package com.github.reygnn.verso_launcher

import java.util.concurrent.atomic.AtomicBoolean

/**
 * The launcher's data — config, enumerated apps, recents — and the rules that keep it consistent
 * while a background load and the user's taps overlap. Plays the ViewModel role, in plain Kotlin.
 *
 * Threading: every public member is main-thread only; disk and LauncherApps work runs on [io],
 * results come back through [main]. The IO side reads only the @Volatile counters:
 *   loadGeneration — bumped by [refresh]: the newest requested enumeration wins, older ones bail.
 *   configEpoch    — bumped by every config change: a first load adopts the disk config only if
 *                    nothing changed it since the request (memory is then the newer truth).
 * An enumeration carries no config (system labels only); custom names are applied on main from
 * the live config ([relabel]), so no edit can make an enumeration stale.
 *
 * @param loadConfig null when verso.json is there but unreadable.
 * @param enumerateApps null if the enumeration failed — the last good list is kept.
 * @param onRefreshed configReplaced: drop anything holding the old config (a rename dialog);
 *   listChanged false: nothing to re-render (a re-render would void a tap in flight).
 */
internal class LauncherState(
    private val io: (Runnable) -> Boolean,
    private val main: (Runnable) -> Unit,
    private val loadConfig: () -> VersoConfig?,
    private val setAsideUnreadable: () -> Boolean = { false },
    private val enumerateApps: () -> List<AppEntry>?,
    private val serialize: (VersoConfig) -> String,
    private val writeConfig: (String) -> Boolean,
    private val onSaveFailed: () -> Unit = {},
    private val onRefreshed: (configReplaced: Boolean, listChanged: Boolean) -> Unit,
    private val isFreshInstall: () -> Boolean = { false },
    private val startedOverFlag: AtomicBoolean = AtomicBoolean(),
    private val defaultFavoriteCandidates: () -> List<List<String>> = { emptyList() },
    initialRecents: List<String> = emptyList(),
    private val onRecentsChanged: (List<String>) -> Unit = {},
    private val loadUsage: () -> Map<String, Usage> = { emptyMap() },
    private val writeUsage: (Map<String, Usage>, Long) -> Unit = { _, _ -> },
    private val now: () -> Long = System::currentTimeMillis,
    initialUsage: Map<String, Usage> = emptyMap(),
    initialUsageLoaded: Boolean = false,
    private val onUsageChanged: (Map<String, Usage>, Boolean) -> Unit = { _, _ -> },
) {
    /** Read-only from outside: every change goes through this class (save, configEpoch). */
    val config: ConfigView get() = cfg
    private var cfg: VersoConfig = VersoConfig()

    /** [enumerated] under the live custom names, in drawer order. */
    var apps: List<AppEntry> = emptyList()
        private set

    private var enumerated: List<AppEntry> = emptyList()

    /** Until then an empty [apps] means "not loaded yet": no no-favorites hint flash. */
    var appsLoaded = false
        private set

    /** Refresh on resume even without a package change: else a failed load waits for the stale timer. */
    val needsRefresh: Boolean
        get() = !configLoaded || !lastLoadCommitted

    private var lastLoadCommitted = false

    var recentKeys: List<String> = initialRecents
        private set

    /** The launches behind the "%" order. Written only once the file was read (or it was lost). */
    var usage: Map<String, Usage> = initialUsage
        private set

    private var usageLoaded = initialUsageLoaded

    /** Read from disk once (or adopted from a restore); after that [config] is the truth. */
    var configLoaded = false
        private set

    fun takeStartedOver(): Boolean = configLoaded && startedOverFlag.getAndSet(false)

    @Volatile private var loadGeneration = 0
    @Volatile private var configEpoch = 0
    @Volatile private var saveSeq = 0

    fun favorites(): List<AppEntry> = LauncherLogic.favoritesInDisplayOrder(apps, cfg.favorites)

    /** Null until loaded — never the empty default (hard rule 5). */
    fun loadedSnapshot(): VersoConfig? = if (configLoaded) cfg.snapshot() else null

    /** Re-enumerate the apps (and, until loaded, read the config first) off the main thread. */
    fun refresh() {
        val generation = ++loadGeneration
        // Captured at request time, not at the read.
        val epoch = configEpoch
        val loadConfigNow = !configLoaded
        val loadUsageNow = !usageLoaded
        io(Runnable {
            if (generation != loadGeneration) return@Runnable  // superseded while queued
            val freshBefore = loadConfigNow && isFreshInstall()
            val read = if (loadConfigNow) loadConfig() else null
            // Unreadable: set aside and start fresh; if it can't be moved, stay unloaded (hard rule 4).
            val setAside = loadConfigNow && read == null && setAsideUnreadable()
            if (setAside) startedOverFlag.set(true)
            val diskConfig = read ?: if (setAside) VersoConfig() else null
            val fresh = freshBefore || setAside
            val diskUsage = if (loadUsageNow) loadUsage() else null
            val loaded = enumerateApps()
            val seeds = if (fresh && loaded != null) {
                LauncherLogic.pickDefaultFavorites(loaded, defaultFavoriteCandidates())
            } else emptyList()
            main(Runnable {
                if (generation != loadGeneration) return@Runnable  // superseded meanwhile
                var replaced = false
                // A restore adopted before this landed is the newer truth.
                if (diskConfig != null && configEpoch == epoch) {
                    cfg = diskConfig
                    configLoaded = true
                    replaced = true
                    if (seeds.isNotEmpty() && cfg.favorites.isEmpty()) {
                        cfg.favorites.addAll(seeds)
                        mutated()
                    }
                }
                if (diskUsage != null && !usageLoaded) {
                    val counted = usage
                    usage = UsageLog.merged(diskUsage, counted, now())
                    usageLoaded = true
                    onUsageChanged(usage, true)
                    if (counted.isNotEmpty()) persistUsage()
                }
                lastLoadCommitted = loaded != null
                // Most refreshes bring the same list; AppEntry ==, status included, sees any change.
                val changed = replaced || (loaded != null && (!appsLoaded || loaded != enumerated))
                if (loaded != null) {
                    enumerated = loaded
                    appsLoaded = true
                }
                if (changed) relabel()
                onRefreshed(replaced, changed)
            })
        })
    }

    /** Serialized on main (a consistent config), written on IO. */
    fun save() {
        // Last line of hard rule 5: the empty default must never overwrite verso.json.
        if (!configLoaded) return
        val payload = serialize(cfg)
        val seq = ++saveSeq
        val accepted = io(Runnable {
            // A newer save queued behind this one carries this state too.
            if (seq != saveSeq) return@Runnable
            if (!writeConfig(payload)) main(Runnable { onSaveFailed() })
        })
        if (!accepted) {
            // Take the number back, or a save queued ahead would skip itself for one that never runs.
            saveSeq--
            onSaveFailed()
        }
    }

    fun toggleHidden(idle: CommandGate.Idle, key: String) = toggleIn(idle, cfg.hidden, key)

    fun toggleFavorite(idle: CommandGate.Idle, key: String) = toggleIn(idle, cfg.favorites, key)

    private fun toggleIn(idle: CommandGate.Idle, set: MutableSet<String>, key: String) {
        if (!configLoaded || !idle.current) return
        if (!set.remove(key)) set.add(key)
        mutated()
    }

    /** An app left with no tags loses its key: the file drops empty lists, memory must match. */
    fun toggleTag(idle: CommandGate.Idle, key: String, tag: String) {
        if (!configLoaded || !idle.current) return
        val next = LauncherLogic.toggleTag(cfg.tags[key], tag)
        if (next.isEmpty()) cfg.tags.remove(key) else cfg.tags[key] = next.toMutableList()
        mutated()
    }

    /** False, nothing saved, when the move is impossible or a no-op. */
    fun moveFavorite(idle: CommandGate.Idle, pickedKey: String, targetKey: String): Boolean {
        if (!configLoaded || !idle.current) return false
        val newOrder = LauncherLogic.reorder(cfg.favorites.toList(), pickedKey, targetKey)
            ?: return false
        cfg.favorites.clear()
        cfg.favorites.addAll(newOrder)  // LinkedHashSet: insertion order is the rank
        mutated()
        return true
    }

    /** A blank name, or one equal to [systemLabel], clears the override; [tags] arrive canonical. */
    fun rename(idle: CommandGate.Idle, key: String, systemLabel: String, name: String, tags: List<String>) {
        if (!configLoaded || !idle.current) return
        val override = LauncherLogic.resolveNameOverride(LauncherLogic.cleanName(name), systemLabel)
        if (override == null) cfg.names.remove(key) else cfg.names[key] = override
        if (tags.isEmpty()) cfg.tags.remove(key) else cfg.tags[key] = tags.toMutableList()
        mutated()
        relabel()
    }

    /** Drop every entry (favorite, hidden, name, tags) for [keys] in one save. */
    private fun forget(keys: Set<String>) {
        // Before the load: unsavable, and the epoch bump would make the first load drop the disk config.
        if (!configLoaded || keys.isEmpty()) return
        cfg.favorites.removeAll(keys)
        cfg.hidden.removeAll(keys)
        cfg.names.keys.removeAll(keys)
        cfg.tags.keys.removeAll(keys)
        mutated()
        relabel()
    }

    /** A confirmed "~cleanup" (hard rule 14): recheck and undo point on IO, then [forget] on main. */
    fun cleanup(
        confirmed: Set<String>,
        recheck: (VersoConfig) -> CleanupPlan,
        writeUndo: (String) -> String?,
        onDone: (CleanupOutcome) -> Unit,
    ) {
        if (!configLoaded) {
            onDone(CleanupOutcome.Refused)
            return
        }
        // Identity, not content: a restore swaps the object, an edit mutates it in place.
        val base = cfg
        val snapshot = cfg.snapshot()
        val payload = serialize(cfg)
        val accepted = io(Runnable {
            // Reported from finally: the "~" command run ends only with it (hard rule 15).
            var outcome: CleanupOutcome = CleanupOutcome.Refused
            try {
                outcome = when (val plan = recheck(snapshot)) {
                    CleanupPlan.Incomplete -> CleanupOutcome.Incomplete
                    CleanupPlan.NothingToDo -> CleanupOutcome.NothingToDo
                    is CleanupPlan.Offer -> {
                        val keys = plan.orphans.keys intersect confirmed
                        if (keys.isEmpty()) CleanupOutcome.NothingToDo
                        else writeUndo(payload)?.let { CleanupOutcome.Done(it, keys) } ?: CleanupOutcome.UndoFailed
                    }
                }
            } finally {
                val result = outcome
                main(Runnable {
                    when {
                        result !is CleanupOutcome.Done -> onDone(result)
                        cfg !== base -> onDone(CleanupOutcome.Superseded)
                        else -> {
                            forget(result.keys)
                            onDone(result)
                        }
                    }
                })
            }
        })
        if (!accepted) onDone(CleanupOutcome.Refused)
    }

    fun recordLaunch(key: String) {
        recentKeys = LauncherLogic.pushRecent(recentKeys, key, RECENTS_LIMIT)
        onRecentsChanged(recentKeys)
        usage = UsageLog.recorded(usage, key, now())
        onUsageChanged(usage, usageLoaded)
        if (usageLoaded) persistUsage()
    }

    private fun persistUsage() {
        val log = usage
        val at = now()
        io(Runnable { writeUsage(log, at) })
    }

    /** "%": the plain drawer by usage, or back to A to Z. Null, nothing saved, when refused. */
    fun toggleOrder(idle: CommandGate.Idle): Boolean? {
        if (!configLoaded || !idle.current) return null
        cfg.byUsage = !cfg.byUsage
        mutated()
        return cfg.byUsage
    }

    /** The epoch bump keeps a first load still in flight from landing over the restore. */
    fun adopt(restored: VersoConfig) {
        cfg = restored
        configLoaded = true
        ++configEpoch  // no save: the restore has written verso.json already
        relabel()
        refresh()
    }

    private fun relabel() {
        apps = LauncherLogic.named(enumerated, cfg.names)
    }

    private fun mutated() {
        ++configEpoch
        save()
    }

    companion object {
        const val RECENTS_LIMIT = 8
    }
}
