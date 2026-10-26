package com.github.reygnn.verso_launcher

import android.net.Uri
import android.util.Log

/**
 * The "~" commands' work, owned by the process (VersoApp.commands): "~backup", "~restore" and
 * "~undo", a "~restore-saf" pick's read and adoption, the "~cleanup" check and its run. One at a
 * time through the process's gate (hard rule 15); every step hands back to the process and reads the state
 * from Core, so a run ends and lands whatever happens to the window that started it. Results go
 * through [Results]; a window is reached only as a [LauncherWindow], the live one or none.
 *
 * What a window decides stays there (MainActivity): refusing a command up front (busy, not
 * loaded, a pick pending), clearing the prompt, the picker, "~restart", the cleanup dialog.
 *
 * Main thread only, but for the work handed to [step] and the pick's read thread.
 */
internal class Commands(private val app: VersoApp) {

    private val gate get() = app.gate
    private val core get() = app.core
    private val results get() = app.results
    private val window get() = app.instance

    /**
     * Bumped whenever a window turns away from the page it asked on (the drawer closed, HOME,
     * onStop — which a recreation passes through too): a "~cleanup" check's answer must still match
     * it to be shown.
     * A number, not the window, so a check waiting behind a stuck IO thread holds no activity.
     */
    var pageTurns = 0

    /**
     * One IO step of the run [t] (see [CommandGate.io]): [then] gets null if it failed. Handed back
     * to the process, never dropped: the gate must be released (hard rule 15), and what [then] does
     * to the shared state (a restore's adopt) must land, or memory and verso.json part ways.
     */
    private fun <T : Any> step(t: CommandGate.Ticket, work: () -> T?, then: (T?) -> Unit) =
        gate.io(t, app::submitIo, app::onMain, work, { e -> Log.w("Verso", "command step failed", e) }, then)

    /** "~backup": export the config in memory to Download/Verso (hard rule 6). */
    fun backup() {
        val payload = ConfigJson.serialize(core.state.loadedSnapshot() ?: return)
        val store = core.backupStore
        gate.start { t ->
            step(t, work = {
                val name = store.write(BackupFiles.BACKUP_BASE, payload)
                results.keep(
                    if (name != null) app.getString(R.string.toast_backup_ok, name) else app.getString(R.string.toast_backup_failed)
                )
            }) { kept ->
                // Nothing kept: the step threw.
                if (kept == null) results.report(app.getString(R.string.toast_backup_failed)) else results.flush()
            }
        }
    }

    /** "~restore": the newest own backup. */
    fun restore() = restoreNewest(BackupFiles.Source.BACKUP)

    /** "~undo": the newest own undo point, of a restore, a cleanup or an undo. */
    fun undo() = restoreNewest(BackupFiles.Source.UNDO)

    /** Read and parse the newest own file of [source] on the IO thread, then adopt it. */
    private fun restoreNewest(source: BackupFiles.Source) {
        val store = core.backupStore
        gate.start { t ->
            step(t, work = {
                val latest = store.lookupLatest(source.bases)
                val text = (latest as? BackupStore.Latest.Found)?.let { store.readText(it.uri) }
                val restored = text?.let { ConfigJson.parseForeign(it) }
                if (restored == null) results.keep(readFailure(app, source, latest))
                Read(restored)
            }) { read ->
                val restored = read?.restored
                when {
                    // Nothing kept: the step threw.
                    read == null -> results.report(app.getString(R.string.toast_restore_failed))
                    restored == null -> results.flush()
                    else -> adopt(t, restored, source)
                }
            }
        }
    }

    /** A "~restore" or "~undo" read: null if there was nothing to restore (its message is kept). */
    private class Read(val restored: VersoConfig?)

    /**
     * "~restore-saf" after the picker answered: the pick is the process's from here (VersoApp.pick).
     * Its own thread, not the IO thread (hard rule 3): a provider can block the read indefinitely.
     * The thread holds the process only — the application's resolver, a hand-back to the process.
     * Back on main, a dropped or superseded pick is ignored; an unreadable file is a result of the
     * user's pick; a config waits in VersoApp.pick to be asked about ([offerPendingPick]).
     */
    fun readPick(uri: Uri) {
        val number = app.pick.begin()
        val store = core.backupStore
        Thread(Guard.wrap("restore read", ::logWarning) {
            val restored = store.readText(uri)?.let { ConfigJson.parseForeign(it) }
            app.onMain {
                if (app.pick.arrived(number, restored)) {
                    if (restored == null) results.report(app.getString(R.string.toast_restore_failed)) else offerPendingPick()
                }
            }
        }, "verso-restore-read").start()
    }

    /**
     * Asks the user about a read pick: the window in front shows what the file would set. Only with
     * the config loaded — a first load would replace the config under the open dialog and void it
     * (hard rule 5). Called when the read lands, when a window comes in front (onStart) and when a
     * load lands (Core).
     */
    fun offerPendingPick() {
        if (!core.state.configLoaded) return
        val front = window?.takeIf { it.inFront } ?: return
        val restored = app.pick.ask() ?: return
        front.offerRestore(restored)
    }

    /**
     * The restore dialog's answer: OK adopts (once the gate is idle). Cancel ends the pick without a
     * word; a dialog gone unanswered (the page turned, the window left) ends it with one, a result.
     */
    fun answerPick(confirmed: Boolean, byCancel: Boolean) {
        if (!app.pick.answer(confirmed)) return
        when {
            confirmed -> adoptPendingPick()
            !byCancel -> results.report(app.getString(R.string.toast_restore_saf_dropped))
        }
    }

    /**
     * Adopts a "~restore-saf" pick the user confirmed, once nothing stands in its way: no run and a
     * loaded config (hard rule 5). Called on the OK, when a run ends (the gate's onIdle) and when a
     * load lands (Core) — whichever comes last adopts. It waits instead of being refused: while it
     * is pending no restore can start (MainActivity.runCommand: "~restore", "~restore-saf", "~undo"),
     * so the run it waits for is a backup or a cleanup, never a restore it would replace right after.
     */
    fun adoptPendingPick() {
        if (gate.busy || !core.state.configLoaded) return
        val restored = app.pick.take() ?: return
        // A picked file is a restore: its undo point is a pre-restore one.
        gate.start { t -> adopt(t, restored, BackupFiles.Source.BACKUP) }
    }

    /**
     * Adopt a config parsed with parseForeign (never the lenient parse, which turns any JSON
     * object into an empty config). No undo point, no restore (hard rule 7): [source] names the one
     * it leaves. Both callers start it only with the config loaded; the check below stays as the
     * last line of hard rule 5.
     */
    private fun adopt(t: CommandGate.Ticket, restored: VersoConfig, source: BackupFiles.Source) {
        // So a "my favorites are gone" report can be settled from the log.
        Log.i(
            "Verso",
            "restore (${source.name.lowercase()}): ${restored.favorites.size} favorites, ${restored.hidden.size} hidden, " +
                "${restored.names.size} names, ${restored.tags.size} tagged",
        )
        val state = core.state
        val store = core.backupStore
        val outgoing = state.loadedSnapshot() ?: run {
            results.refuse(app.getString(R.string.toast_not_loaded))
            return
        }
        step(t, work = {
            RestoreSteps.run(
                current = outgoing,
                restored = restored,
                writeUndo = { payload -> store.write(source.undoBase, payload) },
                writeConfig = state::writeRestored,
            ).also { outcome -> results.keep(restoreMessage(app, source, outcome)) }
        }) { outcome ->
            // Nothing kept: the step threw.
            if (outcome == null) {
                results.report(app.getString(R.string.toast_restore_undo_failed))
                return@step
            }
            // Cannot refuse: loaded was checked above. The shared state, whatever the window does.
            if (outcome is RestoreSteps.Outcome.Done) state.adopt(restored)
            results.flush()
        }
    }

    /**
     * "~cleanup": check on the IO thread, then offer what is confirmed gone (hard rule 14). The
     * answer is for the page that asked, while it still asks — no dialog over a page that moved on
     * or a window made since, no toast over another app; else it is dropped (nothing was changed).
     */
    fun checkCleanup() {
        val ticket = pageTurns
        val snapshot = core.state.config
        val apps = core.apps
        gate.start { t ->
            step(t, work = { apps.safeCleanupCheck(snapshot) }) { plan ->
                val asked = window?.takeIf { it.inFront && ticket == pageTurns } ?: return@step
                when (plan) {
                    null -> asked.show(app.getString(R.string.toast_cleanup_not_run), long = false)
                    CleanupPlan.Incomplete -> asked.show(app.getString(R.string.toast_cleanup_incomplete), long = false)
                    CleanupPlan.NothingToDo -> asked.show(app.getString(R.string.toast_cleanup_none), long = false)
                    // The dialog ends this run: no run waits on the user. Its OK starts a new one.
                    is CleanupPlan.Offer -> asked.offerCleanup(plan.orphans)
                }
            }
        }
    }

    /** The cleanup dialog's OK: false if another run is going (the window says so). */
    fun runCleanup(confirmed: Set<String>): Boolean {
        val state = core.state
        val apps = core.apps
        val store = core.backupStore
        return gate.start { t ->
            gate.hold(t)
            state.cleanup(
                confirmed = confirmed,
                recheck = apps::safeCleanupCheck,
                writeUndo = { payload -> store.write(BackupFiles.PRE_CLEANUP_BASE, payload) },
            ) { outcome -> gate.finish(t) { cleanupDone(outcome) } }
        }
    }

    /** A confirmed run's outcome is a result: the live window redraws, the message waits if need be. */
    private fun cleanupDone(outcome: CleanupOutcome) {
        if (outcome is CleanupOutcome.Done) window?.cleanupApplied()
        results.report(cleanupMessage(app, outcome))
    }

    /** The words for an outcome: lookups only, apart from the runs. */
    private companion object {
        fun readFailure(app: VersoApp, source: BackupFiles.Source, latest: BackupStore.Latest): String = app.getString(
            when (latest) {
                is BackupStore.Latest.Failed -> R.string.toast_restore_list_failed
                is BackupStore.Latest.None -> when (source) {
                    BackupFiles.Source.BACKUP -> R.string.toast_restore_none
                    BackupFiles.Source.UNDO -> R.string.toast_undo_none
                }
                is BackupStore.Latest.Found -> R.string.toast_restore_failed
            }
        )

        fun restoreMessage(app: VersoApp, source: BackupFiles.Source, outcome: RestoreSteps.Outcome): String = when (outcome) {
            RestoreSteps.Outcome.Unchanged -> app.getString(
                when (source) {
                    BackupFiles.Source.BACKUP -> R.string.toast_restore_same
                    BackupFiles.Source.UNDO -> R.string.toast_undo_same
                }
            )
            RestoreSteps.Outcome.WriteFailed -> app.getString(R.string.toast_restore_write_failed)
            RestoreSteps.Outcome.UndoFailed -> app.getString(R.string.toast_restore_undo_failed)
            is RestoreSteps.Outcome.Done -> app.getString(
                when (source) {
                    BackupFiles.Source.BACKUP -> R.string.toast_restore_ok
                    BackupFiles.Source.UNDO -> R.string.toast_undo_ok
                },
                BackupFiles.timeOf(outcome.undoName),
            )
        }

        fun cleanupMessage(app: VersoApp, outcome: CleanupOutcome): String = when (outcome) {
            CleanupOutcome.Refused -> app.getString(R.string.toast_cleanup_not_run)
            CleanupOutcome.Incomplete -> app.getString(R.string.toast_cleanup_incomplete)
            CleanupOutcome.NothingToDo -> app.getString(R.string.toast_cleanup_none)
            CleanupOutcome.UndoFailed -> app.getString(R.string.toast_restore_undo_failed)
            is CleanupOutcome.Done -> app.getString(R.string.toast_cleanup_ok, BackupFiles.timeOf(outcome.undoName))
        }
    }
}
