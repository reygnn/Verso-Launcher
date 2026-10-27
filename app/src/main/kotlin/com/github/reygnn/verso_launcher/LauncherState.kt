package com.github.reygnn.verso_launcher

import java.util.concurrent.atomic.AtomicReference

/**
 * The launcher's data — config, enumerated apps, recents — and the rules that keep it consistent
 * while a background load and the user's taps overlap. Plays the ViewModel role, in plain Kotlin.
 *
 * Threading: every public member is main-thread only; disk and LauncherApps work runs on [io],
 * results come back through [main]. The IO side reads only the @Volatile counter, and writes only
 * the atomic:
 *   loadGeneration — bumped by [refresh]: the newest requested enumeration wins, older ones bail.
 *   unsaved        — set by [save], taken by whoever writes first: a save's IO task or [flushSave].
 * The config comes in loaded ([initial], read by Core before the state exists, hard rule 5): there
 * is no "not loaded yet", and nothing on the IO side ever reads it.
 * An enumeration carries no config (system labels only); custom names are applied on main from
 * the live config ([relabel]), so no edit can make an enumeration stale.
 *
 * Every collaborator is passed, none defaulted: a wire MainActivity forgot would otherwise be
 * a silent no-op. Tests build it through testLauncherState, which supplies inert ones.
 *
 * @param initial the config as read from verso.json (the empty one for a fresh install or a file
 *   that could not be read, hard rule 4).
 * @param freshInstall nothing stood for verso.json: the default favorites wait for a first list
 *   (hard rule 10).
 * @param enumerateApps null if the enumeration failed — the last good list is kept; an
 *   incomplete one is shown but retried on the next resume ([needsRefresh]).
 * @param process what outlives this instance. Its seedPending ends with the seeding itself,
 *   [toggleFavorite] and [adopt]; while it is set the favorites are empty (a fresh config has
 *   none, the first toggle ends it), so no other edit can touch them.
 * @param onRefreshed configReplaced: drop anything holding the old config (a rename dialog);
 *   listChanged false: nothing to re-render (a re-render would void a tap in flight).
 */
internal class LauncherState(
    private val io: (Runnable) -> Boolean,
    private val main: (Runnable) -> Unit,
    initial: VersoConfig,
    freshInstall: Boolean,
    private val configFile: ConfigFile,
    private val enumerateApps: () -> Enumeration?,
    private val defaultFavoriteCandidates: () -> List<List<String>>,
    private val recentsFile: RecentsFile,
    private val usageFile: UsageFile,
    private val process: ProcessState,
    private val now: () -> Long,
    private val onSaveFailed: () -> Unit,
    private val onRefreshed: (configReplaced: Boolean, listChanged: Boolean) -> Unit,
) {
    /** Immutable: every change goes through this class (and its save) as a new config. */
    val config: VersoConfig get() = cfg
    private var cfg: VersoConfig = initial

    init {
        if (freshInstall) process.seedPending.set(true)
    }

    /** [enumerated] under the live custom names, in drawer order. */
    var apps: List<AppEntry> = emptyList()
        private set

    private var enumerated: List<AppEntry> = emptyList()

    /** Until then an empty [apps] means "not loaded yet": no no-favorites hint flash. */
    var appsLoaded = false
        private set

    /** Refresh on resume even without a package change: no package event retries a failed or incomplete load. */
    val needsRefresh: Boolean
        get() = !lastLoadCommitted || process.seedPending.get()

    private var lastLoadCommitted = false

    /** The "?" list, newest first. recents.txt is written only once it was read, like usage.txt. */
    val recentKeys: List<String> get() = process.recents.value

    /** The launches behind the "%" order. Written only once the file was read (or it was lost). */
    val usage: Map<String, Usage> get() = process.usage.value

    /**
     * Proof an edit is for this state: a permit of another one ("~restart" replaced it, and a window
     * still holds it) edits nothing here, so its save cannot write the old config over the new one.
     */
    class EditPermit internal constructor(internal val from: LauncherState)

    fun editPermit(): EditPermit = EditPermit(this)

    @Volatile private var loadGeneration = 0

    /** The newest config not yet written; a newer save replaces it (SharedPreferences skips stale states too). */
    private val unsaved = AtomicReference<String?>(null)

    /** Taking [unsaved] and writing it are one step, so [flushSave] waits for a write the IO thread has begun. */
    private val writeLock = Any()

    fun favorites(): List<AppEntry> = LauncherLogic.favoritesInDisplayOrder(apps, cfg.favorites)

    /** Re-enumerate the apps off the main thread (and, until they are read, recents.txt and usage.txt). */
    fun refresh() {
        val generation = ++loadGeneration
        val loadUsageNow = !process.usage.loaded
        val loadRecentsNow = !process.recents.loaded
        val seedStillPending = process.seedPending.get()
        io(Runnable {
            if (generation != loadGeneration) return@Runnable  // superseded while queued
            val diskUsage = if (loadUsageNow) usageFile.read() else null
            val diskRecents = if (loadRecentsNow) recentsFile.read() else null
            val enumeration = enumerateApps()
            val loaded = enumeration?.apps
            // From any enumeration with a list, complete or not: one that never completes (a
            // profile that stays unreadable) must not starve the defaults.
            val seeds = if (seedStillPending && loaded != null) {
                LauncherLogic.pickDefaultFavorites(loaded, defaultFavoriteCandidates())
            } else emptyList()
            main(Runnable {
                if (generation != loadGeneration) return@Runnable  // superseded meanwhile: the newer one brings the list
                // Still pending here means nothing touched the favorites since the request.
                var seeded = false
                if (loaded != null && process.seedPending.get()) {
                    process.seedPending.set(false)
                    if (seeds.isNotEmpty() && cfg.favorites.isEmpty()) {
                        cfg = cfg.copy(favorites = cfg.favorites + seeds)
                        mutated()
                        seeded = true
                    }
                }
                val kept = process.usage
                if (diskUsage != null && !kept.loaded) {
                    val counted = kept.value
                    kept.value = UsageLog.merged(diskUsage, counted, now())
                    kept.loaded = true
                    if (counted.isNotEmpty()) persistUsage()
                }
                var recentsChanged = false
                val recents = process.recents
                if (diskRecents != null && !recents.loaded) {
                    val launched = recents.value
                    recents.value = RecentsLog.merged(diskRecents, launched, RECENTS_KEPT)
                    recents.loaded = true
                    recentsChanged = recents.value != launched
                    if (launched.isNotEmpty()) persistRecents()
                }
                lastLoadCommitted = enumeration?.complete == true
                // Most refreshes bring the same list; AppEntry ==, status included, sees any change.
                val changed = seeded || recentsChanged || (loaded != null && (!appsLoaded || loaded != enumerated))
                if (loaded != null) {
                    enumerated = loaded
                    appsLoaded = true
                }
                if (changed) relabel()
                onRefreshed(false, changed)
            })
        })
    }

    /** Serialized on main (a consistent config), written on IO. */
    fun save() {
        val payload = configFile.serialize(cfg)
        val earlier = unsaved.getAndSet(payload)
        val accepted = io(Runnable {
            if (writeUnsaved() == false) main(Runnable { onSaveFailed() })
        })
        if (!accepted) {
            // Give the save queued ahead its own state back: this one never runs.
            unsaved.compareAndSet(payload, earlier)
            onSaveFailed()
        }
    }

    /**
     * Main thread, from onStop: what SharedPreferences' apply guarantees through
     * QueuedWork.waitToFinish — a save not yet on disk is written now, on this thread, and one
     * being written is waited for. So no change is lost to a process ended after the stop.
     */
    fun flushSave() {
        if (writeUnsaved() == false) onSaveFailed()
    }

    /** Null if there was nothing to write: the newest state is on disk, or being written. */
    private fun writeUnsaved(): Boolean? = synchronized(writeLock) {
        unsaved.getAndSet(null)?.let { configFile.write(it) }
    }

    fun toggleHidden(permit: EditPermit, key: String) {
        if (permit.from !== this) return
        cfg = cfg.copy(hidden = cfg.hidden.toggled(key))
        mutated()
    }

    /** The user's own favorites, even none after a toggle back: no default is seeded over them. */
    fun toggleFavorite(permit: EditPermit, key: String) {
        if (permit.from !== this) return
        cfg = cfg.copy(favorites = cfg.favorites.toggled(key))
        process.seedPending.set(false)
        mutated()
    }

    /** An app left with no tags loses its key: the file drops empty lists, memory must match. */
    fun toggleTag(permit: EditPermit, key: String, tag: String) {
        if (permit.from !== this) return
        cfg = cfg.copy(tags = cfg.tags.withList(key, LauncherLogic.toggleTag(cfg.tags[key], tag)))
        mutated()
    }

    /** False, nothing saved, when the move is impossible or a no-op. */
    fun moveFavorite(permit: EditPermit, pickedKey: String, targetKey: String): Boolean {
        if (permit.from !== this) return false
        val newOrder = LauncherLogic.reorder(cfg.favorites, pickedKey, targetKey)
            ?: return false
        cfg = cfg.copy(favorites = newOrder)
        mutated()
        return true
    }

    /** A blank name, or one equal to [systemLabel], clears the override; [tags] arrive canonical. */
    fun rename(permit: EditPermit, key: String, systemLabel: String, name: String, tags: List<String>) {
        if (permit.from !== this) return
        // Both sides cleaned: the dialog starts from the label cut to 100 chars, and another
        // profile's badged label is built from the cleaned one, so it can be longer (and may carry
        // bidi isolates) — an untouched OK must not turn it into a custom name.
        val override = LauncherLogic.resolveNameOverride(LauncherLogic.cleanName(name), LauncherLogic.cleanName(systemLabel))
        cfg = cfg.copy(
            names = if (override == null) cfg.names - key else cfg.names + (key to override),
            tags = cfg.tags.withList(key, tags),
        )
        mutated()
        relabel()
    }

    fun recordLaunch(key: String) {
        process.recents.value = LauncherLogic.pushRecent(recentKeys, key, RECENTS_KEPT)
        if (process.recents.loaded) persistRecents()
        process.usage.value = UsageLog.recorded(usage, key, now())
        if (process.usage.loaded) persistUsage()
    }

    private fun persistRecents() {
        val keys = recentKeys
        io(Runnable { recentsFile.write(keys) })
    }

    private fun persistUsage() {
        val log = usage
        val at = now()
        io(Runnable { usageFile.write(log, at) })
    }

    /** "%": the plain drawer by usage, or back to A to Z. Null, nothing saved, when refused. */
    fun toggleOrder(permit: EditPermit): Boolean? {
        if (permit.from !== this) return null
        cfg = cfg.copy(byUsage = !cfg.byUsage)
        mutated()
        return cfg.byUsage
    }

    /**
     * A "~restore" or "~undo" (hard rule 7): [next] replaces the config, once [keepUndo] has kept the
     * one it replaces (no undo point, no restore). Main thread, one step: no edit can come between
     * the undo point and the swap.
     */
    fun replace(next: VersoConfig, keepUndo: (String) -> Boolean): Replaced = when {
        next == cfg -> Replaced.SAME
        !keepUndo(configFile.serialize(cfg)) -> Replaced.UNDO_FAILED
        else -> {
            adopt(next)
            Replaced.DONE
        }
    }

    /** Saved like any edit; no re-enumeration: the apps are the same. */
    fun adopt(restored: VersoConfig) {
        cfg = restored
        // The restored favorites, or none, are the user's.
        process.seedPending.set(false)
        mutated()
        relabel()
        onRefreshed(true, true)
    }

    private fun Set<String>.toggled(key: String): Set<String> = if (key in this) this - key else this + key

    private fun List<String>.toggled(key: String): List<String> = if (key in this) this - key else this + key

    private fun Map<String, List<String>>.withList(key: String, list: List<String>): Map<String, List<String>> =
        if (list.isEmpty()) this - key else this + (key to list.toList())

    private fun relabel() {
        apps = LauncherLogic.named(enumerated, cfg.names)
    }

    private fun mutated() = save()

    companion object {
        /** How many "?" shows. */
        const val RECENTS_LIMIT = 8

        /** How many recents.txt keeps: an app that is gone takes no place among the shown. */
        const val RECENTS_KEPT = 4 * RECENTS_LIMIT
    }
}
