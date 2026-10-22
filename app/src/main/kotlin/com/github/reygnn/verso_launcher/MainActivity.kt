package com.github.reygnn.verso_launcher

import android.app.Activity
import android.app.AlertDialog
import android.app.WallpaperColors
import android.app.WallpaperManager
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
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Log
import android.view.KeyEvent
import android.view.WindowInsetsController
import android.widget.Toast
import android.window.OnBackInvokedDispatcher
import java.io.File
import java.io.FileDescriptor
import java.util.concurrent.RejectedExecutionException

/**
 * The launcher's single window: the home page (recto) in front, the drawer with Sigil's command
 * line (verso) behind it ([PageTurn] turns between them), and the side effects of taps, edits and
 * the "~" commands. The decisions themselves are pure, in [LauncherLogic] and [LauncherState].
 */
class MainActivity : Activity() {

    private var palette = HomeStyle.palette(lightWallpaper = false)
    // Whether the next onResume must re-enumerate the apps.
    private var appsDirty = true
    private var resumed = false
    // Set by a launch that started: the pause it brings turns the page home (see onPause).
    private var launchedApp = false
    // Between onStart and onStop: the launcher can be seen (see cleanup).
    private var started = false
    private val gate = CommandGate()

    private val app get() = VersoApp.from(this)

    // The process's IO thread, not this instance's: a recreated activity queues behind whatever
    // its predecessor left in flight instead of racing it (hard rule 3).
    private val ioExecutor get() = app.io

    // ---- the data ---------------------------------------------------------------
    private val state: LauncherState by lazy {
        LauncherState(
            io = ::submitIo,
            main = { task -> onUi { task.run() } },
            loadConfig = { store.load() },
            setAsideUnreadable = {
                store.setAsideUnreadable { text -> backupStore.write(BackupFiles.UNREADABLE_BASE, text) != null }
            },
            enumerateApps = { apps.enumerate() },
            serialize = ConfigJson::serialize,
            writeConfig = { payload -> store.write(payload) },
            onSaveFailed = { toast(getString(R.string.toast_save_failed)) },
            onRefreshed = { configReplaced, listChanged ->
                // An open rename dialog holds the OLD config and would write stale fields over
                // the one that just arrived.
                if (configReplaced) dismissDialogs()
                if (state.takeStartedOver()) toast(getString(R.string.toast_config_reset), Toast.LENGTH_LONG)
                if (listChanged) appDrawer.refilter()
            },
            isFreshInstall = { store.isFresh() },
            startedOverFlag = app.startedOver,
            defaultFavoriteCandidates = { apps.defaultFavoriteCandidates() },
            seedPendingFlag = app.seedPending,
            initialRecents = app.recentKeys,
            initialRecentsLoaded = app.recentsLoaded,
            onRecentsChanged = { keys, loaded ->
                app.recentKeys = keys
                app.recentsLoaded = loaded
            },
            loadRecents = { RecentsLog.read(filesDir, LauncherState.RECENTS_KEPT) },
            writeRecents = { keys -> RecentsLog.write(filesDir, keys) },
            loadUsage = { UsageLog.read(filesDir) },
            writeUsage = { log, now -> UsageLog.write(filesDir, log, now) },
            initialUsage = app.usage,
            initialUsageLoaded = app.usageLoaded,
            onUsageChanged = { log, loaded ->
                app.usage = log
                app.usageLoaded = loaded
            },
        )
    }

    // ---- the two pages ------------------------------------------------------
    private lateinit var homePage: HomePage
    private lateinit var appDrawer: AppDrawer
    private lateinit var pageTurn: PageTurn

    // Tracked so they can be dismissed on teardown: an undismissed dialog leaks its window.
    private var renameDialog: AlertDialog? = null
    private var commandDialog: AlertDialog? = null
    // Bumped whenever the page turns away: a "~cleanup" check's result must still match it to be shown.
    private var cleanupTicket = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Under enforced edge-to-edge the system does NOT lift content for the IME; we pad for it
        // ourselves. No scrim behind 3-button navigation: the wallpaper runs to the bottom edge.
        window.isNavigationBarContrastEnforced = false

        appDrawer = AppDrawer(
            activity = this,
            state = state,
            palette = palette,
            onTap = ::onDrawerTap,
            onLongPress = { entry -> if (editPermit() != null) promptRename(entry) },
            onEnter = ::onDrawerEnter,
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
        // before the full enumeration does.
        submitIo {
            val cached = HomeCache.read(filesDir)
            onUi {
                if (cached.isNotEmpty() && !state.appsLoaded) {
                    cachedFavorites = cached.map { it.key to it.label }
                    homePage.render(cached, loaded = true)
                }
            }
        }

        // Asking for the wallpaper colours is a binder call that may extract them from the bitmap
        // right then, so it runs on the IO thread; until then, the process's last palette.
        applyPalette(app.palette ?: HomeStyle.palette(lightWallpaper = false))
        val asked = ++paletteGeneration
        submitIo {
            val light = wallpaperIsLight()
            onUi {
                // A listener call since the question is newer than this answer.
                val answer = HomeStyle.palette(light)
                if (asked == paletteGeneration && answer != palette) applyPalette(answer)
            }
        }
        WallpaperManager.getInstance(this).addOnColorsChangedListener(wallpaperColors, Handler(Looper.getMainLooper()))
    }

    override fun onStart() {
        super.onStart()
        started = true
        homePage.startBattery()
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
        unwatchApps()
        WallpaperManager.getInstance(this).removeOnColorsChangedListener(wallpaperColors)
        super.onDestroy()
    }

    /** Run [block] on the main thread for THIS instance — or not at all once it is destroyed. */
    private fun onUi(block: () -> Unit) = runOnUiThread { if (!isDestroyed) block() }

    /** For the executor and the SAF read thread: an escape there kills the process on every start. */
    private fun guarded(what: String, task: Runnable) = Guard.wrap(what, ::logWarning, task)

    /** A refused task is reported as false, never thrown (the executor is never shut down, but a HOME app must not crash). */
    private fun submitIo(task: Runnable): Boolean {
        if (ioExecutor.isShutdown) return false
        return try {
            ioExecutor.execute(guarded("IO task", task))
            true
        } catch (e: RejectedExecutionException) {
            Log.w("Verso", "IO task rejected (executor shutting down)", e)
            false
        }
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

    private fun logWarning(msg: String, e: Throwable?) {
        if (e != null) Log.w("Verso", msg, e) else Log.w("Verso", msg)
    }

    private val store by lazy {
        ConfigStore(
            dir = filesDir,
            log = ::logWarning,
            syncDir = ::fsyncDir,
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
        // Refused while one runs — and the typed command stays in the prompt, to send again.
        if (gate.busy) {
            toast(getString(R.string.toast_busy))
            return
        }
        if (command != Command.RESTART && !editable()) return
        // Cleared BEFORE running: setText fires the TextWatcher synchronously, so the mode is
        // back to NORMAL before the command runs.
        appDrawer.prompt.setText("")
        gate.start { t ->
            when (command) {
                Command.RESTART -> {
                    // A hung provider must not block ~restart; the bump voids a hand-back already queued.
                    if (safReading) {
                        restorePick++
                        safReading = false
                        toast(getString(R.string.toast_restore_saf_dropped), Toast.LENGTH_LONG)
                    }
                    recreate()
                }
                Command.BACKUP -> backup(t)
                Command.RESTORE -> restoreConfig(t)
                // Only the picker: it is another activity, so no run spans it (onActivityResult starts one).
                Command.RESTORE_SAF -> pickRestoreFile()
                Command.CLEANUP -> cleanup(t)
            }
        }
    }

    /** One IO step of the run [t] (see [CommandGate.io]): [then] gets null if it failed. */
    private fun <T : Any> commandIo(t: CommandGate.Ticket, work: () -> T?, then: (T?) -> Unit) =
        gate.io(t, ::submitIo, { block -> onUi(block) }, work, { e -> Log.w("Verso", "command step failed", e) }, then)

    /** "~backup": export the config in memory to Download/Verso (hard rule 6). */
    private fun backup(t: CommandGate.Ticket) {
        val payload = ConfigJson.serialize(state.loadedSnapshot() ?: return)
        commandIo(t, work = { backupStore.write(BackupFiles.BACKUP_BASE, payload) }) { name ->
            if (name != null) toast(getString(R.string.toast_backup_ok, name), Toast.LENGTH_LONG)
            else toast(getString(R.string.toast_backup_failed))
        }
    }

    /** "~cleanup": check on the IO thread, then offer what is confirmed gone (hard rule 14). */
    private fun cleanup(t: CommandGate.Ticket) {
        val ticket = cleanupTicket
        val snapshot = state.config.snapshot()
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
        val accepted = gate.start { t ->
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
            latest to text?.let { ConfigJson.parseForeign(it) }
        }) { read ->
            val (latest, restored) = read ?: (null to null)
            when {
                latest is BackupStore.Latest.Failed ->
                    toast(getString(R.string.toast_restore_list_failed), Toast.LENGTH_LONG)
                latest is BackupStore.Latest.None ->
                    toast(getString(R.string.toast_restore_none), Toast.LENGTH_LONG)
                restored == null -> toast(getString(R.string.toast_restore_failed), Toast.LENGTH_LONG)
                else -> adoptRestored(t, restored)
            }
        }
    }

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

    /** Counts "~restore-saf" picks, so only the newest one's read is adopted. */
    private var restorePick = 0
    // A picked file is being read: no run yet; ~restart drops it.
    private var safReading = false

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_RESTORE) return
        val uri = data?.data
        // Backing out of the picker is not an error — say nothing.
        if (resultCode != RESULT_OK || uri == null) return
        // Its own thread, not the IO thread (hard rule 3): a provider can block the read
        // indefinitely. Newest pick wins — a stuck read may finish after a later one.
        val pick = ++restorePick
        safReading = true
        Thread(guarded("restore read") {
            val restored = backupStore.readText(uri)?.let { ConfigJson.parseForeign(it) }
            onUi {
                if (pick != restorePick) return@onUi
                safReading = false
                if (restored == null) toast(getString(R.string.toast_restore_failed), Toast.LENGTH_LONG)
                // The adoption is a run of its own; with another one running, the pick is dropped.
                else if (!gate.start { t -> adoptRestored(t, restored) }) toast(getString(R.string.toast_busy))
            }
        }, "verso-restore-read").start()
    }

    /**
     * Adopt a config parsed with parseForeign (never the lenient parse, which turns any JSON
     * object into an empty config). No undo point, no restore (hard rule 7). The picker's answer
     * can reach a fresh process whose first load has not landed — then the user is told to retry.
     */
    private fun adoptRestored(t: CommandGate.Ticket, restored: VersoConfig) {
        // So a "my favorites are gone" report can be settled from the log.
        Log.i(
            "Verso",
            "restore: ${restored.favorites.size} favorites, ${restored.hidden.size} hidden, " +
                "${restored.names.size} names, ${restored.tags.size} tagged",
        )
        val outgoing = state.loadedSnapshot() ?: run {
            toast(getString(R.string.toast_not_loaded))
            return
        }
        commandIo(t, work = {
            RestoreSteps.run(
                current = outgoing,
                restored = restored,
                writeUndo = { payload -> backupStore.write(BackupFiles.PRE_RESTORE_BASE, payload, sweep = false) },
                writeConfig = { payload -> store.write(payload) },
                sweepUndo = { backupStore.sweep(BackupFiles.PRE_RESTORE_BASE) },
            )
        }) { outcome ->
            when (outcome) {
                RestoreSteps.Outcome.Unchanged -> toast(getString(R.string.toast_restore_same), Toast.LENGTH_LONG)
                RestoreSteps.Outcome.WriteFailed -> toast(getString(R.string.toast_restore_write_failed), Toast.LENGTH_LONG)
                null, RestoreSteps.Outcome.UndoFailed -> toast(getString(R.string.toast_restore_undo_failed), Toast.LENGTH_LONG)
                is RestoreSteps.Outcome.Done -> if (state.adopt(restored)) {
                    toast(getString(R.string.toast_restore_ok, BackupFiles.timeOf(outcome.undoName)), Toast.LENGTH_LONG)
                }
            }
        }
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

    /**
     * fsync a directory so a preceding rename() survives power loss, not merely is visible.
     * Best-effort: a failure only weakens durability, so it is logged, not thrown.
     */
    private fun fsyncDir(dir: File) {
        var fd: FileDescriptor? = null
        try {
            fd = Os.open(dir.path, OsConstants.O_RDONLY, 0)
            Os.fsync(fd)
        } catch (e: ErrnoException) {
            Log.w("Verso", "dir fsync failed (non-fatal): ${dir.path}", e)
        } finally {
            if (fd != null) {
                try {
                    Os.close(fd)
                } catch (e: ErrnoException) {
                    // Nothing actionable at close time; the publish already happened.
                }
            }
        }
    }

    /** "%": saved at once like every edit; the prompt clears onto the drawer in its new order. */
    private fun toggleOrder() {
        val permit = editPermit() ?: return
        val byUsage = state.toggleOrder(permit) ?: return
        toast(getString(if (byUsage) R.string.toast_order_usage else R.string.toast_order_name))
        appDrawer.setPrompt("")
    }

    /** Internal for MainActivityFlowsTest. */
    internal fun onDrawerTap(action: TapAction<AppEntry>) {
        when (action) {
            is TapAction.Launch -> launch(action.entry)
            is TapAction.ToggleHidden -> editPermit()?.let {
                state.toggleHidden(it, action.entry.key)
                afterToggle()
            }
            is TapAction.ToggleFavorite -> editPermit()?.let {
                state.toggleFavorite(it, action.entry.key)
                afterToggle()
            }
            is TapAction.ReorderPick -> reorderTap(action.entry.key)
            is TapAction.ToggleTag -> toggleTagOn(action.entry.key)
            is TapAction.Run -> runCommand(action.command)
            is TapAction.SetPrompt -> appDrawer.setPrompt(action.text)
            TapAction.ToggleOrder -> toggleOrder()
            TapAction.None -> {}  // stale position, or a row this mode ignores
        }
    }

    private fun onDrawerEnter(action: EnterAction<AppEntry>) {
        when (action) {
            is EnterAction.Run -> runCommand(action.command)
            is EnterAction.LaunchApp -> launch(action.entry)
            is EnterAction.SetPrompt -> appDrawer.setPrompt(action.text)
            EnterAction.ToggleOrder -> toggleOrder()
            EnterAction.DismissKeyboard -> appDrawer.hideKeyboard()
            EnterAction.None -> {}
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

    /** "!!": tap picks a row up, the held row again cancels, any other row drops it there. */
    private fun reorderTap(key: String) {
        val picked = appDrawer.reorderPick
        when {
            picked == null -> { appDrawer.reorderPick = key; appDrawer.notifyChanged() }
            picked == key  -> { appDrawer.reorderPick = null; appDrawer.notifyChanged() }
            else -> editPermit()?.let { moveFavorite(it, picked, key) }
        }
    }

    private fun moveFavorite(permit: LauncherState.EditPermit, pickedKey: String, targetKey: String) {
        appDrawer.reorderPick = null
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
        try {
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

    /** Android's own judgement (HINT_SUPPORTS_DARK_TEXT); unknown counts as a dark wallpaper. */
    private fun wallpaperIsLight(): Boolean = try {
        val colors = WallpaperManager.getInstance(this).getWallpaperColors(WallpaperManager.FLAG_SYSTEM)
        colors.wantsDarkText()
    } catch (e: RuntimeException) {
        Log.w("Verso", "wallpaper colours unavailable", e)
        false
    }

    private fun WallpaperColors?.wantsDarkText(): Boolean =
        this != null && (colorHints and WallpaperColors.HINT_SUPPORTS_DARK_TEXT) != 0

    private val wallpaperColors = WallpaperManager.OnColorsChangedListener { colors, which ->
        if (which and WallpaperManager.FLAG_SYSTEM == 0) return@OnColorsChangedListener
        val light = colors.wantsDarkText()
        ++paletteGeneration
        // Live and time-of-day wallpapers report colours often; repaint only on a real change.
        val p = HomeStyle.palette(light)
        if (p != palette) applyPalette(p)
    }

    // Bumped by every palette decision, so the IO thread's first answer cannot overwrite a
    // newer one from the listener.
    private var paletteGeneration = 0

    private fun applyPalette(p: Palette) {
        palette = p
        app.palette = p
        homePage.applyTextColor(p.homeText)
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
        try {
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
        const val REQ_RESTORE = 1
        const val DIALOG_TEXT = 0xFFD4D4D4.toInt()
        /** How long package changes in front are collected into one refresh. */
        const val CHANGE_SETTLE_MS = 500L
        /** How long a paused profile's "ready" toast stays expected after the tap. */
        const val PROFILE_WAIT_MS = 2 * 60 * 1000L
    }
}
