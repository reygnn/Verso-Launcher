package com.github.reygnn.verso_launcher

/**
 * The launcher's data: the config (favorites, hidden apps, custom names, tags), the enumerated
 * app list and the in-memory recents — plus the rules for keeping them consistent while a
 * background load and the user's taps overlap.
 *
 * Plain Kotlin, no Android, no AndroidX. It plays the role a ViewModel would, without the
 * dependency: the Activity owns the views and the drawer's view state (mode, list rows, tap
 * guards); this owns the data and the concurrency rules, which are the part that is easy to get
 * subtly wrong and therefore the part worth testing ([LauncherStateTest] drives every overlap
 * deterministically by running the two "threads" as manual queues).
 *
 * THREADING. Every public member is for the main thread only — config and app list are only
 * ever read and mutated there. Disk and LauncherApps work runs on [io] (the process's single IO
 * thread, VersoApp.io), and its results come back through [main] (runOnUiThread, dropped for a
 * destroyed activity). Nothing here touches a
 * mutable field from the IO side except the @Volatile generation counters, which an IO task
 * reads to bail out early.
 *
 * THE THREE COUNTERS keep three concerns from stepping on each other:
 *   loadGeneration  — bumped by [refresh] only. "Newest enumeration wins": an older in-flight
 *                     load bails (before its work if still queued, at the hand-off otherwise).
 *   labelGeneration — bumped by [rename] only. A rename changes what enumeration produces
 *                     (labels), so a load whose name snapshot predates it must not commit its
 *                     now-stale app list.
 *   configEpoch     — bumped by ANY config mutation. Guards a disk reload's wholesale config
 *                     replace: if the config changed since the read, memory holds the newer
 *                     truth and must not be clobbered by the older file.
 * A membership toggle therefore never throws an enumeration away (new installs still surface);
 * only a rename invalidates one, because only a rename makes its labels wrong.
 *
 * @param io            submit a task to the IO thread; false if it was refused (shutting down).
 * @param main          run a task on the main thread (posted — never inline from IO).
 * @param loadConfig    read verso.json (IO thread).
 * @param enumerateApps list the launchable apps for a config snapshot (IO thread); null if the
 *                      enumeration itself failed — the last-known-good list is then kept.
 * @param serialize     config -> file text (main thread, so it reads a consistent config).
 * @param writeConfig   persist that text durably (IO thread); true on success.
 * @param onRefreshed   main thread, after a [refresh] hand-off was applied. [configReplaced] is
 *                      true when the config was swapped wholesale for the disk copy — anything
 *                      that captured the old one (an open rename dialog) must go.
 * @param isFreshInstall true when neither verso.json nor its .bak exists yet (IO thread, before
 *                      the read). A lone recoverable .tmp still counts as fresh; seeding is then
 *                      skipped anyway, because it only fills a config with no favorites.
 * @param defaultFavoriteCandidates the candidate keys per default favorite slot, see
 *                      [LauncherLogic.pickDefaultFavorites] (IO thread, fresh install only).
 * @param initialRecents the "?" list to start from — the process-wide one, so an Activity
 *                      recreated by a configuration change does not lose it.
 * @param onRecentsChanged main thread, after every change to [recentKeys], to keep that
 *                      process-wide copy current.
 */
internal class LauncherState(
    private val io: (Runnable) -> Boolean,
    private val main: (Runnable) -> Unit,
    private val loadConfig: () -> VersoConfig,
    private val enumerateApps: (VersoConfig) -> List<AppEntry>?,
    private val serialize: (VersoConfig) -> String,
    private val writeConfig: (String) -> Boolean,
    private val onRefreshed: (configReplaced: Boolean) -> Unit,
    private val isFreshInstall: () -> Boolean = { false },
    private val defaultFavoriteCandidates: () -> List<List<String>> = { emptyList() },
    initialRecents: List<String> = emptyList(),
    private val onRecentsChanged: (List<String>) -> Unit = {},
) {
    /** The live config. Read freely on the main thread; mutate only through this class. */
    var config: VersoConfig = VersoConfig()
        private set

    /** Every launchable app in drawer order ([LauncherLogic.sortedForDrawer]: the device language's alphabet). */
    var apps: List<AppEntry> = emptyList()
        private set

    /** False until the first enumeration has committed: an empty [apps] then means "not
     *  loaded yet", not "no apps" — the home page must not flash its no-favorites hint. */
    var appsLoaded = false
        private set

    /**
     * True while the launcher is not current: the config is not loaded yet, or the last load
     * that reached its hand-off did not commit its app list (the enumeration failed, or a
     * rename made its labels stale). The Activity refreshes on resume while this holds, even
     * when no package change was reported — otherwise a dropped load is only retried once the
     * stale timer runs out.
     */
    val needsRefresh: Boolean
        get() = !configLoaded || !lastLoadCommitted

    // Whether the newest load to reach its hand-off committed its app list.
    private var lastLoadCommitted = false

    /** The "?" view's keys, newest first. In memory only — empty after every process start. */
    var recentKeys: List<String> = initialRecents
        private set

    /**
     * True once [config] holds the real config (read from disk, or adopted from a restore).
     * The config is read from disk once, then [config] IS the cache: the app is its only
     * writer, so re-reading it on every resume would be wasted I/O. "~load" forces a read.
     */
    var configLoaded = false
        private set

    /**
     * A "~load" asked for that has not been carried out yet. A refresh is superseded by any
     * newer one, and a plain resume-refresh does not read the file — so without this a
     * "~load" followed by a quick pause/resume (a dialog, a translucent activity) was
     * silently dropped. Cleared only when a load that read the file reaches its hand-off.
     */
    private var reloadPending = false

    @Volatile private var loadGeneration = 0
    @Volatile private var labelGeneration = 0
    @Volatile private var configEpoch = 0
    // Counts save requests; read on the IO thread to skip a write a newer one supersedes.
    @Volatile private var saveSeq = 0

    /** The launchable favorites in their stored order — what the home page shows. */
    fun favorites(): List<AppEntry> = LauncherLogic.favoritesInDisplayOrder(apps, config.favorites)

    /**
     * Re-enumerate the apps (and, on the first call or with [reloadConfig], re-read the config)
     * off the main thread. The Activity calls it on a resume when apps may have changed or
     * [needsRefresh] holds, on a reported package or profile change while in front, and for
     * "~load" and a restore. The most recently REQUESTED refresh wins; see the class comment for what a concurrent toggle or
     * rename does to an in-flight one.
     */
    fun refresh(reloadConfig: Boolean = false) {
        val generation = ++loadGeneration
        // Captured at REQUEST time, together with the snapshot the load reads from: if either
        // advances before the hand-off, an in-memory change already applied the newer truth.
        val labelGen = labelGeneration
        val epoch = configEpoch
        if (reloadConfig) reloadPending = true
        val loadConfigNow = reloadPending || !configLoaded
        // Snapshot on the main thread — the loader must never read the live config, which a
        // rename/toggle may be mutating. Skipped when the load reads the disk copy anyway.
        val cached = if (loadConfigNow) VersoConfig() else config.snapshot()
        io(Runnable {
            if (generation != loadGeneration) return@Runnable  // superseded while queued
            // Checked before the read: only a config that is not on disk at all gets seeded.
            val fresh = loadConfigNow && isFreshInstall()
            val useCfg = if (loadConfigNow) loadConfig() else cached
            val loaded = enumerateApps(useCfg)
            // Seeding needs the enumeration (a default app must be launchable to be a
            // favorite), so a failed one seeds nothing; the next cold start tries again,
            // since nothing was saved.
            val seeds = if (fresh && loaded != null) {
                LauncherLogic.pickDefaultFavorites(loaded, defaultFavoriteCandidates())
            } else emptyList()
            main(Runnable {
                if (generation != loadGeneration) return@Runnable  // superseded meanwhile
                // The file was read: the reload is done — adopted below, or deliberately not,
                // because memory changed since the read and is the newer truth.
                if (loadConfigNow) reloadPending = false
                var replaced = false
                // Adopt the disk copy only if nothing changed the config since the read; on a
                // cold start nothing can have, so this always adopts there.
                if (loadConfigNow && configEpoch == epoch) {
                    config = useCfg
                    configLoaded = true
                    replaced = true
                    // First start: give the home page something to show. Saving here writes
                    // verso.json, so later starts are no longer fresh and never re-seed — a
                    // user who removes every favorite keeps an empty home page.
                    if (seeds.isNotEmpty() && config.favorites.isEmpty()) {
                        config.favorites.addAll(seeds)
                        mutated()
                    }
                }
                // Commit the enumeration unless a rename made its labels stale. null means the
                // enumeration itself failed (e.g. a system_server restart): keep the last
                // known-good list rather than blanking the launcher.
                lastLoadCommitted = labelGeneration == labelGen && loaded != null
                if (lastLoadCommitted && loaded != null) {
                    apps = loaded
                    appsLoaded = true
                }
                onRefreshed(replaced)
            })
        })
    }

    /**
     * Persist the config: serialized here on the main thread (an immutable snapshot of a
     * consistent config), written on the IO thread so a tap never waits on disk. [onDone] —
     * used by "~save", which has to report something true — gets the real result, back on the
     * main thread; if the IO thread refused the task it gets false at once.
     */
    fun save(onDone: ((Boolean) -> Unit)? = null) {
        // Before the first load lands, [config] is the empty default, not the user's config.
        // Writing it would rotate the real file into .bak and publish an empty primary — one
        // more save, or a process death before the hand-off, and every favorite is gone. Every
        // mutation path already runs after the load (or an adopt), so this only ever stops an
        // early "~save"; it reports failure, which is the truth.
        if (!configLoaded) {
            onDone?.invoke(false)
            return
        }
        val payload = serialize(config)
        val seq = ++saveSeq
        val accepted = io(Runnable {
            // Latest wins: when a newer save is already queued behind this one (ticking 30
            // apps in "-" mode queues 30), its payload carries this state too — skip the
            // durable write (a parse, a rename, 2-3 fsyncs) and let that one do it. An
            // explicit "~save" (onDone) is never skipped: it reports a real write.
            if (onDone == null && seq != saveSeq) return@Runnable
            val ok = writeConfig(payload)
            onDone?.let { cb -> main(Runnable { cb(ok) }) }
        })
        if (!accepted) {
            // Refused (the executor is gone): take the number back, or a save still queued
            // ahead would see itself superseded by one that never runs, and skip its write.
            saveSeq--
            onDone?.invoke(false)
        }
    }

    /** Hide / unhide [key] ("-" mode). */
    fun toggleHidden(key: String) = toggleIn(config.hidden, key)

    /** Favorite / unfavorite [key] ("!" mode). A new favorite goes to the end of the order. */
    fun toggleFavorite(key: String) = toggleIn(config.favorites, key)

    private fun toggleIn(set: MutableSet<String>, key: String) {
        if (!set.remove(key)) set.add(key)
        mutated()
    }

    /**
     * Put [tag] on [key] or take it off ("##tag" mode). An app left with no tags loses its key
     * entirely — the file format drops empty lists, so keeping one would make memory disagree
     * with the file it was just written to.
     */
    fun toggleTag(key: String, tag: String) {
        val next = LauncherLogic.toggleTag(config.tags[key], tag)
        if (next.isEmpty()) config.tags.remove(key) else config.tags[key] = next.toMutableList()
        mutated()
    }

    /**
     * Move favorite [pickedKey] into [targetKey]'s slot ("!!" mode). False — and nothing saved —
     * when the move is impossible or a no-op (either key gone, or the same row).
     */
    fun moveFavorite(pickedKey: String, targetKey: String): Boolean {
        val newOrder = LauncherLogic.reorder(config.favorites.toList(), pickedKey, targetKey)
            ?: return false
        config.favorites.clear()
        config.favorites.addAll(newOrder)  // LinkedHashSet: insertion order IS the rank
        mutated()
        return true
    }

    /**
     * Commit the long-press dialog for [key]: its custom [name] and [tags]. A blank name, or one
     * identical to [systemLabel], clears the override ([LauncherLogic.resolveNameOverride]);
     * [tags] arrive already canonical. The new label is applied to [apps] in memory — no
     * re-enumeration — and the list re-sorted, since a rename can move an app alphabetically.
     *
     * Bumps labelGeneration so an in-flight load holding a pre-rename name snapshot drops its
     * stale list, and configEpoch so an in-flight "~load" keeps this name over the disk copy.
     * Installs/uninstalls since the last load are picked up by the next [refresh], not here.
     *
     * Ignored before the config is loaded (the home page can already show cached favorites
     * then): the edit could not be saved, and its epoch bump would make the first load drop
     * both the disk config and its app list.
     */
    fun rename(key: String, systemLabel: String, name: String, tags: List<String>) {
        if (!configLoaded) return
        // Cleaned here, for every caller: the dialog filters as you type, but what is stored
        // must hold the rule whoever commits it.
        val override = LauncherLogic.resolveNameOverride(LauncherLogic.cleanName(name), systemLabel)
        if (override == null) config.names.remove(key) else config.names[key] = override
        if (tags.isEmpty()) config.tags.remove(key) else config.tags[key] = tags.toMutableList()
        ++labelGeneration
        mutated()
        apps = apps.map { e ->
            if (e.key != key) e else e.withLabel(config.names[key] ?: e.systemLabel)
        }.let { LauncherLogic.sortedForDrawer(it) }
    }

    /**
     * Remove every entry for [keys] — favorite, hidden, name, tags — in one save ("~cleanup",
     * for apps that are no longer installed). Ignored before the config is loaded, like every
     * other edit that could not be saved.
     */
    fun forget(keys: Set<String>) {
        // Before the load an edit cannot be saved — and its epoch bump would make the first
        // load drop the disk config it is waiting for.
        if (!configLoaded || keys.isEmpty()) return
        config.favorites.removeAll(keys)
        config.hidden.removeAll(keys)
        config.names.keys.removeAll(keys)
        config.tags.keys.removeAll(keys)
        mutated()
    }

    /**
     * Carry out a confirmed "~cleanup" of [confirmed] keys, in the restore's order (hard rule 14):
     * on the IO thread, [recheck] the apps again for the config as it is NOW (an app may have
     * come back, a profile may be unreadable, the config may have been replaced while the dialog
     * was up) and keep only what is still an orphan AND was confirmed; write the untouched config
     * as undo point ([writeUndo]: its name, or null); only then, back on main, [forget] them.
     * No undo point, no cleanup. [onDone] gets the outcome on the main thread. A cleanup
     * [CleanupOutcome.Superseded] at the last step has already written its undo point, which —
     * like any — replaced the previous cleanup's; it holds the config of before the replace.
     */
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
        // The config object itself: a wholesale replace (adopt, a reload) swaps it, while an
        // edit mutates it in place — so this tells "replaced meanwhile" from "edited meanwhile".
        val base = config
        val snapshot = config.snapshot()
        val payload = serialize(config)
        val accepted = io(Runnable {
            val outcome = when (val plan = recheck(snapshot)) {
                CleanupPlan.Incomplete -> CleanupOutcome.Incomplete
                CleanupPlan.NothingToDo -> CleanupOutcome.NothingToDo
                is CleanupPlan.Offer -> {
                    val keys = plan.orphans.keys intersect confirmed
                    if (keys.isEmpty()) CleanupOutcome.NothingToDo
                    else writeUndo(payload)?.let { CleanupOutcome.Done(it, keys) } ?: CleanupOutcome.UndoFailed
                }
            }
            main(Runnable {
                when {
                    outcome !is CleanupOutcome.Done -> onDone(outcome)
                    config !== base -> onDone(CleanupOutcome.Superseded)
                    else -> {
                        forget(outcome.keys)
                        onDone(outcome)
                    }
                }
            })
        })
        if (!accepted) onDone(CleanupOutcome.Refused)
    }

    /** Record a launch that actually started, for the "?" view. */
    fun recordLaunch(key: String) {
        recentKeys = LauncherLogic.pushRecent(recentKeys, key, RECENTS_LIMIT)
        onRecentsChanged(recentKeys)
    }

    /**
     * Adopt a restored config wholesale ("~restore"). The caller has already parsed it (a
     * malformed backup never gets here) and written the outgoing config out as the undo point.
     * Bumps configEpoch for the same reason a reload checks it: an enumeration already in
     * flight captured the OLD config and must not hand it back over this one. Saves, then
     * re-renders through a [refresh] — without re-reading the file just queued for writing.
     */
    fun adopt(restored: VersoConfig) {
        config = restored
        configLoaded = true
        // A restore supersedes a "~load" still pending: the file it would read is about to be
        // the restored config anyway, and the refresh below must not re-read it.
        reloadPending = false
        mutated()
        refresh()
    }

    /** The common tail of every config change: advance the epoch, then persist. */
    private fun mutated() {
        ++configEpoch
        save()
    }

    companion object {
        /** How many apps "?" remembers. */
        const val RECENTS_LIMIT = 8
    }
}
