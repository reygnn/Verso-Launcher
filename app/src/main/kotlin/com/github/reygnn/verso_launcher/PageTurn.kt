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
 * status bar that goes with the page (hard rule 12). The decisions are pure, in [SwipeGesture] for
 * a gesture and in [PageState] for the page; this class keeps the one current state and moves the
 * views after it. Main thread only.
 *
 * @param byUsage whether the plain drawer is sorted most used first: then it is re-sorted
 *   before it shows (a launch changes the order, none re-sorts a drawer out of sight).
 * @param onOpen the drawer turned up. Nothing in the window needs it today (no dialog is left to
 *   dismiss); PageTurnTest counts the turns through it and [onClose].
 * @param onClose the drawer turned away.
 */
internal class PageTurn(
    private val activity: Activity,
    private val homePage: HomePage,
    private val appDrawer: AppDrawer,
    private val byUsage: () -> Boolean,
    private val onOpen: () -> Unit = {},
    private val onClose: () -> Unit = {},
) {
    private val home: View = homePage.view
    // GONE while closed and blocking descendant focus, so the prompt can never raise the IME on home.
    // Its animations take no layer, like the drag that moves it by hand (only home's fades take
    // one): it only moves, at alpha 1, and translationY is a render-node property, so each frame
    // replays the rows as they are. A layer would cost a screen-sized texture per turn and be
    // redrawn whenever the content changes mid-slide, as the keyboard's padding can.
    private val drawer: ViewGroup = appDrawer.view

    // Which page is up, where the drawer is and what holds it: every change is a transition of
    // PageState, and the views follow.
    private var state = PageState()

    /** Which page is up ([PageState.open]). */
    val isOpen: Boolean get() = state.open

    // The drawer offset a running Back preview started from; it means something while Hold.BACK holds.
    private var backPreviewFrom = 0f

    init {
        // A pane title, for screen readers that announce a page as it turns up. Samsung's TalkBack
        // (A17) speaks the newly focused element instead, which AppDrawer names (hint, list).
        home.accessibilityPaneTitle = activity.getString(R.string.a11y_home)
    }

    /** The window root: both pages, and the swipe watched in its dispatchTouchEvent. */
    val root: SwipeFrame = SwipeFrame(activity, object : SwipeFrame.Host {
        override fun claims(dir: SwipeDir, downY: Float): Boolean {
            // A flick down on favorites scrolled down scrolls them back, not to the recents.
            if (dir == SwipeDir.DOWN && !state.open && homePage.scroller.canScrollVertically(-1)) return false
            return SwipeGesture.claims(state.open, dir, downY, topExclusionPx())
        }

        // Up is normally a drag; the flick opens the drawer when the favorites could still
        // scroll and no drag started.
        override fun onSwipe(dir: SwipeDir) {
            when {
                dir == SwipeDir.UP && !state.open -> open()
                dir == SwipeDir.DOWN && !state.open -> openRecents()
            }
        }

        override fun closeDirection(): SwipeDir? = if (state.open) state.closeDir else null

        override fun canDrag(dir: SwipeDir, downY: Float): Boolean = when {
            state.open && state.fromTop -> appDrawer.listAtBottom
            state.open -> appDrawer.listAtTop
            dir == SwipeDir.UP -> !homePage.scroller.canScrollVertically(1)
            // A pull from the very top is the notification shade's.
            else -> downY >= topExclusionPx() && !homePage.scroller.canScrollVertically(-1)
        }

        override fun pageHeight(): Float = this@PageTurn.pageHeight()

        override fun onDragStart(dir: SwipeDir): Float = beginDrag(dir, Hold.DRAG)

        override fun onDrag(dir: SwipeDir, offset: Float) = drag(offset)

        override fun onDragEnd(dir: SwipeDir, end: DragEnd) = endDrag(end)
    }).apply {
        addView(home, FrameLayout.LayoutParams(MATCH, MATCH))
        addView(drawer, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    /**
     * Back must never finish a HOME activity (the default callback would, on targetSdk 36).
     * Home: no-op. Drawer: a non-empty prompt clears first, an empty one turns the page home.
     * A back gesture that will close the drawer previews it, like a short drag.
     */
    val backCallback: OnBackAnimationCallback = object : OnBackAnimationCallback {
        // The bare recents view ("?") came from a swipe down, so Back leaves it straight home.
        private fun closes(): Boolean {
            val text = appDrawer.promptText
            return state.open && (text.isEmpty() || text == RECENTS_PROMPT)
        }

        /** Lets go of a preview, if one holds the page; true if it did. */
        private fun endPreview(): Boolean {
            val previewed = state.hold == Hold.BACK
            if (previewed) state = state.released()
            return previewed
        }

        override fun onBackStarted(backEvent: BackEvent) {
            if (closes()) backPreviewFrom = beginDrag(state.closeDir, Hold.BACK) else endPreview()
        }

        override fun onBackProgressed(backEvent: BackEvent) {
            // A turn made meanwhile (HOME, the screen going off) let go of the page.
            if (state.hold != Hold.BACK) return
            drag(backPreviewFrom + backEvent.progress * pageHeight() * BACK_PREVIEW_FRACTION)
            // The drag's own fade starts far past the preview; let home show through faintly.
            home.alpha = backEvent.progress * BACK_PREVIEW_HOME_ALPHA
        }

        override fun onBackCancelled() {
            // HOME or the screen going off may have closed the drawer meanwhile: then nothing was held.
            if (endPreview()) {
                settle(open = true)
                followMode()
            }
        }

        override fun onBackInvoked() {
            val previewed = endPreview()
            if (!state.open) return
            if (closes()) close()
            else {
                // The prompt changed during the gesture (a hardware key): undo the preview.
                if (previewed) settle(open = true)
                appDrawer.setPrompt("")
                followMode()
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
        if (state.open) return
        val fromHidden = !state.showing
        val place = state.needsPlacing(fromTop)
        // A drag still holding the page must not act on it after this turn: the state lets go of
        // it, and the frame is told.
        state = state.opened(fromTop)
        root.abortDrag()
        showStatusBar(true)
        onOpen()
        home.animate().cancel()
        drawer.animate().cancel()
        drawer.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        titleDrawer(fromTop)
        drawer.visibility = View.VISIBLE
        drawer.alpha = 1f
        // The parent's height, not the drawer's: a drawer that was GONE has not been measured yet.
        // One already showing on this side (dragged, or caught mid-close) slides on from where it is.
        if (place) drawer.translationY = hiddenY()
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
                if (state.open) home.visibility = View.INVISIBLE
            }.start()
        // Posted: let the just-shown drawer be laid out once before the prompt asks for the IME.
        if (initialPrompt.isEmpty()) drawer.post { if (state.open) appDrawer.showKeyboard() }
    }

    /** Turn back to home, clearing the prompt; [animate] = false when going to the background anyway. */
    fun close(animate: Boolean = true) {
        if (!state.open) {
            // A finger may be pulling the drawer up: end that drag here, or its release would
            // still open the drawer.
            if (state.hold != Hold.NONE) {
                state = state.released()
                root.abortDrag()
                if (animate) settle(open = false) else showHomeNow()
            }
            return
        }
        state = state.closed()
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
                hideDrawerIfClosed()
                clearPromptIfClosed()
            }.start()
    }

    /**
     * Back in front: an app that showed the status bar must not leave it up on the home page.
     * A resume without a stop (a dialog or translucent activity on top) returns to an open
     * drawer; the recents view deliberately stays without the keyboard.
     */
    fun resumed() {
        showStatusBar(state.open)
        if (state.open && appDrawer.promptText !in KEYBOARDLESS) appDrawer.showKeyboard()
    }

    /** The command line changed its mode: out of the recents the drawer changes sides ([PageState.following]). */
    fun modeChanged(mode: Mode) {
        state = state.following(mode)
    }

    /** After a hold that ended without closing the drawer: it follows the mode it missed meanwhile. */
    private fun followMode() = modeChanged(appDrawer.mode)

    /** The slide away ended: the drawer is out of sight, unless a new turn opened it meanwhile. */
    private fun hideDrawerIfClosed() {
        if (state.open) return
        state = state.gone()
        drawer.visibility = View.GONE
    }

    private fun clearPromptIfClosed() {
        if (!state.open && appDrawer.promptText.isNotEmpty()) appDrawer.setPrompt("")
    }

    /** Most used first changes with every launch, and none re-sorts a drawer out of sight. */
    private fun resortByUsage() {
        if (byUsage()) appDrawer.rebuildRows()
    }

    /** Where the closed drawer waits: above the page for the recents, below it otherwise. */
    private fun hiddenY(): Float = if (state.fromTop) -pageHeight() else pageHeight()

    /** Its pane title, set before it shows: the recents from the top, all apps from below. */
    private fun titleDrawer(fromTop: Boolean) {
        drawer.accessibilityPaneTitle =
            activity.getString(if (fromTop) R.string.a11y_pane_recents else R.string.a11y_pane_drawer)
    }

    private fun showHomeNow() {
        home.animate().cancel()
        drawer.animate().cancel()
        home.setLayerType(View.LAYER_TYPE_NONE, null)
        home.visibility = View.VISIBLE
        home.alpha = 1f
        drawer.translationY = 0f
        state = state.gone()
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
    private fun beginDrag(dir: SwipeDir, by: Hold): Float {
        home.animate().cancel()
        drawer.animate().cancel()
        home.visibility = View.VISIBLE
        // A full-screen view group at partial alpha is composed offscreen every frame without a layer.
        home.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        val height = pageHeight()
        if (state.open) {
            state = state.held(by, dir)
            return abs(drawer.translationY)
        }
        val fromTop = dir == SwipeDir.DOWN
        val wasShowing = state.showing
        val place = state.needsPlacing(fromTop)
        // Focus stays blocked: the drawer is not the page in front until the drag commits.
        state = state.held(by, dir)
        if (place) drawer.translationY = hiddenY()
        // The recents must be what the pull reveals; a settle back clears the prompt again.
        val prompt = if (fromTop) RECENTS_PROMPT else ""
        if (!wasShowing) {
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
        // A turn made some other way let go of the page: this drag no longer moves it.
        if (state.hold == Hold.NONE) return
        val height = pageHeight()
        val open = if (state.open) height - offset else offset  // how much of the drawer shows
        drawer.translationY = if (state.fromTop) open - height else height - open
        home.alpha = SwipeGesture.homeAlpha(open, height)
    }

    private fun endDrag(end: DragEnd) {
        // Belt and braces next to abortDrag: a drag only ever ends on the page it grabbed.
        if (state.hold == Hold.NONE) return
        val outcome = state.dragEnded(end)
        state = state.released()
        when (outcome) {
            DragOutcome.OPEN -> open(if (state.fromTop) RECENTS_PROMPT else "", fromTop = state.fromTop)
            DragOutcome.CLOSE -> close()
            DragOutcome.FALL_HOME -> settle(open = false)
            DragOutcome.FALL_OPEN -> {
                settle(open = true)
                followMode()
            }
        }
    }

    /** A drag that did not commit falls back to the page it started on; nothing else changes. */
    private fun settle(open: Boolean) {
        home.setLayerType(View.LAYER_TYPE_NONE, null)  // the drag's layer; withLayer takes over
        drawer.animate().translationY(if (open) 0f else hiddenY()).setDuration(DRAG_SETTLE_MS)
            .setInterpolator(DecelerateInterpolator()).withEndAction {
                hideDrawerIfClosed()
                // A recents pull that fell back left its "?" behind.
                clearPromptIfClosed()
            }.start()
        home.animate().alpha(if (open) 0f else 1f).setDuration(DRAG_SETTLE_MS).withLayer()
            .setInterpolator(DecelerateInterpolator()).withEndAction {
                if (state.open) home.visibility = View.INVISIBLE
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

        /** The prompts a resume leaves without the keyboard: the recents, and "~verify"'s report. */
        val KEYBOARDLESS = setOf(RECENTS_PROMPT, LauncherLogic.VERIFY)
    }
}
