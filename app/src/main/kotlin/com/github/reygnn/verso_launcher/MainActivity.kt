package com.github.reygnn.verso_launcher

import android.app.Activity
import android.content.Intent
import android.content.pm.LauncherApps
import android.os.Bundle
import android.os.UserHandle
import android.os.UserManager
import android.view.KeyEvent
import android.view.WindowInsetsController
import android.widget.Toast
import android.window.OnBackInvokedDispatcher

/**
 * The launcher's single window: the home page (recto) in front, the drawer with Sigil's command
 * line (verso) behind it ([PageTurn] turns between them), and the side effects of taps, "%" and
 * the "~" commands. The decisions themselves are pure, in [LauncherLogic] and [LauncherState].
 */
class MainActivity : Activity() {

    // Set by a launch that started: the pause it brings turns the page home (see onPause).
    private var launchedApp = false

    private val app get() = VersoApp.from(this)

    // ---- the data ---------------------------------------------------------------
    // The process's state (VersoApp.core), shared with every other instance. Taken once: after a
    // "~restart" reset this instance stays with the old state (whose hand-backs are dropped) until
    // the recreation that follows replaces it.
    private val state: LauncherState by lazy { app.core.state }

    /**
     * The state's hand-back after a refresh (Core sends it to the live window). An instance made
     * with another state (a reset since) ignores it: the recreation renders anew.
     */
    private fun refreshed(from: LauncherState, listChanged: Boolean) {
        if (from !== state) return
        if (listChanged) appDrawer.refilter()
    }

    /**
     * This window as the process sees it (VersoApp.instance): an object of its own, so the public
     * Activity exposes none of the internal types, and the process holds it only until onDestroy.
     */
    private val launcherWindow = object : LauncherWindow {
        override fun refreshed(from: LauncherState, listChanged: Boolean) = this@MainActivity.refreshed(from, listChanged)
        override fun profileAvailable(user: UserHandle?) = launcher.profileAvailable(user)
    }

    // ---- the two pages ------------------------------------------------------
    private lateinit var homePage: HomePage
    private lateinit var appDrawer: AppDrawer
    private lateinit var pageTurn: PageTurn
    private lateinit var wallpaper: WallpaperPalette
    private var unwatchWallpaper: () -> Unit = {}

    // After "~restart", until the recreation replaces this window: the process's state is already
    // the fresh one, this window's is not, so it takes no more commands or "%". Asked, not kept: a
    // flag would have to be set wherever a state is replaced.
    private val replaced: Boolean get() = state !== app.core.state

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The process hands its refreshes to whichever instance is alive, and loads the list again
        // for a new one: a language change recreates the window, and relabels every app.
        app.instance = launcherWindow
        app.core.windowCreated()

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

        // Asked before the first refresh, so the cached favorites land long before the full
        // enumeration does (CachedFavorites reads the file once per process).
        app.core.homeCache.load { cached ->
            if (!isDestroyed && cached.isNotEmpty() && !state.appsLoaded) homePage.render(cached, loaded = true)
        }

        applyPalette(wallpaper.current)
        wallpaper.ask()
        unwatchWallpaper = WallpaperSystem.watch(this, wallpaper)
    }

    override fun onStart() {
        super.onStart()
        homePage.startBattery()
    }

    override fun onResume() {
        super.onResume()
        // The process decides whether the list is loaded again (AppChanges): not on every return home.
        app.core.windowResumed()
        pageTurn.resumed()
    }

    /** The page stays as it is — a dialog or the shade on top pauses without leaving; [onStop] turns it home. */
    override fun onPause() {
        // A dialog-like or translucent app never stops this activity: without this the drawer
        // (or the recents) would stay open under it, and re-sort in view on the next refresh.
        if (launchedApp) {
            launchedApp = false
            pageTurn.close(animate = false)
        }
        app.core.windowPaused()
        super.onPause()
    }

    /** Leaving the launcher turns the page home instantly, so a return lands on home, not a half-typed search. */
    override fun onStop() {
        pageTurn.close(animate = false)
        homePage.stopBattery()
        super.onStop()
    }

    override fun onDestroy() {
        // No executor shutdown: work this instance queued must still run.
        if (app.instance === launcherWindow) app.instance = null
        unwatchWallpaper()
        super.onDestroy()
    }

    /** Run [block] on the main thread for THIS instance — or not at all once it is destroyed. */
    private fun onUi(block: () -> Unit) = runOnUiThread { if (!isDestroyed) block() }

    /** The process's IO thread, behind Guard ([VersoApp.submitIo]). */
    private fun submitIo(task: Runnable) = app.submitIo(task)

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

    // ---- commands -----------------------------------------------------------

    /** Run a "~" command. */
    private fun runCommand(command: Command) {
        // A deliberate crash, for the crash-only paths, at any time. The constant compiles
        // it out of a public release, where nothing parses to CRASH anyway.
        if (command == Command.CRASH) {
            if (DailyDriver.ENABLED) error("~crash: deliberate crash (daily-driver build)")
            return
        }
        // After "~restart" this window holds the old state until the recreation lands: it takes
        // nothing more. Nothing to say — the new window comes at once.
        if (replaced) return
        // Cleared BEFORE running: clearing fires the TextWatcher synchronously, so the mode is
        // back to NORMAL before the command runs.
        appDrawer.setPrompt("")
        when (command) {
            Command.RESTART -> restart()
            Command.VERIFY -> showReport()
            Command.CRASH -> Unit // returned above
        }
    }

    /** "~verify": its prompt is its view (LauncherLogic.VERIFY), read with the keyboard down. */
    private fun showReport() {
        appDrawer.setPrompt(LauncherLogic.VERIFY)
        appDrawer.hideKeyboard()
    }

    /** "~restart": the reload of the config file, and the repair by hand. */
    private fun restart() {
        // A fresh state (Core.reset) reads the config file again and loads the app list anew, and
        // the recreation builds every view anew around it. Until that recreation lands this window
        // still holds the old state, so it takes nothing more (replaced).
        app.core.reset()
        recreate()
    }

    private fun toast(text: String, duration: Int = Toast.LENGTH_SHORT) =
        Toast.makeText(this, text, duration).show()

    /** "%": kept at once (order.txt); the prompt clears onto the drawer in its new order. */
    private fun toggleOrder() {
        if (replaced) return
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

    private fun renderHome() {
        val favs = state.favorites()
        // Before the first list memory has no apps to show: home keeps the cached favorites, and
        // the cache is not emptied.
        val ready = state.appsLoaded
        homePage.render(favs, ready)
        if (ready) app.core.homeCache.shown(favs)
    }

    private fun applyPalette(p: Palette) {
        app.palette = p
        homePage.applyPalette(p)
        appDrawer.applyPalette(p)
        val lightBars = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
            WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        window.insetsController?.setSystemBarsAppearance(if (p.darkBarIcons) lightBars else 0, lightBars)
    }
}
