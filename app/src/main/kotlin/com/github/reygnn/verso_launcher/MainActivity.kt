package com.github.reygnn.verso_launcher

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.LauncherApps
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.os.UserManager
import android.provider.DocumentsContract
import android.util.Log
import android.view.KeyEvent
import android.view.WindowInsetsController
import android.widget.Toast
import android.window.OnBackInvokedDispatcher
import com.github.reygnn.guru.Guru

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
    // Between onStart and onStop: the launcher can be seen (results wait for it, Results).
    private var started = false

    private val app get() = VersoApp.from(this)

    // The process's gate, not this instance's: a recreation must not find a fresh, idle gate
    // while its predecessor's run still has IO steps queued (hard rule 15).
    private val gate get() = app.gate

    // ---- the data ---------------------------------------------------------------
    // The process's state (VersoApp.core), shared with every other instance. Taken once: after a
    // "~restart" reset this instance stays with the old state (whose hand-backs are dropped) until
    // the recreation that follows replaces it.
    private val state: LauncherState by lazy { app.core.state }

    /**
     * The state's hand-back after a refresh or a restore (Core sends it to the live window). An
     * instance made with another state (a reset since) ignores it: the recreation renders anew.
     */
    private fun refreshed(from: LauncherState, configReplaced: Boolean, listChanged: Boolean) {
        if (from !== state) return
        // An open rename dialog holds the OLD config and would write stale fields over the one
        // that just arrived.
        if (configReplaced) dismissDialogs()
        // A recreated instance's first refresh: the results its predecessor's runs left.
        app.results.flush()
        if (listChanged) appDrawer.refilter()
    }

    /**
     * This window as the process sees it (VersoApp.instance): an object of its own, so the public
     * Activity exposes none of the internal types, and the process holds it only until onDestroy.
     */
    private val launcherWindow = object : LauncherWindow {
        override val inFront: Boolean get() = started

        override fun refreshed(from: LauncherState, configReplaced: Boolean, listChanged: Boolean) =
            this@MainActivity.refreshed(from, configReplaced, listChanged)

        override fun show(text: String, long: Boolean) = toast(text, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT)

        override fun offerRestore(restored: VersoConfig) = this@MainActivity.offerRestore(restored)
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
    // Set by "~restart" until the recreation replaces this window: the process's state is already the
    // fresh one, this window's is not, so it takes no more commands or edits.
    private var restarting = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The process hands a "~restore-saf" pick to whichever instance is alive (VersoApp.pick).
        app.instance = launcherWindow

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
            onLaunch = { launcher.launch(it) },
            onEdit = { if (editPermit() != null) promptRename(it) },
            onOpenDrawer = { pageTurn.open() },
            onOpenRecents = { pageTurn.openRecents() },
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
        // A recreation finds the process's state loaded: draw it now. Its next refresh may bring
        // the same list, which re-renders nothing (listChanged false).
        if (state.appsLoaded) appDrawer.refilter()

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
        app.results.flush()
        // A pick read while away is asked about now.
        app.commands.offerPendingPick()
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
        // After onStop, where Android waits for SharedPreferences' writes too (hard rule 4).
        state.flushSave()
    }

    /** The user left the page they acted on: a dialog opened there is void. */
    private fun turnAway() = dismissDialogs()

    private fun dismissDialogs() {
        renameDialog?.dismiss()
        commandDialog?.dismiss()
    }

    override fun onDestroy() {
        dismissDialogs()
        renameDialog = null
        commandDialog = null
        // No executor shutdown: work this instance queued (a save above all) must still run. Its
        // command steps hand back to the process (Commands), so a run it started still ends.
        if (app.instance === launcherWindow) app.instance = null
        unwatchApps()
        unwatchWallpaper()
        super.onDestroy()
    }

    /** Run [block] on the main thread for THIS instance — or not at all once it is destroyed. */
    private fun onUi(block: () -> Unit) = runOnUiThread { if (!isDestroyed) block() }

    /** The process's IO thread, behind Guard (VersoApp.submitIo): false if refused, never a throw. */
    private fun submitIo(task: Runnable): Boolean = app.submitIo(task)

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
        when (command) {
            // A run of its own, so no command step is in flight when the state is replaced.
            Command.RESTART -> gate.start { restart() }
            Command.BACKUP -> app.commands.backup()
            Command.RESTORE -> app.commands.restore()
            // Only the picker: it is another activity, so no run spans it (the adoption is one).
            Command.RESTORE_SAF -> pickRestoreFile()
            Command.UNDO -> app.commands.undo()
            Command.CRASH -> Unit // returned above
        }
    }

    /** "~restart": the repair by hand. */
    private fun restart() {
        // Already replaced: a second ~restart before the recreation lands would reset again.
        if (restarting) return
        // A hung provider must not block ~restart: the pick is dropped, read or still being read,
        // and a read that lands later finds it gone.
        if (app.pick.drop()) toast(getString(R.string.toast_restore_saf_dropped), Toast.LENGTH_LONG)
        // A fresh state reads verso.json (and an unread recents.txt or usage.txt) again, and the
        // recreation builds every view anew around it. Until that recreation lands this window still
        // holds the old state, so it takes nothing more (editable, editPermit).
        restarting = true
        app.core.reset()
        recreate()
    }

    /**
     * A restore ("~undo" is one) while a pick is read or waits to be adopted (PendingPick) is refused,
     * with a toast: the automatic adoption behind it would replace what it brought. The prompt stays,
     * to send again.
     */
    private fun heldBackByPick(command: Command): Boolean {
        val restore = command == Command.RESTORE || command == Command.RESTORE_SAF || command == Command.UNDO
        if (!restore || !app.pick.pending) return false
        toast(getString(R.string.toast_restore_pending), Toast.LENGTH_LONG)
        return true
    }

    /**
     * A "~restore-saf" file was read (Commands): what it would set. Only OK adopts; Cancel ends the
     * pick quietly, and a dialog the page turned away from or the window left ends it with a word
     * (Commands.answerPick).
     */
    private fun offerRestore(restored: VersoConfig) {
        commandDialog?.dismiss()
        commandDialog = AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(R.string.restore_title)
            .setMessage(CommandDialogText.restore(restored, ::words))
            .setPositiveButton(R.string.restore_apply) { _, _ -> app.commands.answerPick(confirmed = true, byCancel = false) }
            .setNegativeButton(android.R.string.cancel) { _, _ -> app.commands.answerPick(confirmed = false, byCancel = true) }
            .create()
            .apply {
                setOnDismissListener { d ->
                    if (commandDialog === d) commandDialog = null
                    // After OK or Cancel nobody is asked any more: a no-op. Else the dialog went unanswered.
                    app.commands.answerPick(confirmed = false, byCancel = false)
                }
            }
        commandDialog?.show()
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

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_RESTORE) return
        val uri = data?.data
        // Backing out of the picker is not an error — say nothing.
        if (resultCode != RESULT_OK || uri == null) return
        // From here the pick is the process's (VersoApp.pick): a recreation or a stop loses it no
        // more, and the read thread holds nothing of this activity.
        app.commands.readPick(uri)
    }

    /** No "~" command but ~restart before the config is loaded (hard rule 5); the toast says why. */
    private fun editable(): Boolean {
        // ~restart: the window is about to be replaced; nothing to say, the new one comes at once.
        if (restarting) return false
        if (state.configLoaded) return true
        toast(getString(R.string.toast_not_loaded))
        return false
    }

    /** Not while a "~" command runs (hard rule 15), nor before the config is loaded (hard rule 5); a toast says which. */
    private fun editPermit(): LauncherState.EditPermit? {
        if (restarting) return null
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

    private fun words(id: Int, count: Int?): String = if (count == null) getString(id) else getString(id, count)

    private fun toast(text: String, duration: Int = Toast.LENGTH_SHORT) =
        Toast.makeText(this, text, duration).show()

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
            is DrawerAction.Launch -> launcher.launch(action.entry)
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
            onAppInfo = { launcher.openAppInfo(entry) },
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

    private val launcher by lazy {
        AppLauncher(
            context = this,
            system = AppLauncher.System(launcherApps, getSystemService(UserManager::class.java)),
            userOf = { apps.userOf(it) },
            say = { text, long -> toast(text, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT) },
            launched = { entry ->
                state.recordLaunch(entry.key)
                launchedApp = true
            },
        )
    }

    // The process's app list (Core): enumeration, a profile's user.
    private val apps get() = app.core.apps

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

    private val profilesChanged = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            appsMaybeChanged()
            if (intent.action != Intent.ACTION_PROFILE_AVAILABLE) return
            launcher.profileAvailable(intent.getParcelableExtra(Intent.EXTRA_USER, UserHandle::class.java))
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

    private fun applyPalette(p: Palette) {
        app.palette = p
        homePage.applyPalette(p)
        appDrawer.applyPalette(p)
        val lightBars = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
            WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        window.insetsController?.setSystemBarsAppearance(if (p.darkBarIcons) lightBars else 0, lightBars)
    }

    private companion object {
        const val DIALOG_TEXT = 0xFFD4D4D4.toInt()

        /** How long package changes in front are collected into one refresh. */
        const val CHANGE_SETTLE_MS = 500L
    }
}

/** The "~restore-saf" picker's request code. Internal for MainActivityFlowsTest, which answers one. */
internal const val REQ_RESTORE = 1
