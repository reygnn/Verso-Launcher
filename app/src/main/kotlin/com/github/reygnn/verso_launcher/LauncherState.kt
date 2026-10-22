package com.github.reygnn.verso_launcher

/**
 * The launcher's data — config, enumerated apps, recents — and the rules that keep it consistent
 * while a background load and the user's taps overlap. Plays the ViewModel role, in plain Kotlin.
 *
 * Threading: every public member but [writeRestored] (an IO step of a restore) is main-thread
 * only; disk and LauncherApps work runs on [io], results come back through [main]. The IO side
 * reads only the @Volatile counters, and writes only the atomic process.seedPending:
 *   loadGeneration — bumped by [refresh]: the newest requested enumeration wins, older ones bail.
 *   saveSeq        — bumped by [save]: a newer queued save carries this state, an older one skips.
 * Nothing changes the config before it is loaded (no [EditPermit] is issued, [adopt] and [cleanup]
 * refuse), so a first load lands as read.
 * An enumeration carries no config (system labels only); custom names are applied on main from
 * the live config ([relabel]), so no edit can make an enumeration stale.
 *
 * Every collaborator is passed, none defaulted: a wire MainActivity forgot would otherwise be
 * a silent no-op. Tests build it through testLauncherState, which supplies inert ones.
 *
 * @param enumerateApps null if the enumeration failed — the last good list is kept; an
 *   incomplete one is shown but retried on the next resume ([needsRefresh]).
 * @param process what outlives this instance. Its seedPending ends with the seeding itself,
 *   [toggleFavorite], [adopt] and [writeRestored]; while it is set the favorites are empty (a
 *   fresh config has none, the first toggle ends it), so no other edit can touch them.
 * @param idle the command gate's permit, null while a "~" command runs: seeding the default
 *   favorites is an edit like any other (hard rule 15) — a restore's write must not be
 *   overwritten by it.
 * @param onRefreshed configReplaced: drop anything holding the old config (a rename dialog);
 *   listChanged false: nothing to re-render (a re-render would void a tap in flight). Also
 *   called by [adopt], always (true, true).
 */
internal class LauncherState(
    private val io: (Runnable) -> Boolean,
    private val main: (Runnable) -> Unit,
    private val configFile: ConfigFile,
    private val enumerateApps: () -> Enumeration?,
    private val defaultFavoriteCandidates: () -> List<List<String>>,
    private val recentsFile: RecentsFile,
    private val usageFile: UsageFile,
    private val process: ProcessState,
    private val idle: () -> CommandGate.Idle?,
    private val now: () -> Long,
    private val onSaveFailed: () -> Unit,
    private val onRefreshed: (configReplaced: Boolean, listChanged: Boolean) -> Unit,
) {
    /** Immutable: every change goes through this class (and its save) as a new config. */
    val config: VersoConfig get() = cfg
    private var cfg: VersoConfig = VersoConfig()

    // Counts restores (adopt): a restore replaces the config, an edit only changes it — a
    // "~cleanup" still lands on top of an edit, never on top of a restore.
    private var adoptions = 0

    /** [enumerated] under the live custom names, in drawer order. */
    var apps: List<AppEntry> = emptyList()
        private set

    private var enumerated: List<AppEntry> = emptyList()

    /** Until then an empty [apps] means "not loaded yet": no no-favorites hint flash. */
    var appsLoaded = false
        private set

    /**
     * Refresh on resume even without a package change: no package event retries a failed or
     * incomplete load, nor a seeding a running "~" command held back.
     */
    val needsRefresh: Boolean
        get() = !configLoaded || !lastLoadCommitted || process.seedPending.get()

    private var lastLoadCommitted = false

    /** The "?" list, newest first. recents.txt is written only once it was read, like usage.txt. */
    val recentKeys: List<String> get() = process.recents.value

    /** The launches behind the "%" order. Written only once the file was read (or it was lost). */
    val usage: Map<String, Usage> get() = process.usage.value

    /** Read from disk once; after that [config] is the truth. */
    var configLoaded = false
        private set

    fun takeStartedOver(): Boolean = configLoaded && process.startedOver.getAndSet(false)

    /**
     * Proof an edit may run: the config was loaded when it was issued (hard rule 5; it never
     * unloads) and no "~" command runs (hard rule 15) — [current] is false once one has started
     * since. Every edit takes one, so an edit path that skips either check does not compile.
     */
    class EditPermit internal constructor(private val idle: CommandGate.Idle) {
        val current: Boolean get() = idle.current
    }

    /** Null before the config is loaded: an edit then would land on the empty default. */
    fun editPermit(idle: CommandGate.Idle): EditPermit? = if (configLoaded) EditPermit(idle) else null

    @Volatile private var loadGeneration = 0
    @Volatile private var saveSeq = 0

    fun favorites(): List<AppEntry> = LauncherLogic.favoritesInDisplayOrder(apps, cfg.favorites)

    /** Null until loaded — never the empty default (hard rule 5). */
    fun loadedSnapshot(): VersoConfig? = if (configLoaded) cfg else null

    /** Re-enumerate the apps (and, until loaded, read the config first) off the main thread. */
    fun refresh() {
        val generation = ++loadGeneration
        val loadConfigNow = !configLoaded
        val loadUsageNow = !process.usage.loaded
        val loadRecentsNow = !process.recents.loaded
        val seedStillPending = process.seedPending.get()
        io(Runnable {
            if (generation != loadGeneration) return@Runnable  // superseded while queued
            val freshBefore = loadConfigNow && configFile.isFresh()
            val read = if (loadConfigNow) configFile.load() else null
            // Unreadable: set aside and start fresh; if it can't be moved, stay unloaded (hard rule 4).
            val setAside = loadConfigNow && read == null && configFile.setAsideUnreadable()
            if (setAside) process.startedOver.set(true)
            val diskConfig = read ?: if (setAside) VersoConfig() else null
            val fresh = freshBefore || setAside
            val diskUsage = if (loadUsageNow) usageFile.read() else null
            val diskRecents = if (loadRecentsNow) recentsFile.read() else null
            val enumeration = enumerateApps()
            val loaded = enumeration?.apps
            // From any enumeration with a list, complete or not: one that never completes (a
            // profile that stays unreadable) must not starve the defaults.
            val seeds = if ((fresh || seedStillPending) && loaded != null) {
                LauncherLogic.pickDefaultFavorites(loaded, defaultFavoriteCandidates())
            } else emptyList()
            main(Runnable {
                if (generation != loadGeneration) return@Runnable  // superseded meanwhile
                var replaced = false
                if (diskConfig != null) {
                    cfg = diskConfig
                    configLoaded = true
                    replaced = true
                    if (fresh) process.seedPending.set(true)
                }
                // Still pending here means nothing touched the favorites since the request. While a
                // "~" command runs there is no permit: the wait stays, a later refresh seeds.
                val seedPermit = if (configLoaded && loaded != null && process.seedPending.get()) {
                    idle()?.let(::editPermit)
                } else null
                var seeded = false
                if (seedPermit != null) {
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
                // Most refreshes bring the same list; AppEntry ==, status included, sees any change. A
                // seeding held back by a command lands with the same list: home must still show it.
                val changed = replaced || seeded || recentsChanged || (loaded != null && (!appsLoaded || loaded != enumerated))
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
        val payload = configFile.serialize(cfg)
        val seq = ++saveSeq
        val accepted = io(Runnable {
            // A newer save queued behind this one carries this state too.
            if (seq != saveSeq) return@Runnable
            if (!configFile.write(payload)) main(Runnable { onSaveFailed() })
        })
        if (!accepted) {
            // Take the number back, or a save queued ahead would skip itself for one that never runs.
            saveSeq--
            onSaveFailed()
        }
    }

    fun toggleHidden(permit: EditPermit, key: String) {
        if (!permit.current) return
        cfg = cfg.copy(hidden = cfg.hidden.toggled(key))
        mutated()
    }

    /** The user's own favorites, even none after a toggle back: no default is seeded over them. */
    fun toggleFavorite(permit: EditPermit, key: String) {
        if (!permit.current) return
        cfg = cfg.copy(favorites = cfg.favorites.toggled(key))
        process.seedPending.set(false)
        mutated()
    }

    /** An app left with no tags loses its key: the file drops empty lists, memory must match. */
    fun toggleTag(permit: EditPermit, key: String, tag: String) {
        if (!permit.current) return
        cfg = cfg.copy(tags = cfg.tags.withList(key, LauncherLogic.toggleTag(cfg.tags[key], tag)))
        mutated()
    }

    /** False, nothing saved, when the move is impossible or a no-op. */
    fun moveFavorite(permit: EditPermit, pickedKey: String, targetKey: String): Boolean {
        if (!permit.current) return false
        val newOrder = LauncherLogic.reorder(cfg.favorites.toList(), pickedKey, targetKey)
            ?: return false
        cfg = cfg.copy(favorites = newOrder.toSet())  // insertion order is the rank
        mutated()
        return true
    }

    /** A blank name, or one equal to [systemLabel], clears the override; [tags] arrive canonical. */
    fun rename(permit: EditPermit, key: String, systemLabel: String, name: String, tags: List<String>) {
        if (!permit.current) return
        val override = LauncherLogic.resolveNameOverride(LauncherLogic.cleanName(name), systemLabel)
        cfg = cfg.copy(
            names = if (override == null) cfg.names - key else cfg.names + (key to override),
            tags = cfg.tags.withList(key, tags),
        )
        mutated()
        relabel()
    }

    /** Drop every entry (favorite, hidden, name, tags) for [keys] in one save. Only behind an
     *  accepted [cleanup], which refuses before the load. */
    private fun forget(keys: Set<String>) {
        if (keys.isEmpty()) return
        cfg = cfg.copy(
            favorites = cfg.favorites - keys,
            hidden = cfg.hidden - keys,
            names = cfg.names - keys,
            tags = cfg.tags - keys,
        )
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
        val base = adoptions
        val snapshot = cfg
        val payload = configFile.serialize(cfg)
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
                        adoptions != base -> onDone(CleanupOutcome.Superseded)
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
        if (!permit.current) return null
        cfg = cfg.copy(byUsage = !cfg.byUsage)
        mutated()
        return cfg.byUsage
    }

    /**
     * IO thread: writes a restored config to verso.json (hard rule 7) and, once it is written,
     * ends any wait to seed the default favorites — the restored favorites, or none, are the
     * user's (hard rule 10), even when the hand-back to [adopt] is lost to a recreation.
     */
    fun writeRestored(payload: String): Boolean =
        configFile.write(payload).also { written -> if (written) process.seedPending.set(false) }

    /** False, nothing adopted, before the first load: it would land over the restore. No save —
     *  the restore has written verso.json already — and no re-enumeration: the apps are the same. */
    fun adopt(restored: VersoConfig): Boolean {
        if (!configLoaded) return false
        cfg = restored
        adoptions++
        // The restored favorites, or none, are the user's.
        process.seedPending.set(false)
        relabel()
        onRefreshed(true, true)
        return true
    }

    private fun Set<String>.toggled(key: String): Set<String> = if (key in this) this - key else this + key

    /** An empty list removes the key: the file drops empty lists, memory must match. */
    private fun Map<String, List<String>>.withList(key: String, list: List<String>): Map<String, List<String>> =
        if (list.isEmpty()) this - key else this + (key to list.toList())

    private fun relabel() {
        apps = LauncherLogic.named(enumerated, cfg.names)
    }

    private fun mutated() = save()

    companion object {
        /** How many "?" shows. */
        const val RECENTS_LIMIT = 8

        /** How many recents.txt keeps: an app that is gone takes no place among the shown (Kolibri's × 4). */
        const val RECENTS_KEPT = 4 * RECENTS_LIMIT
    }
}
