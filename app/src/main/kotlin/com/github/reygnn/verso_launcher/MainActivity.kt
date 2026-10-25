package com.github.reygnn.verso_launcher

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.LauncherApps
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.UserHandle
import android.os.UserManager
import android.provider.DocumentsContract
import android.provider.Telephony
import android.util.Log
import android.view.KeyEvent
import android.view.WindowInsetsController
import android.widget.Toast
import android.window.OnBackInvokedDispatcher
import com.github.reygnn.guru.Guru
import java.util.concurrent.RejectedExecutionException

/**
 * The launcher's single window: the home page (recto) in front, the drawer with Sigil's command
 * line (verso) behind it ([PageTurn] turns between them), and the side effects of taps, edits and
 * the "~" commands. The decisions themselves are pure, in [LauncherLogic] and [LauncherState].
 */
class MainActivity : Activity() {

    // Whether the next onResume must re-enumerate the apps.
    private var appsDirty = true
    private var resumed = false
    // Set by a launch that started: the pause it brings turns the page home (see onPause).
    private var launchedApp = false
    // Between onStart and onStop: the launcher can be seen (see cleanup).
    private var started = false

    private val app get() = VersoApp.from(this)

    // The process's gate, not this instance's: a recreation must not find a fresh, idle gate
    // while its predecessor's run still has IO steps queued (hard rule 15).
    private val gate get() = app.gate

    // Who started a run, for CommandGate.abandon. A token, not this activity, so the process's gate
    // holds no instance. (A hanging step's own task still does, as before: its work and hand-back.)
    private val runOwner = Any()

    // The process's IO thread, not this instance's: a recreated activity queues behind whatever
    // its predecessor left in flight instead of racing it (hard rule 3).
    private val ioExecutor get() = app.io

    // ---- the data ---------------------------------------------------------------
    private val state: LauncherState by lazy {
        LauncherState(
            io = ::submitIo,
            main = { task -> onUi { task.run() } },
            configFile = ConfigFile(
                load = { store.load() },
                setAsideUnreadable = {
                    store.setAsideUnreadable { text -> backupStore.write(BackupFiles.UNREADABLE_BASE, text) != null }
                },
                isFresh = { store.isFresh() },
                serialize = ConfigJson::serialize,
                write = { payload -> store.write(payload) },
            ),
            enumerateApps = { apps.enumerate() },
            defaultFavoriteCandidates = { apps.defaultFavoriteCandidates() },
            recentsFile = RecentsFile(
                read = { RecentsLog.read(filesDir, LauncherState.RECENTS_KEPT, ::logWarning) },
                write = { keys -> RecentsLog.write(filesDir, keys) },
            ),
            usageFile = UsageFile(
                read = { UsageLog.read(filesDir, ::logWarning) },
                write = { log, at -> UsageLog.write(filesDir, log, at) },
            ),
            process = app.process,
            idle = { gate.idle() },
            now = System::currentTimeMillis,
            onSaveFailed = { toast(getString(R.string.toast_save_failed)) },
            onRefreshed = { configReplaced, listChanged ->
                // An open rename dialog holds the OLD config and would write stale fields over
                // the one that just arrived.
                if (configReplaced) dismissDialogs()
                if (state.takeStartedOver()) toast(getString(R.string.toast_config_reset), Toast.LENGTH_LONG)
                // A recreated instance's first load queues behind its predecessor's last step.
                showMessages()
                // A pick read before the first load waited for it (adoptPendingPick).
                adoptPendingPick()
                if (listChanged) appDrawer.refilter()
            },
        )
    }

    // ---- the two pages ------------------------------------------------------
    private lateinit var homePage: HomePage
    private lateinit var appDrawer: AppDrawer
    private lateinit var pageTurn: PageTurn
    private lateinit var wallpaper: WallpaperPalette
    private var unwatchWallpaper: () -> Unit = {}

    // Tracked so they can be dismissed on teardown: an undismissed dialog leaks its window.
    private var renameDialog: AlertDialog? = null
    private var commandDialog: AlertDialog? = null
    // Bumped whenever the page turns away: a "~cleanup" check's result must still match it to be shown.
    private var cleanupTicket = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The process hands a "~restore-saf" pick to whichever instance is alive (VersoApp.pick).
        app.instance = this

        // Under enforced edge-to-edge the system does NOT lift content for the IME; we pad for it
        // ourselves. No scrim behind 3-button navigation: the wallpaper runs to the bottom edge.
        window.isNavigationBarContrastEnforced = false

        // Asked on the IO thread below (ask); until then, the process's last palette.
        wallpaper = WallpaperPalette(
            initial = app.palette ?: HomeStyle.palette(lightWallpaper = false),
            io = { submitIo(it) },
            main = ::onUi,
            isLight = { WallpaperSystem.isLight(this, ::logWarning) },
            onChange = ::applyPalette,
        )

        appDrawer = AppDrawer(
            activity = this,
            state = state,
            palette = wallpaper.current,
            onAction = ::onDrawerAction,
            onLongPress = { entry -> if (editPermit() != null) promptRename(entry) },
            onFiltered = ::renderHome,
            // Called only by a filter, never while AppDrawer is built: pageTurn is set by then.
            onModeChanged = { mode -> pageTurn.modeChanged(mode) },
        )

        homePage = HomePage(
            activity = this,
            onLaunch = ::launch,
            onEdit = { if (editPermit() != null) promptRename(it) },
            onOpenDrawer = { pageTurn.open() },
        )

        pageTurn = PageTurn(
            activity = this,
            homePage = homePage,
            appDrawer = appDrawer,
            byUsage = { state.config.byUsage },
            dialogOpen = { renameDialog != null || commandDialog != null },
            onOpen = ::dismissDialogs,
            onClose = ::turnAway,
        )
        setContentView(pageTurn.root)
        onBackInvokedDispatcher.registerOnBackInvokedCallback(
            OnBackInvokedDispatcher.PRIORITY_DEFAULT,
            pageTurn.backCallback,
        )

        watchApps()

        // Queued on the IO thread ahead of the first load, so the cached favorites land long
        // before the full enumeration does — behind Guru's save after a crash (VersoApp), at
        // worst ~0.7 s later (8 ANR reports, release build on the A17).
        submitIo {
            val cached = HomeCache.read(filesDir, ::logWarning)
            onUi {
                if (cached.isNotEmpty() && !state.appsLoaded) {
                    cachedFavorites = cached.map { it.key to it.label }
                    homePage.render(cached, loaded = true)
                }
            }
        }

        applyPalette(wallpaper.current)
        wallpaper.ask()
        unwatchWallpaper = WallpaperSystem.watch(this, wallpaper)

        // Crash reports from an earlier run: Guru's alert, once the window is up. Queued on
        // the IO thread behind Guru's look at the exit records and its save to Download/Guru.
        // Not through onUi: Guru wants every block run (its README) — a destroyed activity is
        // Guru's own check, which keeps the alert for the next call; onUi would drop it.
        Guru.showPending(this) { show -> runOnUiThread(show) }
    }

    override fun onStart() {
        super.onStart()
        started = true
        homePage.startBattery()
        showMessages()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        // Not on every return home: a full enumeration of every profile would compete with the
        // return animation. needsRefresh covers a load that failed or came back incomplete — no
        // package event retries it.
        if (appsDirty || state.needsRefresh) {
            appsDirty = false
            state.refresh()
        }
        pageTurn.resumed()
    }

    /** The page stays as it is — a dialog or the shade on top pauses without leaving; [onStop] turns it home. */
    override fun onPause() {
        resumed = false
        // A dialog-like or translucent app never stops this activity: without this the drawer
        // (or the recents) would stay open under it, and re-sort in view on the next refresh.
        if (launchedApp) {
            launchedApp = false
            pageTurn.close(animate = false)
        }
        // A change still waiting to be collected is picked up by the next resume instead.
        // (View.removeCallbacks always answers true, so the flag says whether one waits.)
        if (refreshSoonPending) {
            pageTurn.root.removeCallbacks(refreshSoon)
            refreshSoonPending = false
            appsDirty = true
        }
        super.onPause()
    }

    /**
     * In front: a burst of changes is collected for [CHANGE_SETTLE_MS] into one refresh. Behind
     * another app: only marked dirty, so a store updating 20 apps costs one reload on resume.
     */
    private fun appsMaybeChanged() {
        if (resumed) {
            pageTurn.root.removeCallbacks(refreshSoon)
            pageTurn.root.postDelayed(refreshSoon, CHANGE_SETTLE_MS)
            refreshSoonPending = true
        } else {
            appsDirty = true
        }
    }

    private var refreshSoonPending = false
    private val refreshSoon = Runnable {
        refreshSoonPending = false
        if (resumed) {
            state.refresh()
        } else {
            appsDirty = true
        }
    }

    /** Leaving the launcher turns the page home instantly, so a return lands on home, not a half-typed search. */
    override fun onStop() {
        started = false
        // Also from the home page, where closing the drawer does nothing.
        turnAway()
        pageTurn.close(animate = false)
        homePage.stopBattery()
        super.onStop()
    }

    /** The user left the page they acted on: a dialog opened there and a pending "~cleanup" offer are void. */
    private fun turnAway() {
        cleanupTicket++
        dismissDialogs()
    }

    private fun dismissDialogs() {
        renameDialog?.dismiss()
        commandDialog?.dismiss()
    }

    override fun onDestroy() {
        dismissDialogs()
        renameDialog = null
        commandDialog = null
        // No executor shutdown: work this instance queued (a save above all) must still run;
        // its hand-back is dropped by onUi.
        abandonRunBehindIo()
        if (app.instance === this) app.instance = null
        unwatchApps()
        unwatchWallpaper()
        super.onDestroy()
    }

    /**
     * A run this instance started can no longer end by itself: from here on onUi drops its
     * hand-backs, and with them every release. It is given up behind the IO steps it queued (one
     * FIFO thread), so the next run's steps never interleave with them. Captures no activity.
     */
    private fun abandonRunBehindIo() {
        val process = app
        val owner = runOwner
        if (!submitIo { process.onMain { process.gate.abandon(owner) } }) process.gate.abandon(owner)
    }

    /** Run [block] on the main thread for THIS instance — or not at all once it is destroyed. */
    private fun onUi(block: () -> Unit) = runOnUiThread { if (!isDestroyed) block() }

    /** For the executor and the SAF read thread: an escape there kills the process on every start. */
    private fun guarded(what: String, task: Runnable) = Guard.wrap(what, ::logWarning, task)

    /** A refused task is reported as false, never thrown (the executor is never shut down, but a HOME app must not crash). */
    private fun submitIo(task: Runnable): Boolean =
        try {
            ioExecutor.execute(guarded("IO task", task))
            true
        } catch (e: RejectedExecutionException) {
            Log.w("Verso", "IO task rejected (executor shut down)", e)
            false
        }

    /** A hardware keyboard on home: typing (or Search) opens the drawer with that character in the prompt. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (!pageTurn.isOpen) {
            val c = printable(event)
            if (c != null || keyCode == KeyEvent.KEYCODE_SEARCH) {
                pageTurn.open()
                if (c != null) appDrawer.typeIntoPrompt(c)
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    /** HOME pressed while already home: turn back to the home page, scrolled to the top. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)  // keep getIntent() in sync with the latest launch intent
        // A dialog confirmed after HOME would apply an edit the user has visually left behind.
        turnAway()
        pageTurn.close()
        homePage.scrollToTop()
    }

    // ---- config -------------------------------------------------------------

    private val store by lazy {
        ConfigStore(
            dir = filesDir,
            log = ::logWarning,
            syncDir = { dir -> fsyncDir(dir, ::logWarning) },
        )
    }

    private val backupStore by lazy {
        BackupStore(
            resolver = contentResolver,
            subDir = BackupFiles.DIR,
            log = ::logWarning,
            owner = packageName,
        )
    }

    /** Run a "~" command, one at a time (hard rule 15); each reports its real result with a toast. */
    private fun runCommand(command: Command) {
        // Not a run: a deliberate crash, for Guru and the crash-only paths, at any time. The
        // constant compiles it out of a public release, where nothing parses to CRASH anyway.
        if (command == Command.CRASH) {
            if (DailyDriver.ENABLED) error("~crash: deliberate crash (daily-driver build)")
            return
        }
        // Refused while one runs — and the typed command stays in the prompt, to send again.
        if (gate.busy) {
            toast(getString(R.string.toast_busy))
            return
        }
        if (heldBackByPick(command)) return
        if (command != Command.RESTART && !editable()) return
        // Cleared BEFORE running: clearing fires the TextWatcher synchronously, so the mode is
        // back to NORMAL before the command runs.
        appDrawer.clearPrompt()
        gate.start(runOwner) { t ->
            when (command) {
                Command.RESTART -> {
                    // A hung provider must not block ~restart: the pick is dropped, read or still
                    // being read, and a read that lands later finds it gone.
                    if (app.pick.drop()) toast(getString(R.string.toast_restore_saf_dropped), Toast.LENGTH_LONG)
                    recreate()
                }
                Command.BACKUP -> backup(t)
                Command.RESTORE -> restoreConfig(t)
                // Only the picker: it is another activity, so no run spans it (the adoption is one).
                Command.RESTORE_SAF -> pickRestoreFile()
                Command.CLEANUP -> cleanup(t)
                Command.CRASH -> Unit // returned above
            }
        }
    }

    /**
     * A restore while a pick is read or waits to be adopted (PendingPick) is refused, with a toast:
     * the automatic adoption behind it would sweep its undo point. The prompt stays, to send again.
     */
    private fun heldBackByPick(command: Command): Boolean {
        val restore = command == Command.RESTORE || command == Command.RESTORE_SAF
        if (!restore || !app.pick.pending) return false
        toast(getString(R.string.toast_restore_pending), Toast.LENGTH_LONG)
        return true
    }

    /** One IO step of the run [t] (see [CommandGate.io]): [then] gets null if it failed. */
    private fun <T : Any> commandIo(t: CommandGate.Ticket, work: () -> T?, then: (T?) -> Unit) =
        gate.io(t, ::submitIo, { block -> onUi(block) }, work, { e -> Log.w("Verso", "command step failed", e) }, then)

    /** "~backup": export the config in memory to Download/Verso (hard rule 6). */
    private fun backup(t: CommandGate.Ticket) {
        val payload = ConfigJson.serialize(state.loadedSnapshot() ?: return)
        commandIo(t, work = {
            val name = backupStore.write(BackupFiles.BACKUP_BASE, payload)
            keepMessage(
                if (name != null) getString(R.string.toast_backup_ok, name) else getString(R.string.toast_backup_failed)
            )
        }) { kept ->
            // Nothing kept: the step threw.
            if (kept == null) report(getString(R.string.toast_backup_failed)) else showMessages()
        }
    }

    /** "~cleanup": check on the IO thread, then offer what is confirmed gone (hard rule 14). */
    private fun cleanup(t: CommandGate.Ticket) {
        val ticket = cleanupTicket
        val snapshot = state.config
        commandIo(t, work = { apps.safeCleanupCheck(snapshot) }) { plan ->
            // Left meanwhile: no dialog over a page that moved on, no toast over another app.
            if (started && ticket == cleanupTicket) when (plan) {
                null -> toast(getString(R.string.toast_cleanup_not_run))
                CleanupPlan.Incomplete -> toast(getString(R.string.toast_cleanup_incomplete))
                CleanupPlan.NothingToDo -> toast(getString(R.string.toast_cleanup_none))
                // The dialog ends this run: no run waits on the user. Its OK starts a new one.
                is CleanupPlan.Offer -> confirmCleanup(plan.orphans)
            }
        }
    }

    private fun confirmCleanup(orphans: Orphans) {
        commandDialog?.dismiss()
        // One line per section that has entries — no "0" lines, and no plural to get wrong.
        val lines = buildList {
            add(getString(R.string.cleanup_apps, orphans.keys.size))
            add("")
            if (orphans.favorites > 0) add(getString(R.string.cleanup_favorites, orphans.favorites))
            if (orphans.hidden > 0) add(getString(R.string.cleanup_hidden, orphans.hidden))
            if (orphans.names > 0) add(getString(R.string.cleanup_names, orphans.names))
            if (orphans.tags > 0) add(getString(R.string.cleanup_tags, orphans.tags))
            add("")
            add(getString(R.string.cleanup_undo_note))
        }
        commandDialog = AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(R.string.cleanup_title)
            .setMessage(lines.joinToString("\n"))
            .setPositiveButton(R.string.cleanup_remove) { _, _ -> runCleanup(orphans.keys) }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
            .apply { setOnDismissListener { d -> if (commandDialog === d) commandDialog = null } }
        commandDialog?.show()
    }

    private fun runCleanup(confirmed: Set<String>) {
        val accepted = gate.start(runOwner) { t ->
            gate.hold(t)
            state.cleanup(
                confirmed = confirmed,
                recheck = apps::safeCleanupCheck,
                writeUndo = { payload -> backupStore.write(BackupFiles.PRE_CLEANUP_BASE, payload) },
            ) { outcome -> gate.finish(t) { cleanupDone(outcome) } }
        }
        if (!accepted) toast(getString(R.string.toast_busy))
    }

    private fun cleanupDone(outcome: CleanupOutcome) {
        if (outcome is CleanupOutcome.Done) appDrawer.refilter()
        // The result stands, but no toast over another app.
        if (!started) return
        when (outcome) {
            CleanupOutcome.Refused -> toast(getString(R.string.toast_cleanup_not_run))
            CleanupOutcome.Superseded -> toast(getString(R.string.toast_cleanup_superseded))
            CleanupOutcome.Incomplete -> toast(getString(R.string.toast_cleanup_incomplete))
            CleanupOutcome.NothingToDo -> toast(getString(R.string.toast_cleanup_none))
            CleanupOutcome.UndoFailed -> toast(getString(R.string.toast_restore_undo_failed))
            is CleanupOutcome.Done ->
                toast(getString(R.string.toast_cleanup_ok, BackupFiles.timeOf(outcome.undoName)), Toast.LENGTH_LONG)
        }
    }

    /** "~restore": read and parse the newest own backup on the IO thread, then adopt it. */
    private fun restoreConfig(t: CommandGate.Ticket) {
        commandIo(t, work = {
            val latest = backupStore.lookupLatest(BackupFiles.BACKUP_BASE)
            val text = (latest as? BackupStore.Latest.Found)?.let { backupStore.readText(it.uri) }
            val restored = text?.let { ConfigJson.parseForeign(it) }
            if (restored == null) keepMessage(readFailure(latest))
            Read(restored)
        }) { read ->
            val restored = read?.restored
            when {
                // Nothing kept: the step threw.
                read == null -> report(getString(R.string.toast_restore_failed), Toast.LENGTH_LONG)
                restored == null -> showMessages()
                else -> adoptRestored(t, restored)
            }
        }
    }

    /** A "~restore" read: null if there was nothing to restore (its message is kept). */
    private class Read(val restored: VersoConfig?)

    private fun readFailure(latest: BackupStore.Latest): String = getString(
        when (latest) {
            is BackupStore.Latest.Failed -> R.string.toast_restore_list_failed
            is BackupStore.Latest.None -> R.string.toast_restore_none
            is BackupStore.Latest.Found -> R.string.toast_restore_failed
        }
    )

    /**
     * "~restore-saf": the way a config moves between devices. Plain "~restore" only sees the app's
     * OWN MediaStore rows, which are invisible after a reinstall; the user's pick is the permission.
     */
    private fun pickRestoreFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            // Not a strict "application/json": a .json arrives as text/plain or octet-stream
            // depending on who wrote it, and a strict filter would grey it out.
            .setType("*/*")
            .putExtra(
                Intent.EXTRA_MIME_TYPES,
                arrayOf("application/json", "text/plain", "application/octet-stream"),
            )
            // Only a hint; DocumentsUI ignores it when the folder does not exist yet.
            .putExtra(
                DocumentsContract.EXTRA_INITIAL_URI,
                DocumentsContract.buildDocumentUri(
                    "com.android.externalstorage.documents",
                    "primary:${Environment.DIRECTORY_DOWNLOADS}/${BackupFiles.DIR}",
                ),
            )
        try {
            startActivityForResult(intent, REQ_RESTORE)
        } catch (e: ActivityNotFoundException) {
            Log.w("Verso", "no document picker on this device", e)
            toast(getString(R.string.toast_restore_no_picker))
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_RESTORE) return
        val uri = data?.data
        // Backing out of the picker is not an error — say nothing.
        if (resultCode != RESULT_OK || uri == null) return
        // From here the pick is the process's (VersoApp.pick), so a recreation or a stop loses it
        // no more. Its own thread, not the IO thread (hard rule 3): a provider can block the read
        // indefinitely. The thread holds nothing of this activity — the application's resolver, a
        // log of its own, a hand-back to the process — so a read that never returns keeps no
        // destroyed instance alive.
        val process = app
        val number = process.pick.begin()
        val reader = BackupStore(
            resolver = applicationContext.contentResolver,
            subDir = BackupFiles.DIR,
            log = ::logWarning,
            owner = packageName,
        )
        Thread(Guard.wrap("restore read", ::logWarning) {
            val restored = reader.readText(uri)?.let { ConfigJson.parseForeign(it) }
            process.onMain { process.pickRead(number, restored) }
        }, "verso-restore-read").start()
    }

    /** The process handed over a pick's read (VersoApp.pickRead): its result if it failed, else the adoption. */
    internal fun pickArrived() {
        showMessages()
        adoptPendingPick()
    }

    /**
     * Adopts a "~restore-saf" pick that is read and waiting, once nothing stands in its way: no run
     * (also none a predecessor left) and a loaded config (hard rule 5). Called when the read lands,
     * when a run ends (the gate's onIdle) and when a load lands — whichever comes last adopts. It
     * waits instead of being refused: while it is pending no restore can start (runCommand), so the
     * run it waits for is a backup or a cleanup, never a restore whose undo point it would sweep.
     */
    internal fun adoptPendingPick() {
        if (isDestroyed || gate.busy || !state.configLoaded) return
        val restored = app.pick.take() ?: return
        gate.start(runOwner) { t -> adoptRestored(t, restored) }
    }

    /**
     * Adopt a config parsed with parseForeign (never the lenient parse, which turns any JSON
     * object into an empty config). No undo point, no restore (hard rule 7). Both callers start it
     * only with the config loaded (runCommand's editable, adoptPendingPick); the check below stays
     * as the last line of hard rule 5.
     */
    private fun adoptRestored(t: CommandGate.Ticket, restored: VersoConfig) {
        // So a "my favorites are gone" report can be settled from the log.
        Log.i(
            "Verso",
            "restore: ${restored.favorites.size} favorites, ${restored.hidden.size} hidden, " +
                "${restored.names.size} names, ${restored.tags.size} tagged",
        )
        val outgoing = state.loadedSnapshot() ?: run {
            refuse(getString(R.string.toast_not_loaded))
            return
        }
        commandIo(t, work = {
            RestoreSteps.run(
                current = outgoing,
                restored = restored,
                writeUndo = { payload -> backupStore.write(BackupFiles.PRE_RESTORE_BASE, payload, sweep = false) },
                writeConfig = state::writeRestored,
                sweepUndo = { backupStore.sweep(BackupFiles.PRE_RESTORE_BASE) },
            ).also { outcome -> keepMessage(restoreMessage(outcome)) }
        }) { outcome ->
            // Nothing kept: the step threw.
            if (outcome == null) {
                report(getString(R.string.toast_restore_undo_failed), Toast.LENGTH_LONG)
                return@commandIo
            }
            // Cannot refuse: loaded was checked above. The message is the write's, so it stands
            // even for an instance that never gets this hand-back.
            if (outcome is RestoreSteps.Outcome.Done) state.adopt(restored)
            showMessages()
        }
    }

    private fun restoreMessage(outcome: RestoreSteps.Outcome): String = when (outcome) {
        RestoreSteps.Outcome.Unchanged -> getString(R.string.toast_restore_same)
        RestoreSteps.Outcome.WriteFailed -> getString(R.string.toast_restore_write_failed)
        RestoreSteps.Outcome.UndoFailed -> getString(R.string.toast_restore_undo_failed)
        is RestoreSteps.Outcome.Done -> getString(R.string.toast_restore_ok, BackupFiles.timeOf(outcome.undoName))
    }

    /** No "~" command but ~restart before the config is loaded (hard rule 5); the toast says why. */
    private fun editable(): Boolean {
        if (state.configLoaded) return true
        toast(getString(R.string.toast_not_loaded))
        return false
    }

    /** Not while a "~" command runs (hard rule 15), nor before the config is loaded (hard rule 5); a toast says which. */
    private fun editPermit(): LauncherState.EditPermit? {
        val idle = gate.idle()
        if (idle == null) {
            toast(getString(R.string.toast_busy))
            return null
        }
        return state.editPermit(idle) ?: run {
            toast(getString(R.string.toast_not_loaded))
            null
        }
    }

    private fun toast(text: String, duration: Int = Toast.LENGTH_SHORT) =
        Toast.makeText(this, text, duration).show()

    /*
     * A "~backup"/restore result is never shown over another app and never lost: it waits in
     * ProcessState.messages until an instance is in front — shown in the step's hand-back, at
     * onStart (it finished after onStop), or at the first load of an instance that replaced the one
     * whose hand-back a recreation dropped (that load queues behind the step on the IO thread).
     * A refusal is not a result: shown while in front, else dropped ([refuse]).
     */

    /** On the IO thread, before the hand-back: queues [text] as a result and returns it. */
    private fun keepMessage(text: String): String = text.also { app.process.messages.add(it) }

    /** A result decided on the main thread: now if in front, else queued for the next start. */
    private fun report(text: String, duration: Int = Toast.LENGTH_SHORT) {
        if (started) toast(text, duration) else app.process.messages.add(text)
    }

    /**
     * A refusal from a step that ran while the user may have left: shown if in front, else dropped —
     * "not loaded" hours later, out of context, would mean nothing. (A pick no longer meets "busy"
     * or "not loaded": it waits for both, adoptPendingPick. This stays for a caller that breaks that.)
     */
    private fun refuse(text: String) {
        if (started) toast(text)
    }

    /** Every queued result, oldest first, once each, while in front. */
    private fun showMessages() {
        if (!started) return
        val queue = app.process.messages
        var text = queue.poll()
        while (text != null) {
            toast(text, Toast.LENGTH_LONG)
            text = queue.poll()
        }
    }

    /** "%": saved at once like every edit; the prompt clears onto the drawer in its new order. */
    private fun toggleOrder() {
        val permit = editPermit() ?: return
        val byUsage = state.toggleOrder(permit) ?: return
        toast(getString(if (byUsage) R.string.toast_order_usage else R.string.toast_order_name))
        appDrawer.setPrompt("")
    }

    /** A tap on a row or an Enter on the prompt. Internal for MainActivityFlowsTest. */
    internal fun onDrawerAction(action: DrawerAction<AppEntry>) {
        when (action) {
            is DrawerAction.Launch -> launch(action.entry)
            is DrawerAction.ToggleHidden -> editPermit()?.let {
                state.toggleHidden(it, action.entry.key)
                afterToggle()
            }
            is DrawerAction.ToggleFavorite -> editPermit()?.let {
                state.toggleFavorite(it, action.entry.key)
                afterToggle()
            }
            is DrawerAction.ReorderPick -> reorderTap(action.entry.key)
            is DrawerAction.ToggleTag -> toggleTagOn(action.entry.key)
            is DrawerAction.Run -> runCommand(action.command)
            is DrawerAction.SetPrompt -> appDrawer.setPrompt(action.text)
            DrawerAction.ToggleOrder -> toggleOrder()
            DrawerAction.DismissKeyboard -> appDrawer.hideKeyboard()
            DrawerAction.None -> {}
        }
    }

    private fun toggleTagOn(key: String) {
        val tag = appDrawer.tagEditTag ?: return
        val permit = editPermit() ?: return
        state.toggleTag(permit, key, tag)
        appDrawer.notifyChanged()
    }

    private fun afterToggle() {
        appDrawer.notifyChanged()
        renderHome()
    }

    /** "!!": the decision is [LauncherLogic.reorderStep]'s; a move takes an edit permit. */
    private fun reorderTap(key: String) {
        when (val step = LauncherLogic.reorderStep(appDrawer.reorderPick, key)) {
            is ReorderStep.PickUp -> appDrawer.pickUp(step.key)
            ReorderStep.PutDown -> appDrawer.dropPick()
            is ReorderStep.Move -> editPermit()?.let { moveFavorite(it, step.picked, step.target) }
        }
    }

    private fun moveFavorite(permit: LauncherState.EditPermit, pickedKey: String, targetKey: String) {
        appDrawer.dropPick()
        state.moveFavorite(permit, pickedKey, targetKey)
        appDrawer.refilter()  // re-render in the (new) order
    }

    /** Internal for MainActivityFlowsTest. */
    internal fun promptRename(entry: AppEntry) {
        // Home can show cached favorites before the config is loaded; the dialog would open
        // on the empty default and its edit could not be saved.
        if (!state.configLoaded) return
        val key = entry.key
        renameDialog?.dismiss()
        val dialog = RenameDialog.show(
            context = this,
            title = entry.label,
            initialName = state.config.names[key] ?: entry.systemLabel,
            initialTags = state.config.tags[key]?.joinToString(", ").orEmpty(),
            allTags = LauncherLogic.allTags(state.config.tags),
            // The dialog is dark on every wallpaper, so its text stays light whatever the palette.
            fgColor = DIALOG_TEXT,
            onAppInfo = { openAppInfo(entry) },
        ) { name, tags ->
            // A run may have started while the dialog was up (a ~restore-saf read ending).
            val permit = editPermit() ?: return@show
            state.rename(permit, key, entry.systemLabel, name, tags)
            appDrawer.refilter()
        }
        renameDialog = dialog
        // Only if it still holds THIS one: the dismiss listener is a posted message, so the old
        // dialog's listener runs after the new one was assigned.
        dialog.setOnDismissListener { if (renameDialog === dialog) renameDialog = null }
    }

    // ---- apps ---------------------------------------------------------------

    private val launcherApps: LauncherApps by lazy { getSystemService(LauncherApps::class.java) }
    private val userManager: UserManager by lazy { getSystemService(UserManager::class.java) }

    private val apps by lazy {
        Apps(
            launcherApps = launcherApps,
            userManager = userManager,
            pm = packageManager,
            ownPackage = packageName,
            badgedLabels = app.badgedLabels,
            // The badge is localized: part of its cache key, read fresh for each enumeration.
            locale = { resources.configuration.locales[0].toLanguageTag() },
            defaultSmsPackage = { Telephony.Sms.getDefaultSmsPackage(this) },
        )
    }

    private val appsChanged = object : LauncherApps.Callback() {
        override fun onPackageAdded(packageName: String, user: UserHandle) = appsMaybeChanged()
        override fun onPackageRemoved(packageName: String, user: UserHandle) = appsMaybeChanged()
        override fun onPackageChanged(packageName: String, user: UserHandle) = appsMaybeChanged()
        override fun onPackagesAvailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) =
            appsMaybeChanged()
        override fun onPackagesUnavailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) =
            appsMaybeChanged()
        override fun onPackagesSuspended(packageNames: Array<out String>, user: UserHandle) = appsMaybeChanged()
        override fun onPackagesUnsuspended(packageNames: Array<out String>, user: UserHandle) = appsMaybeChanged()
    }

    /** The profile a tap on a paused app turned back on, and that app's label, for the "ready" toast. */
    private var waitingForProfile: Pair<UserHandle, String>? = null
    // Only an answer within PROFILE_WAIT_MS gets the toast: work apps turned on hours later must
    // not announce an app nobody just asked for. Not cleared in onStop — the work challenge covers us.
    private var waitingSince = 0L

    private val profilesChanged = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            appsMaybeChanged()
            val waiting = waitingForProfile ?: return
            if (intent.action != Intent.ACTION_PROFILE_AVAILABLE) return
            val user = intent.getParcelableExtra(Intent.EXTRA_USER, UserHandle::class.java)
            if (user != waiting.first) return
            waitingForProfile = null
            if (SystemClock.uptimeMillis() - waitingSince > PROFILE_WAIT_MS) return
            toast(getString(R.string.toast_app_ready, waiting.second), Toast.LENGTH_LONG)
        }
    }

    private fun watchApps() {
        launcherApps.registerCallback(appsChanged, Handler(Looper.getMainLooper()))
        registerReceiver(
            profilesChanged,
            IntentFilter().apply {
                addAction(Intent.ACTION_PROFILE_ADDED)
                addAction(Intent.ACTION_PROFILE_REMOVED)
                addAction(Intent.ACTION_PROFILE_AVAILABLE)
                addAction(Intent.ACTION_PROFILE_UNAVAILABLE)
            },
            RECEIVER_NOT_EXPORTED,
        )
    }

    private fun unwatchApps() {
        launcherApps.unregisterCallback(appsChanged)
        // Registered in onCreate, so this never throws today; if it ever does, say so (hard rule 2).
        try {
            unregisterReceiver(profilesChanged)
        } catch (e: IllegalArgumentException) {
            Log.w("Verso", "profile receiver was not registered", e)
        }
    }

    /** The favorites the cache file holds, as last written or read (see HomeCache). */
    private var cachedFavorites: List<Pair<String, String>>? = null

    private fun renderHome() {
        val favs = state.favorites()
        // Before the config is loaded memory has no favorites: home keeps the cached ones, and
        // the cache is not emptied.
        val ready = state.appsLoaded && state.configLoaded
        homePage.render(favs, ready)
        if (ready) {
            val pairs = favs.map { it.key to it.label }
            if (pairs != cachedFavorites) {
                cachedFavorites = pairs
                submitIo { HomeCache.write(filesDir, favs) }
            }
        }
    }

    private fun launch(entry: AppEntry) {
        val user = apps.userOf(entry)
        val component = ComponentName(entry.packageName, entry.className)
        // any app may refuse the launch: say so, never crash
        @Suppress("TooGenericExceptionCaught") try {
            when {
                // The entry's profile is gone (a work profile removed since the list loaded).
                user == null -> notFound(entry)
                // A paused profile: the first tap only turns it back on; a toast says when the
                // app is ready (profilesChanged), and the user decides whether to start it.
                userManager.isQuietModeEnabled(user) -> {
                    waitingForProfile = user to entry.label
                    waitingSince = SystemClock.uptimeMillis()
                    // Only the default home app may turn a profile on; anyone else gets a SecurityException.
                    try {
                        userManager.requestQuietModeEnabled(false, user)
                    } catch (e: SecurityException) {
                        waitingForProfile = null
                        Log.w("Verso", "quiet mode denied: ${entry.key}", e)
                        toast(getString(R.string.toast_not_default_home), Toast.LENGTH_LONG)
                    }
                }
                // Component gone since the list loaded: startMainActivity would throw a
                // SecurityException and misreport it as "denied".
                !launcherApps.isActivityEnabled(component, user) -> {
                    Log.w("Verso", "launch unavailable: ${entry.key}")
                    notFound(entry)
                }
                else -> {
                    launcherApps.startMainActivity(component, user, null, null)
                    // Only a launch that actually started counts as recent.
                    state.recordLaunch(entry.key)
                    launchedApp = true
                }
            }
        } catch (e: SecurityException) {
            Log.w("Verso", "launch denied: ${entry.key}", e)
            toast(getString(R.string.toast_denied, entry.label))
        } catch (e: RuntimeException) {
            // Dead system_server or similar — don't crash HOME.
            Log.w("Verso", "launch failed: ${entry.key}", e)
            notFound(entry)
        }
    }

    private fun applyPalette(p: Palette) {
        app.palette = p
        homePage.applyPalette(p)
        appDrawer.applyPalette(p)
        val lightBars = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
            WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        window.insetsController?.setSystemBarsAppearance(if (p.darkBarIcons) lightBars else 0, lightBars)
    }

    private fun notFound(entry: AppEntry) =
        toast(getString(R.string.toast_not_found, entry.label))

    /** The system "App info" screen, in the entry's profile; guarded like [launch]. */
    private fun openAppInfo(entry: AppEntry) {
        val user = apps.userOf(entry) ?: return notFound(entry)
        // the settings app may refuse: say so, never crash
        @Suppress("TooGenericExceptionCaught") try {
            launcherApps.startAppDetailsActivity(ComponentName(entry.packageName, entry.className), user, null, null)
        } catch (e: SecurityException) {
            Log.w("Verso", "app info denied: ${entry.key}", e)
            toast(getString(R.string.toast_denied, entry.label))
        } catch (e: RuntimeException) {
            Log.w("Verso", "app info failed: ${entry.key}", e)
            notFound(entry)
        }
    }

    private companion object {
        const val DIALOG_TEXT = 0xFFD4D4D4.toInt()

        /** In the companion, so a reference to it holds no activity (the "~restore-saf" read thread). */
        private fun logWarning(msg: String, e: Throwable?) {
            if (e != null) Log.w("Verso", msg, e) else Log.w("Verso", msg)
        }
        /** How long package changes in front are collected into one refresh. */
        const val CHANGE_SETTLE_MS = 500L
        /** How long a paused profile's "ready" toast stays expected after the tap. */
        const val PROFILE_WAIT_MS = 2 * 60 * 1000L
    }
}

/** The "~restore-saf" picker's request code. Internal for MainActivityFlowsTest, which answers one. */
internal const val REQ_RESTORE = 1
