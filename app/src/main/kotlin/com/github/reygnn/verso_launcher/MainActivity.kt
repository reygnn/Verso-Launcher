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
import kotlin.math.abs

/**
 * The launcher's single window: the home page (recto) in front, the drawer with Sigil's command
 * line (verso) behind it, the page turn between them, and the side effects of taps, edits and
 * the "~" commands. The decisions themselves are pure, in [LauncherLogic] and [LauncherState].
 */
class MainActivity : Activity() {

    private var palette = HomeStyle.palette(lightWallpaper = false)
    // Whether the next onResume must re-enumerate the apps.
    private var appsDirty = true
    private var lastRefresh = 0L
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

    private lateinit var root: SwipeFrame

    // ---- the data ---------------------------------------------------------------
    private val state: LauncherState by lazy {
        LauncherState(
            io = ::submitIo,
            main = { task -> onUi { task.run() } },
            loadConfig = { store.load() },
            setAsideUnreadable = {
                store.setAsideUnreadable { text -> backupStore.write(BackupFiles.UNREADABLE_BASE, text) != null }
            },
            enumerateApps = { apps.loadApps() },
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
            initialRecents = app.recentKeys,
            initialRecentsLoaded = app.recentsLoaded,
            onRecentsChanged = { keys, loaded ->
                app.recentKeys = keys
                app.recentsLoaded = loaded
            },
            loadRecents = { RecentsLog.read(filesDir, LauncherState.RECENTS_LIMIT) },
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
    private lateinit var home: View
    // GONE while closed and blocking descendant focus, so the prompt can never raise the IME on home.
    private lateinit var drawer: LinearLayout
    private lateinit var appDrawer: AppDrawer
    // THE source of truth for which page is up. The views' visibility trails it by the
    // length of the page-turn animation, so nothing may read visibility instead.
    private var drawerOpen = false
    // The recents come down from the top like a HUD and go back up; the drawer rises from below.
    private var drawerFromTop = false
    // Whether the running drag (or Back preview) set out to open the drawer.
    private var dragOpens = false

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
            onModeChanged = { mode ->
                // Out of the recents (× or typing) it is the plain drawer: it goes back down like one.
                if (drawerOpen && drawerFromTop && mode != Mode.RECENTS && !root.isDragging) drawerFromTop = false
            },
        )
        drawer = appDrawer.view

        homePage = HomePage(
            activity = this,
            onLaunch = ::launch,
            onEdit = { if (editPermit() != null) promptRename(it) },
            onOpenDrawer = { openDrawer() },
        )
        home = homePage.view

        root = SwipeFrame(this).apply {
            addView(home, FrameLayout.LayoutParams(MATCH, MATCH))
            addView(drawer, FrameLayout.LayoutParams(MATCH, MATCH))
            claims = claims@{ dir, downY ->
                // A long-press that opened a dialog can carry on into a drag with the finger still down.
                if (renameDialog != null || commandDialog != null) return@claims false
                // A flick down on favorites scrolled down scrolls them back, not to the recents.
                if (dir == SwipeDir.DOWN && !drawerOpen && homePage.scroller.canScrollVertically(-1)) return@claims false
                val topExclusion = SwipeGesture.TOP_EXCLUSION_DP * resources.displayMetrics.density
                SwipeGesture.claims(drawerOpen, dir, downY, topExclusion)
            }
            // Up is normally a drag; the flick opens the drawer when the favorites could still
            // scroll and no drag started.
            onSwipe = { dir ->
                when {
                    dir == SwipeDir.UP && !drawerOpen -> openDrawer()
                    dir == SwipeDir.DOWN && !drawerOpen -> openDrawer(RECENTS_PROMPT, fromTop = true)
                }
            }
            closeDirection = { if (drawerOpen) closeDir() else null }
            canDrag = canDrag@{ dir, downY ->
                if (renameDialog != null || commandDialog != null) return@canDrag false
                when {
                    drawerOpen && drawerFromTop -> !appDrawer.listView.canScrollVertically(1)
                    drawerOpen -> !appDrawer.listView.canScrollVertically(-1)
                    dir == SwipeDir.UP -> !homePage.scroller.canScrollVertically(1)
                    // A pull from the very top is the notification shade's.
                    else -> downY >= SwipeGesture.TOP_EXCLUSION_DP * resources.displayMetrics.density &&
                        !homePage.scroller.canScrollVertically(-1)
                }
            }
            pageHeight = ::pageHeight
            onDragStart = ::beginPageDrag
            onDrag = ::dragPage
            onDragEnd = ::endPageDrag
        }
        setContentView(root)

        // Back must never finish a HOME activity (the default callback would, on targetSdk 36).
        // Home: no-op. Drawer: a non-empty prompt clears first, an empty one turns the page home.
        // A back gesture that will close the drawer previews it, like a short drag.
        onBackInvokedDispatcher.registerOnBackInvokedCallback(
            OnBackInvokedDispatcher.PRIORITY_DEFAULT,
            object : OnBackAnimationCallback {
                // The drawer offset the preview started from; null while no preview runs.
                private var previewFrom: Float? = null

                // The bare recents view ("?") came from a swipe down, so Back leaves it straight home.
                private fun closes(): Boolean {
                    val text = appDrawer.prompt.text?.toString().orEmpty()
                    return drawerOpen && (text.isEmpty() || text == RECENTS_PROMPT)
                }

                override fun onBackStarted(backEvent: BackEvent) {
                    previewFrom = if (closes()) beginPageDrag(closeDir()) else null
                }

                override fun onBackProgressed(backEvent: BackEvent) {
                    val from = previewFrom ?: return
                    if (!drawerOpen) return
                    dragPage(closeDir(), from + backEvent.progress * pageHeight() * BACK_PREVIEW_FRACTION)
                    // The drag's own fade starts far past the preview; let home show through faintly.
                    home.alpha = backEvent.progress * BACK_PREVIEW_HOME_ALPHA
                }

                override fun onBackCancelled() {
                    // HOME or the screen going off may have closed the drawer meanwhile.
                    if (previewFrom != null && drawerOpen) settle(open = true)
                    previewFrom = null
                }

                override fun onBackInvoked() {
                    val previewed = previewFrom != null
                    previewFrom = null
                    if (!drawerOpen) return
                    if (closes()) closeDrawer()
                    else {
                        // The prompt changed during the gesture (a hardware key): undo the preview.
                        if (previewed) settle(open = true)
                        appDrawer.prompt.setText("")   // TextWatcher -> applyFilter resets mode to NORMAL
                    }
                }
            },
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

    // ---- turning the page ---------------------------------------------------

    /**
     * Turn to the drawer with [initialPrompt]: "?" opens the recents WITHOUT the keyboard, "" with
     * it. Set even when it looks unneeded: [closeDrawer] clears the old one only once the page is gone.
     */
    private fun openDrawer(initialPrompt: String = "", fromTop: Boolean = false) {
        if (drawerOpen) return
        drawerOpen = true
        // A drag still holding the page must not act on it after this turn.
        root.abortDrag()
        showStatusBar(true)
        dismissDialogs()
        home.animate().cancel()
        drawer.animate().cancel()
        drawer.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        val fromHidden = drawer.visibility != View.VISIBLE
        drawer.visibility = View.VISIBLE
        drawer.alpha = 1f
        // The parent's height, not the drawer's: a drawer that was GONE has not been measured yet.
        // One already showing on this side (dragged, or caught mid-close) slides on from where it is.
        if (fromHidden || fromTop != drawerFromTop) {
            drawerFromTop = fromTop
            drawer.translationY = hiddenY()
        }
        drawer.requestApplyInsets()
        if (appDrawer.prompt.text?.toString() != initialPrompt) appDrawer.setPrompt(initialPrompt)
        else if (initialPrompt.isEmpty()) {
            // A drag re-sorted it before showing it (beginPageDrag).
            if (fromHidden) resortByUsage()
            if (appDrawer.shown.isNotEmpty()) appDrawer.listView.setSelection(0)
        }
        home.setLayerType(View.LAYER_TYPE_NONE, null)  // a drag's layer; withLayer takes over
        drawer.animate().translationY(0f).setDuration(DRAWER_SLIDE_MS).withLayer()
            .setInterpolator(AccelerateDecelerateInterpolator()).start()
        home.animate().alpha(0f).setDuration(HOME_FADE_MS).withLayer()
            .setInterpolator(AccelerateInterpolator()).withEndAction {
                if (drawerOpen) home.visibility = View.INVISIBLE
            }.start()
        // Posted: let the just-shown drawer be laid out once before the prompt asks for the IME.
        if (initialPrompt.isEmpty()) drawer.post { if (drawerOpen) appDrawer.showKeyboard() }
    }

    /** Turn back to home, clearing the prompt; [animate] = false when going to the background anyway. */
    private fun closeDrawer(animate: Boolean = true) {
        if (!drawerOpen) {
            // A finger may be pulling the drawer up: end that drag here, or its release would
            // still open the drawer.
            if (root.isDragging) {
                root.abortDrag()
                if (animate) settle(open = false) else showHomeNow()
            }
            return
        }
        drawerOpen = false
        cleanupTicket++
        root.abortDrag()
        dismissDialogs()
        showStatusBar(false)
        appDrawer.hideKeyboard()
        drawer.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        appDrawer.prompt.clearFocus()
        home.animate().cancel()
        drawer.animate().cancel()
        home.visibility = View.VISIBLE
        if (!animate) {
            clearPromptIfClosed()
            showHomeNow()
            return
        }
        home.setLayerType(View.LAYER_TYPE_NONE, null)  // a drag's layer; withLayer takes over
        home.animate().alpha(1f).setDuration(HOME_FADE_MS).withLayer()
            .setInterpolator(AccelerateInterpolator()).start()
        // The prompt goes with the page: cleared before the slide, the list would switch mid-way.
        drawer.animate().translationY(hiddenY()).setDuration(DRAWER_SLIDE_MS).withLayer()
            .setInterpolator(AccelerateDecelerateInterpolator()).withEndAction {
                if (!drawerOpen) drawer.visibility = View.GONE
                clearPromptIfClosed()
            }.start()
    }

    private fun clearPromptIfClosed() {
        if (!drawerOpen && appDrawer.prompt.text?.isNotEmpty() == true) appDrawer.prompt.setText("")
    }

    /** Most used first changes with every launch, and none re-sorts a drawer out of sight. */
    private fun resortByUsage() {
        if (state.config.byUsage) appDrawer.applyFilter("")
    }

    /** Where the closed drawer waits: above the page for the recents, below it otherwise. */
    private fun hiddenY(): Float = if (drawerFromTop) -pageHeight() else pageHeight()

    /** Back toward the side the drawer came from. */
    private fun closeDir(): SwipeDir = if (drawerFromTop) SwipeDir.UP else SwipeDir.DOWN

    private fun showHomeNow() {
        home.animate().cancel()
        drawer.animate().cancel()
        home.setLayerType(View.LAYER_TYPE_NONE, null)
        home.visibility = View.VISIBLE
        home.alpha = 1f
        drawer.translationY = 0f
        drawer.visibility = View.GONE
    }

    private fun showStatusBar(show: Boolean) {
        val controller = window.insetsController ?: return
        controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (show) controller.show(WindowInsets.Type.statusBars())
        else controller.hide(WindowInsets.Type.statusBars())
    }

    /**
     * A page drag takes over: stop any running turn and return the offset already travelled —
     * non-zero when the drawer is caught mid-turn, so it continues from where it is.
     */
    private fun beginPageDrag(dir: SwipeDir): Float {
        home.animate().cancel()
        drawer.animate().cancel()
        home.visibility = View.VISIBLE
        // A full-screen view group at partial alpha is composed offscreen every frame without a layer.
        home.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        val height = pageHeight()
        dragOpens = !drawerOpen
        if (!dragOpens) return abs(drawer.translationY)
        val fromTop = dir == SwipeDir.DOWN
        // Focus stays blocked: the drawer is not the page in front until the drag commits.
        if (drawer.visibility != View.VISIBLE || fromTop != drawerFromTop) {
            drawerFromTop = fromTop
            drawer.translationY = hiddenY()
        }
        // The recents must be what the pull reveals; a settle back clears the prompt again.
        val prompt = if (fromTop) RECENTS_PROMPT else ""
        if (drawer.visibility != View.VISIBLE) {
            if (appDrawer.prompt.text?.toString() != prompt) appDrawer.setPrompt(prompt)
            else if (prompt.isEmpty()) resortByUsage()
            // The A-to-Z list rises from its top, not where it was last left.
            if (prompt.isEmpty() && appDrawer.shown.isNotEmpty()) appDrawer.listView.setSelection(0)
        } else if (appDrawer.prompt.text?.toString() != prompt) {
            appDrawer.setPrompt(prompt)
        }
        drawer.visibility = View.VISIBLE
        drawer.alpha = 1f
        return height - abs(drawer.translationY)
    }

    /** [offset] runs along the drag: how far it opened, or how far it closed. */
    private fun dragPage(@Suppress("UNUSED_PARAMETER") dir: SwipeDir, offset: Float) {
        if (dragOpens == drawerOpen) return
        val height = pageHeight()
        val open = if (dragOpens) offset else height - offset  // how much of the drawer shows
        drawer.translationY = if (drawerFromTop) open - height else height - open
        home.alpha = SwipeGesture.homeAlpha(open, height)
    }

    private fun endPageDrag(@Suppress("UNUSED_PARAMETER") dir: SwipeDir, end: DragEnd) {
        // Belt and braces next to abortDrag: a drag only ever ends on the page it grabbed.
        if (dragOpens == drawerOpen) return
        when {
            dragOpens && end == DragEnd.COMMIT ->
                openDrawer(if (drawerFromTop) RECENTS_PROMPT else "", fromTop = drawerFromTop)
            end == DragEnd.COMMIT -> closeDrawer()
            dragOpens -> settle(open = false)
            else -> settle(open = true)
        }
    }

    /** A drag that did not commit falls back to the page it started on; nothing else changes. */
    private fun settle(open: Boolean) {
        home.setLayerType(View.LAYER_TYPE_NONE, null)  // the drag's layer; withLayer takes over
        drawer.animate().translationY(if (open) 0f else hiddenY()).setDuration(DRAG_SETTLE_MS).withLayer()
            .setInterpolator(DecelerateInterpolator()).withEndAction {
                if (!drawerOpen) {
                    drawer.visibility = View.GONE
                    // A recents pull that fell back left its "?" behind.
                    clearPromptIfClosed()
                }
            }.start()
        home.animate().alpha(if (open) 0f else 1f).setDuration(DRAG_SETTLE_MS).withLayer()
            .setInterpolator(DecelerateInterpolator()).withEndAction {
                if (drawerOpen) home.visibility = View.INVISIBLE
            }.start()
    }

    /** The window root's height, falling back to the display's before the first layout. */
    private fun pageHeight(): Float =
        ((drawer.parent as? View)?.height?.takeIf { it > 0 }
            ?: resources.displayMetrics.heightPixels).toFloat()

    override fun onResume() {
        super.onResume()
        resumed = true
        // Not on every return home: a full enumeration of every profile would compete with the
        // return animation. needsRefresh covers a load that never landed — no package event retries it.
        val now = SystemClock.uptimeMillis()
        if (appsDirty || state.needsRefresh || now - lastRefresh > REFRESH_STALE_MS) {
            appsDirty = false
            lastRefresh = now
            state.refresh()
        }
        // Re-asserted: an app that showed the bar must not leave it up on the home page.
        showStatusBar(drawerOpen)
        // A resume without a stop (a dialog or translucent activity on top) returns to an open
        // drawer; the recents view deliberately stays without the keyboard.
        if (drawerOpen && appDrawer.prompt.text?.toString() != RECENTS_PROMPT) appDrawer.showKeyboard()
    }

    /** The page stays as it is — a dialog or the shade on top pauses without leaving; [onStop] turns it home. */
    override fun onPause() {
        resumed = false
        // A dialog-like or translucent app never stops this activity: without this the drawer
        // (or the recents) would stay open under it, and re-sort in view on the next refresh.
        if (launchedApp) {
            launchedApp = false
            closeDrawer(animate = false)
        }
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
     * In front: a burst of changes is collected for [CHANGE_SETTLE_MS] into one refresh. Behind
     * another app: only marked dirty, so a store updating 20 apps costs one reload on resume.
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

    /** Leaving the launcher turns the page home instantly, so a return lands on home, not a half-typed search. */
    override fun onStop() {
        started = false
        cleanupTicket++
        // Also a dialog opened from the home page, which closeDrawer does not touch.
        dismissDialogs()
        closeDrawer(animate = false)
        homePage.stopBattery()
        super.onStop()
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
        // A dialog confirmed after HOME would apply an edit the user has visually left behind.
        dismissDialogs()
        cleanupTicket++
        closeDrawer()
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
                Command.RESTART -> if (safReading) toast(getString(R.string.toast_busy)) else recreate()
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
    // A picked file is being read: no run yet, but ~restart would drop its result.
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
                    // Same apps, so the refresh in adopt reports no change: re-render here.
                    appDrawer.refilter()
                    // A dialog opened against the pre-restore config would write stale fields over it.
                    dismissDialogs()
                    toast(getString(R.string.toast_restore_ok, BackupFiles.timeOf(outcome.undoName)), Toast.LENGTH_LONG)
                }
            }
        }
    }

    /** Not before the config is loaded (hard rule 5); the toast says why. */
    private fun editable(): Boolean {
        if (state.configLoaded) return true
        toast(getString(R.string.toast_not_loaded))
        return false
    }

    /** Not while a "~" command runs (hard rule 15), and only once the config is loaded. */
    private fun editPermit(): CommandGate.Idle? {
        val idle = gate.idle()
        if (idle == null) {
            toast(getString(R.string.toast_busy))
            return null
        }
        return idle.takeIf { editable() }
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
        val idle = editPermit() ?: return
        state.toggleTag(idle, key, tag)
        appDrawer.notifyChanged()
    }

    private fun afterToggle() {
        appDrawer.notifyChanged()
        renderHome()
    }

    /** "!!": tap picks a row up, the held row again cancels, any other row drops it there. */
    private fun reorderTap(key: String) {
        when (appDrawer.reorderPick) {
            null -> { appDrawer.reorderPick = key; appDrawer.notifyChanged() }
            key  -> { appDrawer.reorderPick = null; appDrawer.notifyChanged() }
            else -> editPermit()?.let { moveFavorite(it, appDrawer.reorderPick!!, key) }
        }
    }

    private fun moveFavorite(idle: CommandGate.Idle, pickedKey: String, targetKey: String) {
        appDrawer.reorderPick = null
        state.moveFavorite(idle, pickedKey, targetKey)
        appDrawer.refilter()  // re-render in the (new) order
    }

    private fun promptRename(entry: AppEntry) {
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
            val idle = editPermit() ?: return@show
            state.rename(idle, key, entry.systemLabel, name, tags)
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
        runCatching { unregisterReceiver(profilesChanged) }
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

    private fun Int.dp(): Int = dp(this)

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        const val REQ_RESTORE = 1
        const val DRAWER_SLIDE_MS = 180L
        const val HOME_FADE_MS = 200L
        const val DIALOG_TEXT = 0xFFD4D4D4.toInt()
        /** A return home re-enumerates anyway once the last load is this old. */
        const val REFRESH_STALE_MS = 5 * 60 * 1000L
        /** How long package changes in front are collected into one refresh. */
        const val CHANGE_SETTLE_MS = 500L
        /** How long a paused profile's "ready" toast stays expected after the tap. */
        const val PROFILE_WAIT_MS = 2 * 60 * 1000L
        const val DRAG_SETTLE_MS = 220L
        /** How far a back gesture's preview pulls the drawer down, as a fraction of the page. */
        const val BACK_PREVIEW_FRACTION = 0.25f
        const val BACK_PREVIEW_HOME_ALPHA = 0.5f
        const val RECENTS_PROMPT = "?"
    }
}
