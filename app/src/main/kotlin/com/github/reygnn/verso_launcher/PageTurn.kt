package com.github.reygnn.verso_launcher

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.window.BackEvent
import android.window.OnBackAnimationCallback
import kotlin.math.abs

/**
 * The page turn between the home page (recto) and the drawer (verso), hard rule 11: the window
 * root and its gestures, the drag that follows the finger, the settle, the Back preview and the
 * status bar that goes with the page (hard rule 12). The decisions are pure in [SwipeGesture];
 * this class owns the state they act on and the views they move. Main thread only.
 *
 * @param byUsage whether the plain drawer is sorted most used first: then it is re-sorted
 *   before it shows (a launch changes the order, none re-sorts a drawer out of sight).
 * @param dialogOpen a dialog is up: it blocks both drag and flick.
 * @param onOpen the drawer turned up (dialogs opened on home are dismissed).
 * @param onClose the drawer turned away (see MainActivity.turnAway).
 */
internal class PageTurn(
    private val activity: Activity,
    private val homePage: HomePage,
    private val appDrawer: AppDrawer,
    private val byUsage: () -> Boolean,
    private val dialogOpen: () -> Boolean,
    private val onOpen: () -> Unit,
    private val onClose: () -> Unit,
) {
    private val home: View = homePage.view
    // GONE while closed and blocking descendant focus, so the prompt can never raise the IME on home.
    // Its animations take no layer, like the drag that moves it by hand (only home's fades take
    // one): it only moves, at alpha 1, and translationY is a render-node property, so each frame
    // replays the rows as they are. A layer would cost a screen-sized texture per turn and be
    // redrawn whenever the content changes mid-slide, as the keyboard's padding can.
    private val drawer: ViewGroup = appDrawer.view

    // THE source of truth for which page is up. The views' visibility trails it by the
    // length of the page-turn animation, so nothing may read visibility instead.
    var isOpen = false
        private set
    // The recents come down from the top like a HUD and go back up; the drawer rises from below.
    private var drawerFromTop = false
    // Whether the running drag (or Back preview) set out to open the drawer.
    private var dragOpens = false

    init {
        // A pane title, for screen readers that announce a page as it turns up. Samsung's TalkBack
        // (A17) speaks the newly focused element instead, which AppDrawer names (hint, list).
        home.accessibilityPaneTitle = activity.getString(R.string.a11y_home)
    }

    /** The window root: both pages, and the swipe watched in its dispatchTouchEvent. */
    val root: SwipeFrame = SwipeFrame(activity).apply {
        addView(home, FrameLayout.LayoutParams(MATCH, MATCH))
        addView(drawer, FrameLayout.LayoutParams(MATCH, MATCH))
        claims = claims@{ dir, downY ->
            // A long-press that opened a dialog can carry on into a drag with the finger still down.
            if (dialogOpen()) return@claims false
            // A flick down on favorites scrolled down scrolls them back, not to the recents.
            if (dir == SwipeDir.DOWN && !isOpen && homePage.scroller.canScrollVertically(-1)) return@claims false
            SwipeGesture.claims(isOpen, dir, downY, topExclusionPx())
        }
        // Up is normally a drag; the flick opens the drawer when the favorites could still
        // scroll and no drag started.
        onSwipe = { dir ->
            when {
                dir == SwipeDir.UP && !isOpen -> open()
                dir == SwipeDir.DOWN && !isOpen -> openRecents()
            }
        }
        closeDirection = { if (isOpen) closeDir() else null }
        canDrag = canDrag@{ dir, downY ->
            if (dialogOpen()) return@canDrag false
            when {
                isOpen && drawerFromTop -> appDrawer.listAtBottom
                isOpen -> appDrawer.listAtTop
                dir == SwipeDir.UP -> !homePage.scroller.canScrollVertically(1)
                // A pull from the very top is the notification shade's.
                else -> downY >= topExclusionPx() && !homePage.scroller.canScrollVertically(-1)
            }
        }
        pageHeight = ::pageHeight
        onDragStart = ::beginDrag
        onDrag = { _, offset -> drag(offset) }
        onDragEnd = { _, end -> endDrag(end) }
    }

    /**
     * Back must never finish a HOME activity (the default callback would, on targetSdk 36).
     * Home: no-op. Drawer: a non-empty prompt clears first, an empty one turns the page home.
     * A back gesture that will close the drawer previews it, like a short drag.
     */
    val backCallback: OnBackAnimationCallback = object : OnBackAnimationCallback {
        // The drawer offset the preview started from; null while no preview runs.
        private var previewFrom: Float? = null

        // The bare recents view ("?") came from a swipe down, so Back leaves it straight home.
        private fun closes(): Boolean {
            val text = appDrawer.promptText
            return isOpen && (text.isEmpty() || text == RECENTS_PROMPT)
        }

        override fun onBackStarted(backEvent: BackEvent) {
            previewFrom = if (closes()) beginDrag(closeDir()) else null
        }

        override fun onBackProgressed(backEvent: BackEvent) {
            val from = previewFrom ?: return
            if (!isOpen) return
            drag(from + backEvent.progress * pageHeight() * BACK_PREVIEW_FRACTION)
            // The drag's own fade starts far past the preview; let home show through faintly.
            home.alpha = backEvent.progress * BACK_PREVIEW_HOME_ALPHA
        }

        override fun onBackCancelled() {
            // HOME or the screen going off may have closed the drawer meanwhile.
            if (previewFrom != null && isOpen) settle(open = true)
            previewFrom = null
        }

        override fun onBackInvoked() {
            val previewed = previewFrom != null
            previewFrom = null
            if (!isOpen) return
            if (closes()) close()
            else {
                // The prompt changed during the gesture (a hardware key): undo the preview.
                if (previewed) settle(open = true)
                appDrawer.clearPrompt()
            }
        }
    }

    /** The recents, down from the top: the swipe down, and TalkBack's action for it. */
    fun openRecents() = open(RECENTS_PROMPT, fromTop = true)

    /**
     * Turn to the drawer with [initialPrompt]: "?" opens the recents WITHOUT the keyboard, "" with
     * it. Set even when it looks unneeded: [close] clears the old one only once the page is gone.
     */
    fun open(initialPrompt: String = "", fromTop: Boolean = false) {
        if (isOpen) return
        isOpen = true
        // A drag still holding the page must not act on it after this turn.
        root.abortDrag()
        showStatusBar(true)
        onOpen()
        home.animate().cancel()
        drawer.animate().cancel()
        drawer.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        val fromHidden = drawer.visibility != View.VISIBLE
        titleDrawer(fromTop)
        drawer.visibility = View.VISIBLE
        drawer.alpha = 1f
        // The parent's height, not the drawer's: a drawer that was GONE has not been measured yet.
        // One already showing on this side (dragged, or caught mid-close) slides on from where it is.
        if (fromHidden || fromTop != drawerFromTop) {
            drawerFromTop = fromTop
            drawer.translationY = hiddenY()
        }
        drawer.requestApplyInsets()
        if (appDrawer.promptText != initialPrompt) appDrawer.setPrompt(initialPrompt)
        else if (initialPrompt.isEmpty()) {
            // A drag re-sorted it before showing it (beginDrag).
            if (fromHidden) resortByUsage()
            appDrawer.scrollToTop()
        }
        home.setLayerType(View.LAYER_TYPE_NONE, null)  // a drag's layer; withLayer takes over
        drawer.animate().translationY(0f).setDuration(DRAWER_SLIDE_MS)
            .setInterpolator(AccelerateDecelerateInterpolator()).start()
        home.animate().alpha(0f).setDuration(HOME_FADE_MS).withLayer()
            .setInterpolator(AccelerateInterpolator()).withEndAction {
                if (isOpen) home.visibility = View.INVISIBLE
            }.start()
        // Posted: let the just-shown drawer be laid out once before the prompt asks for the IME.
        if (initialPrompt.isEmpty()) drawer.post { if (isOpen) appDrawer.showKeyboard() }
    }

    /** Turn back to home, clearing the prompt; [animate] = false when going to the background anyway. */
    fun close(animate: Boolean = true) {
        if (!isOpen) {
            // A finger may be pulling the drawer up: end that drag here, or its release would
            // still open the drawer.
            if (root.isDragging) {
                root.abortDrag()
                if (animate) settle(open = false) else showHomeNow()
            }
            return
        }
        isOpen = false
        onClose()
        root.abortDrag()
        showStatusBar(false)
        appDrawer.hideKeyboard()
        drawer.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        appDrawer.releasePromptFocus()
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
        drawer.animate().translationY(hiddenY()).setDuration(DRAWER_SLIDE_MS)
            .setInterpolator(AccelerateDecelerateInterpolator()).withEndAction {
                if (!isOpen) drawer.visibility = View.GONE
                clearPromptIfClosed()
            }.start()
    }

    /**
     * Back in front: an app that showed the status bar must not leave it up on the home page.
     * A resume without a stop (a dialog or translucent activity on top) returns to an open
     * drawer; the recents view deliberately stays without the keyboard.
     */
    fun resumed() {
        showStatusBar(isOpen)
        if (isOpen && appDrawer.promptText != RECENTS_PROMPT) appDrawer.showKeyboard()
    }

    /** Out of the recents (× or typing) it is the plain drawer: it goes back down like one. */
    fun modeChanged(mode: Mode) {
        if (isOpen && drawerFromTop && mode != Mode.RECENTS && !root.isDragging) drawerFromTop = false
    }

    private fun clearPromptIfClosed() {
        if (!isOpen && appDrawer.promptText.isNotEmpty()) appDrawer.clearPrompt()
    }

    /** Most used first changes with every launch, and none re-sorts a drawer out of sight. */
    private fun resortByUsage() {
        if (byUsage()) appDrawer.rebuildRows()
    }

    /** Where the closed drawer waits: above the page for the recents, below it otherwise. */
    private fun hiddenY(): Float = if (drawerFromTop) -pageHeight() else pageHeight()

    /** Its pane title, set before it shows: the recents from the top, all apps from below. */
    private fun titleDrawer(fromTop: Boolean) {
        drawer.accessibilityPaneTitle =
            activity.getString(if (fromTop) R.string.a11y_pane_recents else R.string.a11y_pane_drawer)
    }

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
        val controller = activity.window.insetsController ?: return
        controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (show) controller.show(WindowInsets.Type.statusBars())
        else controller.hide(WindowInsets.Type.statusBars())
    }

    /**
     * A page drag takes over: stop any running turn and return the offset already travelled —
     * non-zero when the drawer is caught mid-turn, so it continues from where it is.
     */
    private fun beginDrag(dir: SwipeDir): Float {
        home.animate().cancel()
        drawer.animate().cancel()
        home.visibility = View.VISIBLE
        // A full-screen view group at partial alpha is composed offscreen every frame without a layer.
        home.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        val height = pageHeight()
        dragOpens = !isOpen
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
            if (appDrawer.promptText != prompt) appDrawer.setPrompt(prompt)
            else if (prompt.isEmpty()) resortByUsage()
            if (prompt.isEmpty()) appDrawer.scrollToTop()
        } else if (appDrawer.promptText != prompt) {
            appDrawer.setPrompt(prompt)
        }
        titleDrawer(fromTop)
        drawer.visibility = View.VISIBLE
        drawer.alpha = 1f
        return height - abs(drawer.translationY)
    }

    /** [offset] runs along the drag: how far it opened, or how far it closed. */
    private fun drag(offset: Float) {
        if (dragOpens == isOpen) return
        val height = pageHeight()
        val open = if (dragOpens) offset else height - offset  // how much of the drawer shows
        drawer.translationY = if (drawerFromTop) open - height else height - open
        home.alpha = SwipeGesture.homeAlpha(open, height)
    }

    private fun endDrag(end: DragEnd) {
        // Belt and braces next to abortDrag: a drag only ever ends on the page it grabbed.
        if (dragOpens == isOpen) return
        when {
            dragOpens && end == DragEnd.COMMIT ->
                open(if (drawerFromTop) RECENTS_PROMPT else "", fromTop = drawerFromTop)
            end == DragEnd.COMMIT -> close()
            dragOpens -> settle(open = false)
            else -> settle(open = true)
        }
    }

    /** A drag that did not commit falls back to the page it started on; nothing else changes. */
    private fun settle(open: Boolean) {
        home.setLayerType(View.LAYER_TYPE_NONE, null)  // the drag's layer; withLayer takes over
        drawer.animate().translationY(if (open) 0f else hiddenY()).setDuration(DRAG_SETTLE_MS)
            .setInterpolator(DecelerateInterpolator()).withEndAction {
                if (!isOpen) {
                    drawer.visibility = View.GONE
                    // A recents pull that fell back left its "?" behind.
                    clearPromptIfClosed()
                }
            }.start()
        home.animate().alpha(if (open) 0f else 1f).setDuration(DRAG_SETTLE_MS).withLayer()
            .setInterpolator(DecelerateInterpolator()).withEndAction {
                if (isOpen) home.visibility = View.INVISIBLE
            }.start()
    }

    /** The top band left to the notification shade, in px: neither a drag nor a flick starts there. */
    private fun topExclusionPx(): Float = SwipeGesture.TOP_EXCLUSION_DP * activity.resources.displayMetrics.density

    /** The window root's height, falling back to the display's before the first layout. */
    private fun pageHeight(): Float =
        ((drawer.parent as? View)?.height?.takeIf { it > 0 }
            ?: activity.resources.displayMetrics.heightPixels).toFloat()

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val DRAWER_SLIDE_MS = 180L
        const val HOME_FADE_MS = 200L
        const val DRAG_SETTLE_MS = 220L
        /** How far a back gesture's preview pulls the drawer down, as a fraction of the page. */
        const val BACK_PREVIEW_FRACTION = 0.25f
        const val BACK_PREVIEW_HOME_ALPHA = 0.5f
        const val RECENTS_PROMPT = "?"
    }
}
