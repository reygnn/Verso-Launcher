package com.github.reygnn.verso_launcher

import android.app.Activity
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
 * line (verso) behind it ([PageTurn] turns between them), and the side effects of taps, "%" and
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

    // ---- the data ---------------------------------------------------------------
    // The process's state (VersoApp.core), shared with every other instance. Taken once: after a
    // "~restart" reset this instance stays with the old state (whose hand-backs are dropped) until
    // the recreation that follows replaces it.
    private val state: LauncherState by lazy { app.core.state }

    /**
     * The state's hand-back after a refresh or a new config (Core sends it to the live window). An
     * instance made with another state (a reset since) ignores it: the recreation renders anew.
     */
    private fun refreshed(from: LauncherState, listChanged: Boolean) {
        if (from !== state) return
        // A recreated instance's first refresh: the results left while no window was in front.
        app.results.flush()
        if (listChanged) appDrawer.refilter()
    }

    /**
     * This window as the process sees it (VersoApp.instance): an object of its own, so the public
     * Activity exposes none of the internal types, and the process holds it only until onDestroy.
     */
    private val launcherWindow = object : LauncherWindow {
        override val inFront: Boolean get() = started

        override fun refreshed(from: LauncherState, listChanged: Boolean) = this@MainActivity.refreshed(from, listChanged)

        override fun show(text: String, long: Boolean) = toast(text, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT)
    }

    // ---- the two pages ------------------------------------------------------
    private lateinit var homePage: HomePage
    private lateinit var appDrawer: AppDrawer
    private lateinit var pageTurn: PageTurn
    private lateinit var wallpaper: WallpaperPalette
    private var unwatchWallpaper: () -> Unit = {}

    // Set by "~restart" until the recreation replaces this window: the process's state is already the
    // fresh one, this window's is not, so it takes no more commands or "%".
    private var restarting = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The process hands its results and refreshes to whichever instance is alive.
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
            onLongPress = { entry -> launcher.openAppInfo(entry) },
            onFiltered = ::renderHome,
            // Called only by a filter, never while AppDrawer is built: pageTurn is set by then.
            onModeChanged = { mode -> pageTurn.modeChanged(mode) },
        )

        homePage = HomePage(
            activity = this,
            onLaunch = { launcher.launch(it) },
            onAppInfo = { launcher.openAppInfo(it) },
            onOpenDrawer = { pageTurn.open() },
            onOpenRecents = { pageTurn.openRecents() },
        )

        pageTurn = PageTurn(
            activity = this,
            homePage = homePage,
            appDrawer = appDrawer,
            byUsage = { state.config.byUsage },
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

        // First on the IO thread (Guru's save after a crash has a thread of its own, VersoApp), so
        // the cached favorites land long before the full enumeration does.
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
        // Guru's own thread (VersoApp.guruIo) behind its look at the exit records and its save.
        // Not through onUi: Guru wants every block run (its README) — a destroyed activity is
        // Guru's own check, which keeps the alert for the next call; onUi would drop it.
        Guru.showPending(this) { show -> runOnUiThread(show) }
    }

    override fun onStart() {
        super.onStart()
        started = true
        homePage.startBattery()
        app.results.flush()
        // Back from an editor, or after a while: the file may have changed.
        app.commands.readLinked()
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
        pageTurn.close(animate = false)
        homePage.stopBattery()
        super.onStop()
    }

    override fun onDestroy() {
        // No executor shutdown: work this instance queued must still run. A read of the config
        // file it started hands back to the process (Commands).
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
        pageTurn.close()
        homePage.scrollToTop()
    }

    // ---- config -------------------------------------------------------------

    /** Run a "~" command; each reports its real result with a toast. */
    private fun runCommand(command: Command) {
        // A deliberate crash, for Guru and the crash-only paths, at any time. The constant compiles
        // it out of a public release, where nothing parses to CRASH anyway.
        if (command == Command.CRASH) {
            if (DailyDriver.ENABLED) error("~crash: deliberate crash (daily-driver build)")
            return
        }
        // After "~restart" this window holds the old state until the recreation lands: it takes
        // nothing more. Nothing to say — the new window comes at once.
        if (restarting) return
        // Cleared BEFORE running: clearing fires the TextWatcher synchronously, so the mode is
        // back to NORMAL before the command runs.
        appDrawer.clearPrompt()
        when (command) {
            Command.RESTART -> restart()
            // Only the picker here: the answer is the process's (onActivityResult, Commands).
            Command.OPEN -> pickConfigFile()
            Command.EDIT -> editConfigFile()
            Command.CRASH -> Unit // returned above
        }
    }

    /** "~restart": the repair by hand. */
    private fun restart() {
        // A fresh state reads the app's own files again, and the recreation builds every view anew
        // around it; onStart reads the linked file. Until that recreation lands this window still
        // holds the old state, so it takes nothing more.
        restarting = true
        app.core.reset()
        recreate()
    }

    /** "~open": the user's config file, wherever it is — this phone's, a cloud folder's, a copy of it. */
    private fun pickConfigFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            // Not a strict "text/plain": a provider may hand a text file out as octet-stream, and a
            // strict filter would grey it out.
            .setType("*/*")
            .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("text/plain", "application/octet-stream"))
            .addFlags(PERSISTED_GRANT)
            // Only a hint; DocumentsUI ignores it when the folder does not exist yet.
            .putExtra(
                DocumentsContract.EXTRA_INITIAL_URI,
                DocumentsContract.buildDocumentUri(
                    "com.android.externalstorage.documents",
                    "primary:${Environment.DIRECTORY_DOWNLOADS}/$CONFIG_DIR",
                ),
            )
        try {
            startActivityForResult(intent, REQ_OPEN)
        } catch (e: ActivityNotFoundException) {
            Log.w("Verso", "no document picker on this device", e)
            toast(getString(R.string.toast_no_picker))
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        // Backing out of the picker is not an error — say nothing.
        if (resultCode != RESULT_OK || uri == null) return
        // From here the answer is the process's: a recreation or a stop loses it no more, and the
        // thread that reads the document holds nothing of this activity.
        if (requestCode == REQ_OPEN) app.commands.link(uri)
    }

    /**
     * "~edit": the linked file in the user's editor, in a task of its own (not on top of HOME,
     * where a press of Home would close it with the edits); onStart reads it back. An app that
     * only views it, if none edits; the write grant only where Verso holds one itself.
     */
    private fun editConfigFile() {
        val uri = app.core.link ?: return toast(getString(R.string.toast_no_config_file), Toast.LENGTH_LONG)
        val flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
        val tries = listOf(
            Intent(Intent.ACTION_EDIT).setDataAndType(uri, "text/plain").addFlags(flags or Intent.FLAG_GRANT_WRITE_URI_PERMISSION),
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, "text/plain").addFlags(flags),
        )
        var noAccess = false
        for (intent in tries) {
            try {
                startActivity(intent)
                return
            } catch (e: ActivityNotFoundException) {
                Log.w("Verso", "no app takes ${intent.action} for the config file", e)
            } catch (e: SecurityException) {
                // No grant to pass on (a read-only link, or one that is gone): the next is tried.
                Log.w("Verso", "cannot pass on ${intent.action} for the config file", e)
                noAccess = true
            }
        }
        toast(getString(if (noAccess) R.string.toast_edit_no_access else R.string.toast_no_editor), Toast.LENGTH_LONG)
    }

    private fun toast(text: String, duration: Int = Toast.LENGTH_SHORT) =
        Toast.makeText(this, text, duration).show()

    /** "%": kept at once (order.txt); the prompt clears onto the drawer in its new order. */
    private fun toggleOrder() {
        if (restarting) return
        val byUsage = state.toggleOrder()
        toast(getString(if (byUsage) R.string.toast_order_usage else R.string.toast_order_name))
        appDrawer.setPrompt("")
    }

    /** A tap on a row or an Enter on the prompt. Internal for MainActivityFlowsTest. */
    internal fun onDrawerAction(action: DrawerAction<AppEntry>) {
        when (action) {
            is DrawerAction.Launch -> launcher.launch(action.entry)
            is DrawerAction.Run -> runCommand(action.command)
            is DrawerAction.SetPrompt -> appDrawer.setPrompt(action.text)
            DrawerAction.ToggleOrder -> toggleOrder()
            DrawerAction.DismissKeyboard -> appDrawer.hideKeyboard()
            DrawerAction.None -> {}
        }
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
        // Before the first list memory has no apps to show: home keeps the cached favorites, and
        // the cache is not emptied.
        val ready = state.appsLoaded
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
        /** The folder the picker suggests: Download/Verso, where the backups of earlier versions are. */
        const val CONFIG_DIR = "Verso"

        /** The picker's grant, to be kept across restarts (Commands.keepAccess). */
        const val PERSISTED_GRANT = Intent.FLAG_GRANT_READ_URI_PERMISSION or
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION

        /** How long package changes in front are collected into one refresh. */
        const val CHANGE_SETTLE_MS = 500L
    }
}

/** The picker's request code. Internal for MainActivityFlowsTest, which answers it. */
internal const val REQ_OPEN = 1
