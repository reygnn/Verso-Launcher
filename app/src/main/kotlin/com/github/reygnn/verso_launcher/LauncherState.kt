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
 * THE TWO COUNTERS keep two concerns from stepping on each other:
 *   loadGeneration  — bumped by [refresh] only. "Newest enumeration wins": an older in-flight
 *                     load bails (before its work if still queued, at the hand-off otherwise).
 *   configEpoch     — bumped by ANY config mutation. Guards a disk read's wholesale config
 *                     replace (the first load, [reload]): if the config changed since the read,
 *                     memory holds the newer truth and must not be clobbered by the older file.
 *
 * AN ENUMERATION CARRIES NO CONFIG. It lists the apps under their own (system) labels; the
 * custom names are applied here, on the main thread, from the live config ([relabel]) — at its
 * hand-off and after every change to the names. So nothing the user does in the meantime can make
 * an enumeration stale: a toggle, a rename, a restore never throws one away, and new installs
 * always surface.
 *
 * @param io            submit a task to the IO thread; false if it was refused (shutting down).
 * @param main          run a task on the main thread (posted — never inline from IO).
 * @param loadConfig    read verso.json (IO thread): null when a published copy exists but cannot
 *                      be read (ConfigStore.load) — never an empty stand-in for it.
 * @param enumerateApps list the launchable apps under their system labels — no custom name
 *                      applied (IO thread); null if the enumeration itself failed — the
 *                      last-known-good list is then kept. Any order is right; drawer order by
 *                      system label makes the main thread's re-sort ([relabel]) nearly free.
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
    private val loadConfig: () -> VersoConfig?,
    private val enumerateApps: () -> List<AppEntry>?,
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

    /**
     * Every launchable app as shown: under its custom name if it has one, in drawer order (the
     * device language's alphabet) — [LauncherLogic.named] of [enumerated] and the live names.
     */
    var apps: List<AppEntry> = emptyList()
        private set

    /** The last committed enumeration, as the system reports it: system labels, no names. */
    private var enumerated: List<AppEntry> = emptyList()

    /** False until the first enumeration has committed: an empty [apps] then means "not
     *  loaded yet", not "no apps" — the home page must not flash its no-favorites hint. */
    var appsLoaded = false
        private set

    /**
     * True while the launcher is not current: the config is not loaded yet, or the last load
     * that reached its hand-off did not commit its app list (the enumeration failed). The
     * Activity refreshes on resume while this holds, even when no package change was reported —
     * otherwise a failed load is only retried once the stale timer runs out.
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
     * writer, so re-reading it on every resume would be wasted I/O. "~load" ([reload]) forces
     * a read.
     *
     * Every edit and every save waits for it: before, [config] is the empty default, and an
     * edit on it — saved — would push the real file out (hard rule 5).
     */
    var configLoaded = false
        private set

    /**
     * True while the config exists on disk but could not be read: the last read found a
     * published copy and nothing that parses (ConfigStore.load gave null). The config then stays
     * NOT loaded — no edit, no save, nothing of the empty default reaches the disk — and every
     * [refresh] tries again ([needsRefresh] holds), as does "~load". A restore ([adopt]) is the
     * way out when the file stays unreadable. False again once a config is loaded.
     */
    var configUnreadable = false
        private set

    @Volatile private var loadGeneration = 0
    @Volatile private var configEpoch = 0
    // Counts save requests; read on the IO thread to skip a write a newer one supersedes.
    @Volatile private var saveSeq = 0

    /** The launchable favorites in their stored order — what the home page shows. */
    fun favorites(): List<AppEntry> = LauncherLogic.favoritesInDisplayOrder(apps, config.favorites)

    /**
     * Re-enumerate the apps (and, until the config is loaded, read it first) off the main thread.
     * The Activity calls it on a resume when apps may have changed or [needsRefresh] holds, and
     * on a reported package or profile change while in front; [reload] and [adopt] call it after
     * replacing the config. The most recently REQUESTED refresh wins; nothing else drops one
     * (see the class comment).
     */
    fun refresh() {
        val generation = ++loadGeneration
        // Captured at REQUEST time: if it advances before the hand-off, an in-memory change
        // already applied the newer truth, and the disk copy read here must not replace it.
        val epoch = configEpoch
        val loadConfigNow = !configLoaded
        io(Runnable {
            if (generation != loadGeneration) return@Runnable  // superseded while queued
            // Checked before the read: only a config that is not on disk at all gets seeded.
            val fresh = loadConfigNow && isFreshInstall()
            val diskConfig = if (loadConfigNow) loadConfig() else null
            val loaded = enumerateApps()
            // Seeding needs the enumeration (a default app must be launchable to be a
            // favorite), so a failed one seeds nothing; the next cold start tries again,
            // since nothing was saved.
            val seeds = if (fresh && loaded != null) {
                LauncherLogic.pickDefaultFavorites(loaded, defaultFavoriteCandidates())
            } else emptyList()
            main(Runnable {
                if (generation != loadGeneration) return@Runnable  // superseded meanwhile
                var replaced = false
                // The file is there but could not be read: stay not loaded (edits stay off) and
                // say so; the next refresh tries again. Unless a restore was adopted meanwhile.
                if (loadConfigNow && diskConfig == null && !configLoaded) configUnreadable = true
                // Adopt the disk copy only if nothing changed the config since the read — a
                // restore adopted before the first load landed is the newer truth.
                if (diskConfig != null && configEpoch == epoch) {
                    config = diskConfig
                    configLoaded = true
                    configUnreadable = false
                    replaced = true
                    // First start: give the home page something to show. Saving here writes
                    // verso.json, so later starts are no longer fresh and never re-seed — a
                    // user who removes every favorite keeps an empty home page.
                    if (seeds.isNotEmpty() && config.favorites.isEmpty()) {
                        config.favorites.addAll(seeds)
                        mutated()
                    }
                }
                // Commit the enumeration — always, whatever changed meanwhile: it carries no
                // config. null means the enumeration itself failed (e.g. a system_server
                // restart): keep the last known-good list rather than blanking the launcher.
                lastLoadCommitted = loaded != null
                if (loaded != null) {
                    enumerated = loaded
                    appsLoaded = true
                }
                // Shown under the names as they are NOW — a rename, a restore since the request
                // included. Also when only the config arrived: a list committed while the config
                // was unreadable is still showing system labels.
                if (loaded != null || replaced) relabel()
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
            // Reported even if the write throws (it never should — ConfigStore catches its own
            // errors): a "~save" waits for this report behind the busy layer. The exception
            // itself still reaches the IO thread's guard and is logged there.
            var ok = false
            try {
                ok = writeConfig(payload)
            } finally {
                val written = ok
                onDone?.let { cb -> main(Runnable { cb(written) }) }
            }
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
        // Like every edit: none before the config is loaded (see configLoaded).
        if (!configLoaded) return
        if (!set.remove(key)) set.add(key)
        mutated()
    }

    /**
     * Put [tag] on [key] or take it off ("##tag" mode). An app left with no tags loses its key
     * entirely — the file format drops empty lists, so keeping one would make memory disagree
     * with the file it was just written to.
     */
    fun toggleTag(key: String, tag: String) {
        if (!configLoaded) return
        val next = LauncherLogic.toggleTag(config.tags[key], tag)
        if (next.isEmpty()) config.tags.remove(key) else config.tags[key] = next.toMutableList()
        mutated()
    }

    /**
     * Move favorite [pickedKey] into [targetKey]'s slot ("!!" mode). False — and nothing saved —
     * when the move is impossible or a no-op (either key gone, or the same row).
     */
    fun moveFavorite(pickedKey: String, targetKey: String): Boolean {
        // No configLoaded check needed: before the load the favorites are the empty default
        // (every other edit is refused), so there is nothing to move and reorder says null.
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
     * Bumps configEpoch so an in-flight disk read keeps this name over the file. An in-flight
     * enumeration is unaffected: it carries no names, they are applied at its hand-off.
     * Installs/uninstalls since the last load are picked up by the next [refresh], not here.
     *
     * Ignored before the config is loaded (the home page can already show cached favorites
     * then): the edit could not be saved, and its epoch bump would make the first load drop
     * the disk config it is waiting for.
     */
    fun rename(key: String, systemLabel: String, name: String, tags: List<String>) {
        if (!configLoaded) return
        // Cleaned here, for every caller: the dialog filters as you type, but what is stored
        // must hold the rule whoever commits it.
        val override = LauncherLogic.resolveNameOverride(LauncherLogic.cleanName(name), systemLabel)
        if (override == null) config.names.remove(key) else config.names[key] = override
        if (tags.isEmpty()) config.tags.remove(key) else config.tags[key] = tags.toMutableList()
        mutated()
        relabel()
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
        // Normally the keys of apps that are gone, so nothing shown changes — but a forgotten
        // name of an app still listed must not linger on screen.
        relabel()
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
            // Reported even if the re-check or the undo point throws (neither should — both
            // catch their own errors): as Refused, and nothing is removed, since only a Done
            // removes. The exception still reaches the IO thread's guard and is logged there.
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
                        config !== base -> onDone(CleanupOutcome.Superseded)
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

    /** Record a launch that actually started, for the "?" view. */
    fun recordLaunch(key: String) {
        recentKeys = LauncherLogic.pushRecent(recentKeys, key, RECENTS_LIMIT)
        onRecentsChanged(recentKeys)
    }

    /**
     * Adopt a restored config wholesale ("~restore"). The caller has already parsed it (a
     * malformed backup never gets here) and written the outgoing config out as the undo point.
     * Saves, then re-renders through a [refresh] — without re-reading the file just queued for
     * writing. Also the way out of an unreadable config ([configUnreadable]): it loads one.
     */
    fun adopt(restored: VersoConfig) = replace(restored, persist = true)

    /**
     * "~load": read verso.json again ([loadConfig], on [io]) and adopt it wholesale, back on
     * [main]; no save — it IS the file. [onDone] gets the [ReloadOutcome], each with its own
     * message: adopted; the file unreadable (nothing adopted — never an empty stand-in, the
     * config in memory stays as it is); the config changed while the file was read (memory is
     * the newer truth, as for any disk read); the first load still on its way (that load IS the
     * read); or the task refused or failed. While the config is unreadable, "~load" is the way to
     * try again at once. The Activity runs "~load" behind its busy layer (CommandGate), so
     * normally no edit comes in between — but the user may step out of a stalled run, and this
     * class must be right on its own anyway.
     */
    fun reload(onDone: (ReloadOutcome) -> Unit) {
        if (!configLoaded && !configUnreadable) {
            onDone(ReloadOutcome.NotLoaded)
            return
        }
        val epoch = configEpoch
        val accepted = io(Runnable {
            // Reported even if the read throws (it never should — ConfigStore.load catches its
            // own errors), as failed; the exception still reaches the IO guard.
            var threw = true
            var loaded: VersoConfig? = null
            try {
                loaded = loadConfig()
                threw = false
            } finally {
                val read = loaded
                val failed = threw
                main(Runnable {
                    onDone(
                        when {
                            failed -> ReloadOutcome.Failed
                            read == null -> {
                                // Unreadable now, whatever was loaded before: keep what memory
                                // holds. Still unreadable if it was; a loaded config stays loaded.
                                if (!configLoaded) configUnreadable = true
                                ReloadOutcome.Unreadable
                            }
                            configEpoch != epoch -> ReloadOutcome.ChangedMeanwhile
                            else -> {
                                replace(read, persist = false)
                                ReloadOutcome.Adopted
                            }
                        },
                    )
                })
            }
        })
        if (!accepted) onDone(ReloadOutcome.Failed)
    }

    /**
     * The common tail of [adopt] and [reload]: swap the config wholesale and show its names at
     * once ([relabel]), then refresh — a fresh list, and the re-render through its hand-off
     * (onRefreshed). Bumps configEpoch, which a disk read still in flight checks (the first load
     * must not land over a restore adopted before it). [persist] saves the new config (a restore);
     * a reload has just read it from the file.
     */
    private fun replace(next: VersoConfig, persist: Boolean) {
        config = next
        configLoaded = true
        configUnreadable = false
        if (persist) mutated() else ++configEpoch
        relabel()
        refresh()
    }

    /**
     * [apps] from the last enumeration and the live names: the one place a custom name becomes a
     * label ([LauncherLogic.named]). Main thread, after every change to the names and at every
     * hand-off that commits an enumeration or replaces the config.
     */
    private fun relabel() {
        apps = LauncherLogic.named(enumerated, config.names)
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

/** How a "~load" ended ([LauncherState.reload]). */
internal sealed interface ReloadOutcome {
    /** The file was read and adopted. */
    data object Adopted : ReloadOutcome
    /** The file is there but could not be read: nothing adopted, memory as it was. */
    data object Unreadable : ReloadOutcome
    /** The config changed while the file was read: memory, the newer truth, was kept. */
    data object ChangedMeanwhile : ReloadOutcome
    /** The first load is still on its way: it is the read. */
    data object NotLoaded : ReloadOutcome
    /** The IO thread refused the task, or the read failed unexpectedly: nothing changed. */
    data object Failed : ReloadOutcome
}
