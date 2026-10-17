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
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import android.window.BackEvent
import android.window.OnBackAnimationCallback
import android.window.OnBackInvokedDispatcher
import java.io.File
import java.io.FileDescriptor
import java.util.concurrent.RejectedExecutionException

/**
 * The entire Verso Launcher: a text launcher with two pages.
 *
 *  - The FRONT page (recto, "home") is what a normal text launcher shows: a clock, the date
 *    and your favorites as large plain text. No keyboard, no prompt. Tap a favorite to launch
 *    it, long-press it for the rename/tags dialog. Double-tap the clock for the alarms, the
 *    date for the calendar, the battery for its usage screen.
 *  - Pull UP and the page turns to the BACK (verso, "drawer") — it follows the finger: every
 *    app, A to Z, with the Sigil command line at the bottom and the keyboard up. Pull DOWN
 *    (with the list at its top), press Back on an empty prompt or press HOME to turn back.
 *
 * The drawer is the original Sigil Launcher, sigils and all. It lists the launchable apps of
 * every profile (personal, work, clone) through [android.content.pm.LauncherApps], filters as
 * you type, launches on tap/Enter.
 *
 * Config lives in the app's own internal storage (verso.json in filesDir) — no
 * storage permission on any Android version, and the app owns it end to end, so
 * it is edited entirely through the in-app modes below, never a text editor.
 * Prompt grammar (leading sigil = mode):
 *   ""      normal: the full drawer, A to Z (every non-hidden app; favorites always kept).
 *           Enter on it just drops the keyboard so the whole list can be scrolled
 *   text    normal: substring search across ALL apps (hidden included)
 *   *       normal: the drawer with the favorites grouped nearest the prompt;
 *           "*text"/"text*" narrows it to labels starting with text
 *   #       tags: lists the tags in use; tap one to drill into its apps
 *   #text   tag filter: apps under every tag prefix-matching [text], tap/Enter
 *           launches. Tags are assigned via the long-press dialog, or in bulk with "##"
 *   ##      edit tags: lists the tags in use; tap one to get EVERY app with an
 *           [x]/[ ] for that tag, tap a row to toggle it. The long-press dialog stays
 *           the way to invent a tag and to set several on one app; this is the way to
 *           put one tag on many apps
 *   -[text] edit hidden:    tap a row to toggle its [x], persisted immediately
 *   ![text] edit favorites: tap a row to toggle its [x], persisted immediately — this is
 *           what fills the home page
 *   !!      reorder favorites: tap a row to pick it up (marked »), tap another
 *           row to drop it there; tap the picked row again to cancel. The home page
 *           lists the favorites in this order, top to bottom
 *   ?       recents: the last-launched apps (in memory only, empty after a cold start)
 *   ~[text] commands: lists the "~" commands still matching [text] (a bare "~"
 *           lists every one); tap a row to run it
 * The "~" commands. Tap one in that overview, or type enough of one to be
 * unambiguous ("~b" can only be "~backup") and press Enter. Each acts once and
 * clears the prompt. One runs at a time: while it runs, a second is refused (it stays in the
 * prompt), and so is every edit:
 *   ~restart   recreate the activity: the config is read again, the list rebuilt
 *   ~backup    export the config to Download/Verso/verso-<date>_<time>.json,
 *              replacing the previous one once the new one is safely published —
 *              always just the one current backup
 *   ~restore   adopt the newest such backup again. No picker. The config being
 *              replaced is first written next to it as verso-pre-restore-<date>_<time>
 *              .json; if that fails the restore is aborted, so a restore never
 *              destroys the state it overwrote without a trace.
 *   ~restore-saf
 *              same, but PICK the file via the system document picker. The escape
 *              hatch for a config carried over from another phone or surviving a
 *              reinstall (see [restoreConfig]).
 *   ~cleanup   remove the entries of apps no longer installed, after a confirmation and an
 *              undo point (verso-pre-cleanup-<date>_<time>.json)
 * Long-press any row (home or drawer) to set a custom name and its tags.
 */
class MainActivity : Activity() {

    // The row types (Row/AppRow/TagRow/CommandRow) live top-level in LauncherLogic.kt, beside
    // the pure rowsFor that builds them. Here they are parameterised with AppEntry: `shown` is
    // a List<Row<AppEntry>>.

    // The colours for the wallpaper behind (HomeStyle.palette): light text on a dark wallpaper,
    // dark text on a light one — switched live when the wallpaper changes (wallpaperColors).
    private var palette = HomeStyle.palette(lightWallpaper = false)
    // Whether the next onResume must re-enumerate the apps (see onResume / appsMaybeChanged):
    // true at first, then set by the change callbacks while the launcher is not in front.
    private var appsDirty = true
    private var lastRefresh = 0L
    private var resumed = false
    // Between onStart and onStop: the launcher can be seen (see cleanup).
    private var started = false
    // The "~" commands, one at a time (see runCommand).
    private val gate = CommandGate()

    // What outlives this instance: the one disk thread and the recents (see VersoApp).
    private val app get() = VersoApp.from(this)

    // A single background thread owns ALL disk I/O — both the app reload and the
    // config write run here. Off the main thread (a tap or resume never blocks on
    // disk) and serialized against each other, so two rapid saves can't race on the
    // temp file and reloads can't pile up into an unbounded number of threads. It is the
    // PROCESS's thread, not this instance's: a recreated activity queues behind whatever
    // its predecessor left in flight instead of racing it on a thread of its own.
    private val ioExecutor get() = app.io

    // The window root, which also runs the page drag (see SwipeFrame).
    private lateinit var root: SwipeFrame

    // ---- the data ---------------------------------------------------------------
    /**
     * Config, app list and recents, and the rules for keeping them consistent while a
     * background load and the user's taps overlap — see [LauncherState] (and its tests). The
     * Activity hands it its IO thread, the main thread and the Android-side readers/writers,
     * and re-renders when a refresh lands.
     */
    private val state: LauncherState by lazy {
        LauncherState(
            io = ::submitIo,
            main = { task -> onUi { task.run() } },
            loadConfig = { store.load() },
            setAsideUnreadable = { store.setAsideUnreadable() },
            enumerateApps = { apps.loadApps() },
            serialize = ConfigJson::serialize,
            writeConfig = { payload -> store.write(payload) },
            onRefreshed = { configReplaced, listChanged ->
                // The rename dialog read the OLD config when it opened and writes back on
                // OK; left up over a wholesale replace it would persist stale fields into
                // the config that just arrived.
                if (configReplaced) {
                    renameDialog?.dismiss()
                    // Its counts were for the config just replaced.
                    commandDialog?.dismiss()
                }
                // The first load found the config unreadable, set it aside and started over.
                if (state.takeStartedOver()) toast(getString(R.string.toast_config_reset), Toast.LENGTH_LONG)
                if (listChanged) appDrawer.refilter()
            },
            isFreshInstall = { store.isFresh() },
            defaultFavoriteCandidates = { apps.defaultFavoriteCandidates() },
            initialRecents = app.recentKeys,
            onRecentsChanged = { app.recentKeys = it },
        )
    }

    // ---- the two pages ------------------------------------------------------
    // The front page (Kolibri's home layout), built once in onCreate — see HomePage.
    private lateinit var homePage: HomePage
    private lateinit var home: View
    // The back page: the Sigil list + command line. GONE while closed, and blocking focus
    // for its descendants, so the prompt can never grab focus (and raise the IME) on home.
    private lateinit var drawer: LinearLayout
    private lateinit var appDrawer: AppDrawer
    // THE source of truth for which page is up. The views' visibility trails it by the
    // length of the page-turn animation, so nothing may read visibility instead.
    private var drawerOpen = false
    // Uptime until which a page turn is still animating (see SwipeFrame.swallowTaps).
    private var pageTurnUntil = 0L

    // The long-press rename dialog, tracked only so it can be dismissed if the
    // activity is torn down while it is open — an undismissed dialog leaks its
    // window (and would crash on the resulting bad token). configChanges keeps
    // rotation from recreating us; a language, font-size or display-size change still does.
    private var renameDialog: AlertDialog? = null
    // The "~cleanup" confirmation, tracked for the same reason.
    private var commandDialog: AlertDialog? = null
    // Bumped whenever the page turns away (closeDrawer, HOME, onStop): a "~cleanup" check's
    // result must still match it to be shown (see cleanup).
    private var cleanupTicket = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Under enforced edge-to-edge (Android 16) the window never fits the system bars and
        // the system does NOT push our content up for the keyboard on its own — the IME just
        // covers the prompt. So we own the insets and pad for the IME ourselves (below).
        // No grey scrim behind 3-button navigation: the wallpaper runs to the bottom edge, and
        // the bar icons follow the wallpaper's palette (applyPalette).
        window.isNavigationBarContrastEnforced = false

        // The front page is a quiet text home screen, and the IME belongs to the drawer only. The manifest asks for stateHidden; the
        // drawer raises the keyboard itself when it opens (see openDrawer).

        // The back page: list, empty-list hint and command line (see AppDrawer). The decisions
        // stay in LauncherLogic, the side effects of a tap or Enter here.
        appDrawer = AppDrawer(
            activity = this,
            state = state,
            palette = palette,
            onTap = ::onDrawerTap,
            onLongPress = { entry -> if (editAllowed()) promptRename(entry) },
            onEnter = ::onDrawerEnter,
            onFiltered = ::renderHome,
        )
        drawer = appDrawer.view

        homePage = HomePage(
            activity = this,
            onLaunch = ::launch,
            onEdit = { if (editAllowed()) promptRename(it) },
            onOpenDrawer = { openDrawer() },
        )
        home = homePage.view

        // The window root: both pages stacked, home underneath, plus the gestures that turn
        // between them (see SwipeFrame): a page drag that follows the finger once the content
        // under it is at its end, and Kolibri's flick for the recents and as the open fallback.
        // The decisions are the pure SwipeGesture.
        root = SwipeFrame(this).apply {
            addView(home, FrameLayout.LayoutParams(MATCH, MATCH))
            addView(drawer, FrameLayout.LayoutParams(MATCH, MATCH))
            claims = claims@{ dir, downY ->
                // A long-press that opened the rename dialog can carry on into a drag with the
                // finger still down; that must not turn the page under the dialog.
                if (renameDialog != null || commandDialog != null) return@claims false
                // A flick down on favorites scrolled down scrolls them back, not to the recents.
                if (dir == SwipeDir.DOWN && !drawerOpen && homePage.scroller.canScrollVertically(-1)) return@claims false
                // Read live: cheap, and never stale (a display-size change recreates the
                // activity anyway — "density" is not in configChanges).
                val topExclusion = SwipeGesture.TOP_EXCLUSION_DP * resources.displayMetrics.density
                SwipeGesture.claims(drawerOpen, dir, downY, topExclusion)
            }
            // While a page turn is still running, the outgoing page is VISIBLE (just fading)
            // and on top for hit-testing: a quick tap would land on a row the user can no
            // longer really see. Swallow taps for that moment; swipes are still tracked.
            swallowTaps = { SystemClock.uptimeMillis() < pageTurnUntil }
            // Kolibri's home flicks: up = the app drawer, down = the recently used apps (here:
            // the drawer opened on the "?" recents view). Up is normally a drag (below); the
            // flick opens the drawer when the favorites could still scroll and no drag started.
            onSwipe = { dir ->
                when {
                    dir == SwipeDir.UP && !drawerOpen -> openDrawer()
                    dir == SwipeDir.DOWN && !drawerOpen -> openDrawer(RECENTS_PROMPT)
                }
            }
            // The page follows the finger: pull home up to open the drawer, pull the drawer
            // down to close it — once the content under the finger is at its end that way.
            isDrawerOpen = { this@MainActivity.drawerOpen }
            canDrag = canDrag@{ dir ->
                if (renameDialog != null || commandDialog != null) return@canDrag false
                when (dir) {
                    SwipeDir.UP -> !homePage.scroller.canScrollVertically(1)
                    SwipeDir.DOWN -> !appDrawer.listView.canScrollVertically(-1)
                }
            }
            pageHeight = ::pageHeight
            onDragStart = ::beginPageDrag
            onDrag = ::dragPage
            onDragEnd = ::endPageDrag
        }
        setContentView(root)

        // Back on a HOME launcher must not finish the activity — we're the home
        // screen, so the platform's default callback (routed here on targetSdk 36
        // whether or not we opt in) would just finish us and bounce straight back
        // via HOME. Intercept it. On the home page it is a deliberate no-op. In the
        // drawer, a non-empty prompt (a search or an edit mode) clears back to the plain
        // drawer first; Back on an empty prompt turns the page back to home (which also
        // drops the keyboard). Lives for the whole activity.
        //
        // Predictive back: a back gesture that will close the drawer previews it — the drawer
        // follows the gesture down a little (BACK_PREVIEW_FRACTION of the page) over a home
        // fading in, the same pages a drag moves. Released, the close runs on from there;
        // cancelled, the drawer settles back. 3-button Back skips the preview and just closes.
        onBackInvokedDispatcher.registerOnBackInvokedCallback(
            OnBackInvokedDispatcher.PRIORITY_DEFAULT,
            object : OnBackAnimationCallback {
                // The drawer offset the preview started from; null while no preview runs.
                private var previewFrom: Float? = null

                // The bare recents view ("?") is what a swipe down on home opened, so Back
                // leaves it the way it came — straight home, like closing Kolibri's recents
                // list. Anything typed after it ("?gm") is a search like any other: Back clears
                // it first.
                private fun closes(): Boolean {
                    val text = appDrawer.prompt.text?.toString().orEmpty()
                    return drawerOpen && (text.isEmpty() || text == RECENTS_PROMPT)
                }

                override fun onBackStarted(backEvent: BackEvent) {
                    previewFrom = if (closes()) beginPageDrag(SwipeDir.DOWN) else null
                }

                override fun onBackProgressed(backEvent: BackEvent) {
                    val from = previewFrom ?: return
                    if (!drawerOpen) return
                    dragPage(SwipeDir.DOWN, from + backEvent.progress * pageHeight() * BACK_PREVIEW_FRACTION)
                    // The drag's own fade only starts once the drawer is two thirds down — far
                    // past the preview. Home shows through with the gesture instead, faintly:
                    // the drawer still covers most of the page.
                    home.alpha = backEvent.progress * BACK_PREVIEW_HOME_ALPHA
                }

                override fun onBackCancelled() {
                    // Only if the drawer is still the page in front: HOME or the screen going
                    // off may have closed it meanwhile.
                    if (previewFrom != null && drawerOpen) settle(open = true)
                    previewFrom = null
                }

                override fun onBackInvoked() {
                    val previewed = previewFrom != null
                    previewFrom = null
                    if (!drawerOpen) return
                    if (closes()) closeDrawer()
                    else {
                        // The prompt changed during the gesture (a hardware key): the preview
                        // showed a close that is not happening — put the drawer back first.
                        if (previewed) settle(open = true)
                        appDrawer.prompt.setText("")   // TextWatcher -> applyFilter resets mode to NORMAL
                    }
                }
            },
        )

        // Live updates for installs/uninstalls and profile changes (see appsChanged).
        watchApps()

        // A cold start draws the favorites from the cache at once (HomeCache) — queued on the IO
        // thread ahead of the first load, so it lands long before the full enumeration does —
        // and only while the real list has not arrived yet.
        submitIo {
            val cached = HomeCache.read(filesDir)
            onUi {
                if (cached.isNotEmpty() && !state.appsLoaded) {
                    cachedFavorites = cached.map { it.key to it.label }
                    homePage.render(cached, loaded = true)
                }
            }
        }

        // Text colours for the wallpaper behind, and live when it changes (see applyPalette).
        // Asking the system is a binder call that may extract the colours from the bitmap
        // right then (the first start after boot), so it runs on the IO thread; until it
        // answers, the last palette this process saw (a recreated activity) or the dark one.
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

    /** Live battery level on the home page while the launcher is visible. */
    override fun onStart() {
        super.onStart()
        started = true
        homePage.startBattery()
    }

    // ---- turning the page ---------------------------------------------------

    /**
     * Turn to the drawer: fade/slide it in over a fading home page, then hide home entirely
     * (no translucent overlap left on the wallpaper surface). The prompt is always empty here
     * — [closeDrawer] leaves it so — and is then set to [initialPrompt]: "" opens the full
     * A-to-Z list with the keyboard coming up for typing; "?" (swipe down on home) opens the
     * recently used apps WITHOUT the keyboard, like Kolibri's recents list — tap the command
     * line to type after all.
     */
    private fun openDrawer(initialPrompt: String = "") {
        if (drawerOpen) return
        drawerOpen = true
        // A drag still holding the page must not act on it after this turn (see abortDrag).
        root.abortDrag()
        showStatusBar(true)
        renameDialog?.dismiss()
        commandDialog?.dismiss()
        home.animate().cancel()
        drawer.animate().cancel()
        drawer.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        val fromHidden = drawer.visibility != View.VISIBLE
        drawer.visibility = View.VISIBLE
        drawer.alpha = 1f
        // Kolibri's page turn: the drawer slides up over the full height of the window (its
        // slide_in_up, 100%p) while home fades out underneath. The parent's height, not the
        // drawer's: a drawer that was GONE until now has not been measured yet. A drawer that
        // is already showing — pulled up by a drag, or caught mid-close — slides on from
        // where it is instead of jumping to the bottom first.
        if (fromHidden) drawer.translationY = pageHeight()
        drawer.requestApplyInsets()
        if (initialPrompt.isNotEmpty()) appDrawer.setPrompt(initialPrompt)
        else if (appDrawer.shown.isNotEmpty()) appDrawer.listView.setSelection(0)
        pageTurnUntil = SystemClock.uptimeMillis() + PAGE_TURN_MS
        home.setLayerType(View.LAYER_TYPE_NONE, null)  // a drag's layer; withLayer takes over
        drawer.animate().translationY(0f).setDuration(DRAWER_SLIDE_MS).withLayer()
            .setInterpolator(AccelerateDecelerateInterpolator()).start()
        home.animate().alpha(0f).setDuration(HOME_FADE_MS).withLayer()
            .setInterpolator(AccelerateInterpolator()).withEndAction {
                if (drawerOpen) home.visibility = View.INVISIBLE
            }.start()
        // Posted: the drawer was GONE until a moment ago, so let it be laid out once before
        // the prompt takes focus and the IME is asked for.
        if (initialPrompt.isEmpty()) drawer.post { if (drawerOpen) appDrawer.showKeyboard() }
    }

    /**
     * Turn back to home: drop the keyboard, clear the prompt (so the next open starts clean
     * and no edit mode lingers), and swap the pages. [animate] = false is the instant
     * version for when the launcher is going to the background anyway.
     */
    private fun closeDrawer(animate: Boolean = true) {
        if (!drawerOpen) {
            // Home is in front, but a finger may be pulling the drawer up: HOME or the screen
            // going off ends that drag here, or its release would still open the drawer.
            if (root.isDragging) {
                root.abortDrag()
                if (animate) settle(open = false) else showHomeNow()
            }
            return
        }
        drawerOpen = false
        cleanupTicket++
        // HOME, Back or the screen going off while a finger drags the drawer: that drag is over
        // and must not settle the drawer back open over a home that is now in front.
        root.abortDrag()
        renameDialog?.dismiss()
        commandDialog?.dismiss()
        showStatusBar(false)
        appDrawer.hideKeyboard()
        drawer.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        appDrawer.prompt.clearFocus()
        if (appDrawer.prompt.text?.isNotEmpty() == true) appDrawer.prompt.setText("")
        home.animate().cancel()
        drawer.animate().cancel()
        home.visibility = View.VISIBLE
        if (!animate) {
            showHomeNow()
            return
        }
        // Kolibri's way back: the drawer slides down off the screen (slide_out_down) while home
        // fades in (fade_in).
        pageTurnUntil = SystemClock.uptimeMillis() + PAGE_TURN_MS
        home.setLayerType(View.LAYER_TYPE_NONE, null)  // a drag's layer; withLayer takes over
        home.animate().alpha(1f).setDuration(HOME_FADE_MS).withLayer()
            .setInterpolator(AccelerateInterpolator()).start()
        drawer.animate().translationY(pageHeight()).setDuration(DRAWER_SLIDE_MS).withLayer()
            .setInterpolator(AccelerateDecelerateInterpolator()).withEndAction {
                if (!drawerOpen) drawer.visibility = View.GONE
            }.start()
    }

    /** Home in front at once, the drawer gone — no animation. */
    private fun showHomeNow() {
        home.animate().cancel()
        drawer.animate().cancel()
        pageTurnUntil = 0L
        home.setLayerType(View.LAYER_TYPE_NONE, null)
        home.visibility = View.VISIBLE
        home.alpha = 1f
        drawer.translationY = 0f
        drawer.visibility = View.GONE
    }

    /**
     * The status bar belongs to the drawer only: the home page is a quiet full screen, the
     * drawer (a working list with a command line) shows it. Hidden, a swipe from the top edge
     * still brings it — and the notification shade — in transiently, so nothing is out of reach.
     */
    private fun showStatusBar(show: Boolean) {
        val controller = window.insetsController ?: return
        controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (show) controller.show(WindowInsets.Type.statusBars())
        else controller.hide(WindowInsets.Type.statusBars())
    }

    /**
     * A page drag takes over (see SwipeFrame): stop whatever turn is running and put both pages
     * where the drag starts from. Returns the offset already travelled — non-zero when the
     * drawer is caught mid-turn, so the page continues from where it is instead of jumping.
     */
    private fun beginPageDrag(dir: SwipeDir): Float {
        home.animate().cancel()
        drawer.animate().cancel()
        home.visibility = View.VISIBLE
        // Home fades with every move of the drag: a full-screen view group at partial alpha is
        // composed offscreen each frame unless it has a layer — cache it once in a hardware
        // layer for the drag (open/close/settle drop it again).
        home.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        val height = pageHeight()
        return if (dir == SwipeDir.UP) {
            // Opening from home: the drawer comes up from below, focus still blocked — it is not
            // the page in front until the drag commits.
            if (drawer.visibility != View.VISIBLE) drawer.translationY = height
            drawer.visibility = View.VISIBLE
            drawer.alpha = 1f
            height - drawer.translationY
        } else {
            drawer.translationY
        }
    }

    /** The drawer follows the finger; home fades out over the first third of its travel. */
    private fun dragPage(dir: SwipeDir, offset: Float) {
        if ((dir == SwipeDir.DOWN) != drawerOpen) return
        val height = pageHeight()
        val open = if (dir == SwipeDir.UP) offset else height - offset  // how far the drawer is up
        drawer.translationY = height - open
        home.alpha = SwipeGesture.homeAlpha(open, height)
    }

    /** The finger lifted: finish the turn from where the page is, or settle back. */
    private fun endPageDrag(dir: SwipeDir, end: DragEnd) {
        // Belt and braces next to abortDrag: a drag only ever ends on the page it grabbed.
        if ((dir == SwipeDir.DOWN) != drawerOpen) return
        when {
            dir == SwipeDir.UP && end == DragEnd.COMMIT -> openDrawer()
            dir == SwipeDir.DOWN && end == DragEnd.COMMIT -> closeDrawer()
            dir == SwipeDir.UP -> settle(open = false)
            else -> settle(open = true)
        }
    }

    /**
     * A drag that did not commit falls back to the page it started on (Unity's settle-back:
     * 220 ms, decelerating). Nothing else changes — the drawer was never opened by an opening
     * drag that fell back, nor closed by a closing one.
     */
    private fun settle(open: Boolean) {
        pageTurnUntil = SystemClock.uptimeMillis() + DRAG_SETTLE_MS
        home.setLayerType(View.LAYER_TYPE_NONE, null)  // the drag's layer; withLayer takes over
        drawer.animate().translationY(if (open) 0f else pageHeight()).setDuration(DRAG_SETTLE_MS).withLayer()
            .setInterpolator(DecelerateInterpolator()).withEndAction {
                if (!drawerOpen) drawer.visibility = View.GONE
            }.start()
        home.animate().alpha(if (open) 0f else 1f).setDuration(DRAG_SETTLE_MS).withLayer()
            .setInterpolator(DecelerateInterpolator()).withEndAction {
                if (drawerOpen) home.visibility = View.INVISIBLE
            }.start()
    }

    /** The full height a page slides over: the window root's, falling back to the display's
     *  before the first layout. */
    private fun pageHeight(): Float =
        ((drawer.parent as? View)?.height?.takeIf { it > 0 }
            ?: resources.displayMetrics.heightPixels).toFloat()

    override fun onResume() {
        super.onResume()
        resumed = true
        // Re-enumerate only when something changed since the last load — the LauncherApps
        // callback and the profile broadcasts report every install, removal, update and
        // profile change (appsMaybeChanged) — or the last load is old enough that a missed
        // change would matter. Not on every return home: that was a full enumeration of every
        // profile (a binder call each, a label per app) competing with the return animation.
        val now = SystemClock.uptimeMillis()
        // Also while the launcher is not current (state.needsRefresh): a cold start whose load
        // has not landed, or a load that failed or was dropped — no package event will come
        // to retry it.
        if (appsDirty || state.needsRefresh || now - lastRefresh > REFRESH_STALE_MS) {
            appsDirty = false
            lastRefresh = now
            state.refresh()
        }
        // Re-assert on every return: the bar's visibility is a request on our window, and
        // coming back from an app that showed it must not leave it up on the home page.
        showStatusBar(drawerOpen)
        // Only the drawer is keyboard-first. It is normally closed by the time we come
        // back (onStop turns the page home), but a resume WITHOUT a stop — a dialog or a
        // translucent activity on top — returns to an open drawer, and there the
        // keyboard belongs up again.
        // The recents view ("?", from a swipe down) deliberately opens without it.
        if (drawerOpen && appDrawer.prompt.text?.toString() != RECENTS_PROMPT) appDrawer.showKeyboard()
    }

    /** Not in front any more: package changes from now on only mark the list dirty (see
     *  appsMaybeChanged). The page stays as it is — a dialog or the shade on top pauses
     *  without leaving; [onStop] turns it home. */
    override fun onPause() {
        resumed = false
        // A change still waiting to be collected is picked up by the next resume instead.
        // (View.removeCallbacks always answers true, so the flag says whether one waits.)
        if (refreshSoonPending) {
            root.removeCallbacks(refreshSoon)
            refreshSoonPending = false
            appsDirty = true
        }
        super.onPause()
    }

    /**
     * The installed apps may have changed (see appsChanged / profilesChanged). In front: reload
     * now. Behind another app: only note it — a burst of updates (a store updating 20 apps)
     * would otherwise run 20 full enumerations for a page nobody sees; onResume reloads once.
     * In front, a burst too (a store updating apps while home is shown, an app switching its
     * components) is collected for half a second into one refresh.
     */
    private fun appsMaybeChanged() {
        if (resumed) {
            root.removeCallbacks(refreshSoon)
            root.postDelayed(refreshSoon, CHANGE_SETTLE_MS)
            refreshSoonPending = true
        } else {
            appsDirty = true
        }
    }

    private var refreshSoonPending = false
    private val refreshSoon = Runnable {
        refreshSoonPending = false
        if (resumed) {
            lastRefresh = SystemClock.uptimeMillis()
            state.refresh()
        } else {
            appsDirty = true
        }
    }

    /**
     * Leaving the launcher (an app was started, the screen went off, the user went to
     * recents) turns the page back home, instantly — coming back to the launcher should
     * land on the home screen, like any launcher, not on a half-typed search.
     */
    override fun onStop() {
        started = false
        cleanupTicket++
        // Also a dialog opened from the home page, which closeDrawer does not touch.
        renameDialog?.dismiss()
        commandDialog?.dismiss()
        closeDrawer(animate = false)
        homePage.stopBattery()
        super.onStop()
    }

    override fun onDestroy() {
        // Tear down an open rename dialog first — an undismissed dialog leaks its
        // window once the activity's context is gone. setOnDismissListener nulls the
        // field; guarding here is belt-and-suspenders.
        renameDialog?.dismiss()
        renameDialog = null
        commandDialog?.dismiss()
        commandDialog = null
        // No executor shutdown: the IO thread is the process's (VersoApp.io), and work this
        // instance queued — a save above all — must still run for the next one. What it
        // would hand back to this instance's main thread is dropped by onUi instead.
        unwatchApps()
        WallpaperManager.getInstance(this).removeOnColorsChangedListener(wallpaperColors)
        super.onDestroy()
    }

    /**
     * Run [block] on the main thread for THIS instance — or not at all once it is destroyed.
     * IO work outlives the instance that queued it (the thread is the process's); its
     * hand-back must not then act on a dead activity: adopt a restore into a state nobody
     * shows, toast from a gone window, re-render views that are no longer attached.
     */
    private fun onUi(block: () -> Unit) = runOnUiThread { if (!isDestroyed) block() }

    /**
     * [task] behind a last-resort net. Hard rule 2 — a HOME app must never throw — is kept by
     * every IO function catching its own errors; this is the backstop for the one that someday
     * does not: an exception escaping on the executor (or the SAF read thread) reaches the
     * thread's uncaught handler and kills the process on every start. Logged, not swallowed
     * silently.
     */
    private fun guarded(what: String, task: Runnable) = Runnable {
        try {
            task.run()
        } catch (e: RuntimeException) {
            Log.w("Verso", "$what failed", e)
        }
    }

    /**
     * Submit disk work to [ioExecutor]. The executor is the process's and never shut down, so
     * a refusal is not expected; the check and the catch keep a future change that does shut
     * it down from crashing the HOME app — a refused task is reported as false, never thrown.
     */
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

    /**
     * A hardware keyboard on the home page: typing opens the drawer with that character in
     * the command line — the keyboard's way to what a swipe up does. Search does the same
     * without a character. Keys the focused view takes (Enter on a favorite) never get here.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (!drawerOpen) {
            val c = printable(event)
            if (c != null || keyCode == KeyEvent.KEYCODE_SEARCH) {
                openDrawer()
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
        // HOME is a "back to a clean home screen" gesture, so a rename dialog left open has
        // no business surviving it: confirming it afterwards would apply a rename the user
        // has visually left behind.
        renameDialog?.dismiss()
        commandDialog?.dismiss()
        cleanupTicket++
        // Also clears the prompt (closeDrawer always does), so the next swipe up opens on
        // the plain drawer rather than whatever mode was last typed.
        closeDrawer()
        homePage.scrollToTop()
    }

    // ---- config -------------------------------------------------------------

    /** The stores' logger: android.util.Log, with or without a cause. */
    private fun logWarning(msg: String, e: Throwable?) {
        if (e != null) Log.w("Verso", msg, e) else Log.w("Verso", msg)
    }

    /**
     * The verso.json file layer. Everything Android-specific it needs is injected here:
     * filesDir, android.util.Log and the directory fsync. Loading and publishing itself lives
     * in [ConfigStore], where it is unit-tested away from the Activity.
     */
    private val store by lazy {
        ConfigStore(
            dir = filesDir,
            log = ::logWarning,
            syncDir = ::fsyncDir,
        )
    }

    /**
     * The shared-storage backup layer (Download/[BackupFiles.DIR]). The MediaStore mechanics live
     * in [BackupStore]; the Activity keeps only the orchestration that needs it — backup,
     * restoreConfig and the "~restore-saf" picker.
     */
    private val backupStore by lazy {
        BackupStore(
            resolver = contentResolver,
            subDir = BackupFiles.DIR,
            log = ::logWarning,
            owner = packageName,
        )
    }

    /**
     * Run a one-shot "~" command, one at a time ([CommandGate], hard rule 15). Each reports back
     * with a toast on its real result — a save you cannot see happen is a save you do not trust.
     */
    private fun runCommand(command: Command) {
        // Refused while one runs — and the typed command stays in the prompt, to send again.
        if (gate.busy) {
            toast(getString(R.string.toast_busy))
            return
        }
        // Every command but ~restart works on the config in memory: not before the first load
        // (a cold start's first moment).
        if (command != Command.RESTART && !editable()) return
        // Cleared BEFORE running ("" parses to no command): setText fires the TextWatcher
        // synchronously, so the mode is back to NORMAL before the command runs.
        appDrawer.prompt.setText("")
        gate.start { t ->
            when (command) {
                // A new instance reads the config afresh and lists the apps again; its first load
                // queues behind any save still on the IO thread. The redraw is the feedback.
                Command.RESTART -> if (safReading) toast(getString(R.string.toast_busy)) else recreate()
                Command.BACKUP -> backup(t)
                Command.RESTORE -> restoreConfig(t)
                // Only the picker: it is another activity, so no run spans it. Its answer
                // starts a run of its own (onActivityResult).
                Command.RESTORE_SAF -> pickRestoreFile()
                Command.CLEANUP -> cleanup(t)
            }
        }
    }

    /** One IO step of the run [t] (see [CommandGate.io]): [then] gets null if it failed. */
    private fun <T : Any> commandIo(t: CommandGate.Ticket, work: () -> T?, then: (T?) -> Unit) =
        gate.io(t, ::submitIo, { block -> onUi(block) }, work, { e -> Log.w("Verso", "command step failed", e) }, then)

    /**
     * "~backup": export the config to Download/[BackupFiles.DIR] as a backup of [BackupFiles.BACKUP_BASE],
     * replacing the previous one only once the new one is published (see [BackupStore.write]).
     * There is one current backup and one undo point, each named after the moment it was
     * taken (see BackupFiles) — no pile to sift through, which is what lets [restoreConfig]
     * work without a file picker: it reads the newest one.
     *
     * The only copy besides verso.json itself (which lives in filesDir and dies with the app):
     * an ordinary file in shared storage that can be copied off the device.
     *
     * The config in memory, serialized here on the main thread, where it is only ever mutated,
     * so the read is race-free. Never before the first load (runCommand): memory then holds the
     * empty default, and exporting THAT would replace the real backup with nothing.
     */
    private fun backup(t: CommandGate.Ticket) {
        val payload = ConfigJson.serialize(state.loadedSnapshot() ?: return)
        commandIo(t, work = { backupStore.write(BackupFiles.BACKUP_BASE, payload) }) { name ->
            // Name the file actually written: its name carries the time it was taken.
            if (name != null) toast(getString(R.string.toast_backup_ok, name), Toast.LENGTH_LONG)
            else toast(getString(R.string.toast_backup_failed))
        }
    }

    /**
     * "~cleanup": offer to remove what the config keeps for apps that are no longer installed.
     * The check ([cleanupCheck]) runs on the IO thread: a fresh, COMPLETE enumeration, and only
     * entries whose package is gone from the device altogether. Nothing is removed without the
     * dialog's OK, and then [LauncherState.cleanup] checks again and writes an undo point first.
     */
    private fun cleanup(t: CommandGate.Ticket) {
        val ticket = cleanupTicket
        val snapshot = state.config.snapshot()
        commandIo(t, work = { apps.safeCleanupCheck(snapshot) }) { plan ->
            // Left meanwhile — the drawer closed, HOME, an app started, the screen off: no
            // dialog popping up over a page that moved on, no toast over another app.
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

    /** OK: [LauncherState.cleanup] re-checks, writes the undo point, then removes — a run of its own. */
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
        // Gone meanwhile (an app in front, the screen off): the result stands, but no toast
        // over another app.
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

    /**
     * "~restore": read the newest backup back in. No picker — there is one current backup,
     * found by pattern whatever its timestamp, so there is nothing to choose.
     *
     * The read runs on [ioExecutor]; only the adoption returns to the main thread, where
     * the config lives.
     */
    private fun restoreConfig(t: CommandGate.Ticket) {
        commandIo(t, work = {
            // The newest backup of ours, whatever MediaStore named it — or, if none was ever
            // published, an interrupted first one (see BackupFiles.latestToRestore).
            val latest = backupStore.lookupLatest(BackupFiles.BACKUP_BASE)
            val text = (latest as? BackupStore.Latest.Found)?.let { backupStore.readText(it.uri) }
            // Parsed here, off the main thread: a hostile document is parse work the UI must
            // not wait on (and the parse is where a torn temp gets rejected).
            latest to text?.let { ConfigJson.parseForeign(it) }
        }) { read ->
            val (latest, restored) = read ?: (null to null)
            // Nothing there at all is its own message: "~backup was never run" is a different
            // problem from "the backup is broken", and both differ from "the folder could not
            // be read" — saying which saves the user a wrong hunt.
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
     * Ask the system picker for a config, then adopt it — the "~restore-saf" command.
     *
     * Plain "~restore" reads the app's OWN MediaStore row, which is invisible after a
     * reinstall and on a different phone; ACTION_OPEN_DOCUMENT has no such blind spot,
     * because the user granting the pick IS the permission. So this is the way a config
     * moves between devices, at the cost of one dialog.
     */
    private fun pickRestoreFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            // "*/*" plus a hint list, not a strict "application/json": a .json arrives
            // typed as text/plain or octet-stream depending on who wrote or copied it,
            // and a strict filter would grey out the very file being looked for.
            .setType("*/*")
            .putExtra(
                Intent.EXTRA_MIME_TYPES,
                arrayOf("application/json", "text/plain", "application/octet-stream"),
            )
            // Open ON the backup folder rather than wherever the picker last was — the
            // backups live in a SUB-folder of Downloads, so a picker landing in Downloads
            // root shows everything EXCEPT them. Only a hint; DocumentsUI ignores it when
            // it cannot honour it (folder not created yet, no backup ever written).
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
    // A picked file is being read: no run yet, but ~restart would drop its result.
    private var safReading = false

    /** The "~restore-saf" picker's answer. Reading stays off the main thread; only the
     *  adoption comes back to it, exactly as in [restoreConfig]. */
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_RESTORE) return
        val uri = data?.data
        // Backing out of the picker is not an error — say nothing.
        if (resultCode != RESULT_OK || uri == null) return
        // Its own short-lived thread, NOT the app's IO thread: the picked document can live
        // behind any content provider — a cloud drive offline or slow — and a read there can
        // block for as long as the provider likes. On the IO thread that would stall every save
        // and every app refresh behind it. This read touches no file of the app's own, so it
        // needs none of the IO thread's ordering; the undo point and the adoption still go
        // through it (adoptRestored). Never throws: readText and parseForeign return null.
        // Newest pick wins: a read stuck at a hung provider may finish after a later pick — its
        // result must not be adopted over the newer one.
        val pick = ++restorePick
        safReading = true
        Thread(guarded("restore read") {
            val restored = backupStore.readText(uri)?.let { ConfigJson.parseForeign(it) }
            onUi {
                if (pick != restorePick) return@onUi
                safReading = false
                // Unlike [restoreConfig] a null here is not "no backup yet" — the user
                // pointed at a file and it could not be read or is no config. Either way
                // nothing was changed.
                if (restored == null) toast(getString(R.string.toast_restore_failed), Toast.LENGTH_LONG)
                // The adoption is a run of its own; with another one running, the pick is
                // dropped — "~restore-saf" again once that one is done.
                else if (!gate.start { t -> adoptRestored(t, restored) }) toast(getString(R.string.toast_busy))
            }
        }, "verso-restore-read").start()
    }

    /**
     * Adopt a config that [restoreConfig] or the "~restore-saf" picker read and parsed — with
     * parseForeign, NOT parse: the lenient parser would turn any JSON object at all into an
     * empty config and wipe everything without so much as an error. A document that did not
     * parse never gets here, so a malformed backup leaves the live config untouched.
     *
     * Then the undo point — the config being replaced — is written out as a backup of
     * [BackupFiles.PRE_RESTORE_BASE], and ONLY once that write succeeded is [restored] adopted. A restore
     * whose undo point could not be written is aborted: replacing the config with nothing to
     * go back to is exactly the "destroyed without a trace" the undo point exists to prevent.
     * The outgoing config is taken from memory, never before the first load: the empty default
     * would replace a real undo point with nothing (the picker's answer can reach a fresh process
     * whose first load has not landed yet — then the user is told to try again).
     */
    private fun adoptRestored(t: CommandGate.Ticket, restored: VersoConfig) {
        // What a restore actually adopted, so a "my favorites are gone" report can be
        // settled from the log instead of guessed at.
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
            // The IO half in its load-bearing order (CLAUDE.md rule 7): stop if nothing would
            // change, write the undo point, persist the restored config — see RestoreSteps.
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
                is RestoreSteps.Outcome.Done -> {
                    // Wholesale replace, save and re-render — with the epoch guard that keeps an
                    // enumeration already in flight from handing the OLD config back over this one.
                    state.adopt(restored)
                    // A dialog opened against the pre-restore config would write its stale name
                    // and tags over what the restore just brought in.
                    renameDialog?.dismiss()
                    commandDialog?.dismiss()
                    toast(getString(R.string.toast_restore_ok, BackupFiles.timeOf(outcome.undoName)), Toast.LENGTH_LONG)
                }
            }
        }
    }

    /**
     * Whether an edit can be saved now. Not before the config is loaded — the empty default must
     * never be written over the real file; if not, the toast says why.
     */
    private fun editable(): Boolean {
        if (state.configLoaded) return true
        toast(getString(R.string.toast_not_loaded))
        return false
    }

    /**
     * Whether the user may edit now: not while a "~" command runs — a save queued behind a
     * restore's IO step would write the old config back over it (and a recreation before the
     * hand-back would leave it there) — and only once the config is loaded ([editable]).
     */
    private fun editAllowed(): Boolean {
        if (gate.busy) {
            toast(getString(R.string.toast_busy))
            return false
        }
        return editable()
    }

    /** Toasts are this launcher's only chrome — one helper so they stay uniform. */
    private fun toast(text: String, duration: Int = Toast.LENGTH_SHORT) =
        Toast.makeText(this, text, duration).show()

    /**
     * fsync a *directory* so a preceding rename() is durable, not merely visible.
     * The rename publishes atomically, but the directory entry it rewrites isn't on
     * disk until the directory itself is synced — so a power-loss just after a rename
     * can silently roll the publish back to the previous file. Platform syscalls only
     * (no AndroidX): open the dir read-only, fsync the fd, close it. Best-effort — a
     * failure here only weakens durability, it never corrupts, so it is logged, not
     * thrown, exactly like the rest of the save path.
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

    /** A tap on a drawer row (AppDrawer has checked it still aims at the list it was meant for):
     *  the side effects of [LauncherLogic.tapAction]'s decision. Internal for MainActivityFlowsTest. */
    internal fun onDrawerTap(action: TapAction<AppEntry>) {
        when (action) {
            is TapAction.Launch -> launch(action.entry)
            is TapAction.ToggleHidden -> if (editAllowed()) {
                state.toggleHidden(action.entry.key)
                afterToggle()
            }
            is TapAction.ToggleFavorite -> if (editAllowed()) {
                state.toggleFavorite(action.entry.key)
                afterToggle()
            }
            is TapAction.ReorderPick -> reorderTap(action.entry.key)
            is TapAction.ToggleTag -> toggleTagOn(action.entry.key)
            is TapAction.Run -> runCommand(action.command)
            // A tag row drills into that tag's apps by rewriting the prompt — the
            // TextWatcher then re-filters. The same row type serves both overviews;
            // which sigil it drills into is decided by the mode it was rendered in.
            is TapAction.SetPrompt -> appDrawer.setPrompt(action.text)
            TapAction.None -> {}  // stale position, or a row this mode ignores
        }
    }

    /** Enter on the command line: the side effects of [LauncherLogic.enterAction]'s decision. */
    private fun onDrawerEnter(action: EnterAction<AppEntry>) {
        when (action) {
            is EnterAction.Run -> runCommand(action.command)
            is EnterAction.LaunchApp -> launch(action.entry)
            // Drill a tag ("#tag"/"##tag") or clear back to NORMAL ("").
            // setPrompt drives the TextWatcher exactly as typing does, so
            // the drawer/filter and the visible command line stay one source of truth.
            is EnterAction.SetPrompt -> appDrawer.setPrompt(action.text)
            // Empty prompt: the list already shows every app, so Enter only moves
            // the keyboard out of the way of scrolling it.
            EnterAction.DismissKeyboard -> appDrawer.hideKeyboard()
            EnterAction.None -> {}
        }
    }

    /**
     * "##tag": put the chosen tag on [key] or take it off, persisted immediately like the
     * other edit modes' checkboxes. A tag lives in a per-app list inside a map, and an app
     * left with no tags loses its key (see [LauncherState.toggleTag]).
     */
    private fun toggleTagOn(key: String) {
        val tag = appDrawer.tagEditTag ?: return
        if (!editAllowed()) return
        state.toggleTag(key, tag)
        appDrawer.notifyChanged()
    }

    /** After a hidden/favorite toggle: the membership glyphs changed (the row set did not),
     *  and a favorite toggle changes the home page. */
    private fun afterToggle() {
        appDrawer.notifyChanged()
        renderHome()
    }

    /**
     * One tap in FAV_REORDER. Nothing held yet -> pick this row up. The held row
     * tapped again -> cancel. Any other row -> drop the held favorite there (see
     * [moveFavorite]). A pick/cancel only flips the » marker, so notifyDataSetChanged
     * is enough; a drop reorders the set, so it re-renders via applyFilter.
     */
    private fun reorderTap(key: String) {
        when (appDrawer.reorderPick) {
            null -> { appDrawer.reorderPick = key; appDrawer.notifyChanged() }
            key  -> { appDrawer.reorderPick = null; appDrawer.notifyChanged() }
            else -> if (editAllowed()) moveFavorite(appDrawer.reorderPick!!, key)
        }
    }

    /**
     * Drop the picked favorite into [targetKey]'s slot (see [LauncherState.moveFavorite]).
     * Either way the » marker goes; an impossible move (a key vanished mid-session, or the
     * same row) leaves the order untouched.
     */
    private fun moveFavorite(pickedKey: String, targetKey: String) {
        appDrawer.reorderPick = null
        state.moveFavorite(pickedKey, targetKey)
        appDrawer.refilter()  // re-render in the (new) order
    }

    private fun promptRename(entry: AppEntry) {
        // The home page can show cached favorites before the config is loaded; the dialog
        // would open on the empty default (no tags) and its edit could not be saved.
        if (!state.configLoaded) return
        val key = entry.key
        // Dismiss any dialog already up (rapid long-presses) before opening a new one, and
        // keep the reference so onDestroy can tear it down. The view construction lives in
        // RenameDialog; the Activity keeps only the config decisions the commit implies.
        renameDialog?.dismiss()
        val dialog = RenameDialog.show(
            context = this,
            title = entry.label,
            initialName = state.config.names[key] ?: entry.systemLabel,
            initialTags = state.config.tags[key]?.joinToString(", ").orEmpty(),
            allTags = LauncherLogic.allTags(state.config.tags),
            // The dialog is dark on every wallpaper (Theme_Material_Dialog_Alert), so its text
            // stays light whatever the drawer's palette is.
            fgColor = DIALOG_TEXT,
            onAppInfo = { openAppInfo(entry) },
        ) { name, tags ->
            // A run may have started while the dialog was up (a ~restore-saf read ending): an
            // edit now would save behind it. The name is dropped; the toast says why.
            if (!editAllowed()) return@show
            // Name override, tags, save, in-memory relabel + re-sort: LauncherState.rename. A load
            // in flight cannot undo it — it carries no names, they are applied at its hand-off.
            state.rename(key, entry.systemLabel, name, tags)
            appDrawer.refilter()
        }
        renameDialog = dialog
        // Clear the field on dismiss so we never hold a stale, already-gone dialog — but only if
        // it still holds THIS one: Dialog delivers the dismiss listener as a posted message, so
        // the old dialog's listener runs after the new one was assigned and must not null it.
        dialog.setOnDismissListener { if (renameDialog === dialog) renameDialog = null }
    }

    // ---- apps ---------------------------------------------------------------

    private val launcherApps: LauncherApps by lazy { getSystemService(LauncherApps::class.java) }
    private val userManager: UserManager by lazy { getSystemService(UserManager::class.java) }

    /** The apps on the device: enumeration, "~cleanup"'s check, default favorites (see [Apps]). */
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

    /**
     * Installs, uninstalls and updates — in any profile — go to [appsMaybeChanged]: a refresh
     * right away while the launcher is in front, a dirty mark for the next resume otherwise.
     * Registered for the activity's lifetime on the main looper, where [LauncherState]
     * lives; a burst of callbacks (an app update fires several) costs little, because each
     * refresh supersedes the one before it.
     */
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

    /** The profile a tap on a paused app turned back on, and that app's label — for the
     *  "ready" toast once the profile is available (see launch). */
    private var waitingForProfile: Pair<UserHandle, String>? = null
    // When that tap was. Only an answer within PROFILE_WAIT_MS gets the toast: a challenge
    // cancelled and work apps turned on hours later from Quick Settings must not announce an
    // app nobody just asked for. Not cleared in onStop — the work challenge covers us.
    private var waitingSince = 0L

    /** A profile added, removed, paused or resumed changes which apps there are to list (and
     *  how they are drawn: a paused profile's apps are dimmed). */
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
        runCatching { unregisterReceiver(profilesChanged) }
    }

    /** The favorites the cache file holds, as last written or read (see HomeCache). */
    private var cachedFavorites: List<Pair<String, String>>? = null

    /** Keep the home page in step with favorites, labels and the app set. Cheap when nothing
     *  it shows changed, so it is fine to call from every applyFilter. */
    private fun renderHome() {
        val favs = state.favorites()
        // Only with the config loaded: before that memory has no favorites — home keeps what it
        // shows (the cached favorites), and the cache is not emptied.
        val ready = state.appsLoaded && state.configLoaded
        homePage.render(favs, ready)
        // Keep the cold-start cache in step with what home really shows — written only when
        // the favorites (keys, labels) actually changed, never on a plain re-render.
        if (ready) {
            val pairs = favs.map { it.key to it.label }
            if (pairs != cachedFavorites) {
                cachedFavorites = pairs
                submitIo { HomeCache.write(filesDir, favs) }
            }
        }
    }

    private fun launch(entry: AppEntry) {
        // Through LauncherApps, so an app of another profile starts IN that profile; it starts
        // in its own task, not nested in this one.
        val user = apps.userOf(entry)
        val component = ComponentName(entry.packageName, entry.className)
        try {
            when {
                // The entry's profile is gone (a work profile removed since the list loaded).
                user == null -> notFound(entry)
                // A paused work profile: the first tap only turns it back on (the system confirms
                // with the user where needed) — it does not start the app. Once the profile is
                // available the row turns normal and a toast says the app is ready, so the user
                // decides whether it was only about turning work apps on or also about starting
                // this one (see profilesChanged).
                userManager.isQuietModeEnabled(user) -> {
                    waitingForProfile = user to entry.label
                    waitingSince = SystemClock.uptimeMillis()
                    // Only the default home app may turn a profile on: anyone else gets a
                    // SecurityException, and a bare "denied" would not say what to change.
                    try {
                        userManager.requestQuietModeEnabled(false, user)
                    } catch (e: SecurityException) {
                        waitingForProfile = null
                        Log.w("Verso", "quiet mode denied: ${entry.key}", e)
                        toast(getString(R.string.toast_not_default_home), Toast.LENGTH_LONG)
                    }
                }
                // Component gone since the list loaded (uninstall race). Without this check
                // startMainActivity throws a SecurityException, which would misreport the
                // vanished app as "denied".
                !launcherApps.isActivityEnabled(component, user) -> {
                    Log.w("Verso", "launch unavailable: ${entry.key}")
                    notFound(entry)
                }
                else -> {
                    launcherApps.startMainActivity(component, user, null, null)
                    // Only a launch that actually started counts as "recent" — the other
                    // branches leave the list untouched so a dead component never lingers
                    // at the top of "?".
                    state.recordLaunch(entry.key)
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

    /**
     * Whether the system wallpaper wants dark text — Android's own judgement of it
     * (WallpaperColors.HINT_SUPPORTS_DARK_TEXT), the signal Unity Kolibri's smart contrast uses.
     * No permission needed. Unknown (a live wallpaper without colours, an error) counts as dark:
     * light text with a dark rim is what Verso always showed.
     */
    private fun wallpaperIsLight(): Boolean = try {
        val colors = WallpaperManager.getInstance(this).getWallpaperColors(WallpaperManager.FLAG_SYSTEM)
        colors.wantsDarkText()
    } catch (e: RuntimeException) {
        Log.w("Verso", "wallpaper colours unavailable", e)
        false
    }

    private fun WallpaperColors?.wantsDarkText(): Boolean =
        this != null && (colorHints and WallpaperColors.HINT_SUPPORTS_DARK_TEXT) != 0

    /** A new wallpaper (or new colours of a live one) may flip the palette. */
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

    /**
     * Repaint everything that sits on the wallpaper in [p]: the home page, the drawer rows (via a
     * rebind), the prompt, its hint and the ×, and the system bars' icons (dark on a light
     * wallpaper, so the clock and battery up there stay visible).
     */
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

    /**
     * Open the system "App info" screen for this component's package (uninstall, permissions,
     * force-stop live there). Reached from the long-press dialog's neutral button. Launched in
     * its own task like [launch], and guarded the same way so a missing Settings activity or a
     * component gone mid-session can never crash HOME.
     */
    private fun openAppInfo(entry: AppEntry) {
        // The app's details page in ITS profile — a work app's settings live there.
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

    private fun Int.dp(): Int = dp(this)

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        const val REQ_RESTORE = 1
        /** Kolibri's page turn: the drawer's full-height slide (anim_duration_drawer_slide)… */
        const val DRAWER_SLIDE_MS = 180L
        /** …and home's fade underneath it (fade_in / fade_out). */
        const val HOME_FADE_MS = 200L
        /** The rename dialog's field text — light, on the always-dark dialog. */
        const val DIALOG_TEXT = 0xFFD4D4D4.toInt()
        /** A return home re-enumerates anyway once the last load is this old (see onResume). */
        const val REFRESH_STALE_MS = 5 * 60 * 1000L
        /** How long package changes in front are collected into one refresh. */
        const val CHANGE_SETTLE_MS = 500L
        /** How long a paused profile's "ready" toast stays expected after the tap (see launch). */
        const val PROFILE_WAIT_MS = 2 * 60 * 1000L
        /** A page drag that does not commit settles back this fast (Unity's settle-back). */
        const val DRAG_SETTLE_MS = 220L
        /** How far a back gesture's preview pulls the drawer down, as a fraction of the page. */
        const val BACK_PREVIEW_FRACTION = 0.25f
        /** How far home fades in by the end of a back preview. */
        const val BACK_PREVIEW_HOME_ALPHA = 0.5f
        /** How long a page turn runs, i.e. how long taps are swallowed (see swallowTaps). */
        const val PAGE_TURN_MS = HOME_FADE_MS
        /** What a swipe down on home opens the drawer on: the recently used apps. */
        const val RECENTS_PROMPT = "?"
    }
}
