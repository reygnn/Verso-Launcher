package com.github.reygnn.verso_launcher

import android.app.Activity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.window.BackEvent
import android.window.OnBackAnimationCallback

/**
 * The page turn between the home page (recto) and the drawer (verso), hard rule 11: the window
 * root and its gestures, the drag that follows the finger, the settle, the Back preview and the
 * status bar that goes with the page (hard rule 12). The decisions are pure, in [SwipeGesture] for
 * a gesture and in [PageState] for the page; this class keeps the one current state and says what
 * follows from each change, step by step. What a step does to the views is [PageViews]'. Main
 * thread only.
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
    // Which page is up, where the drawer is and what holds it: every change is a transition of
    // PageState, and the views follow.
    private var state = PageState()

    /** Which page is up ([PageState.open]). */
    val isOpen: Boolean get() = state.open

    // The drawer offset a running Back preview started from; it means something while Hold.BACK holds.
    private var backPreviewFrom = 0f

    private val views: PageViews = PageViews(
        activity = activity,
        home = homePage.view,
        drawer = appDrawer.view,
        isOpen = { state.open },
        // Out of sight now, unless a new turn opened it meanwhile: the state says which
        // (PageState.gone), and the view follows. A recents pull that fell back left its "?" behind.
        onSlideEnd = {
            state = state.gone()
            if (!state.showing) views.drawerShowing = false
            clearPromptIfClosed()
        },
    )

    // The top band left to the notification shade, in px: neither a drag nor a flick starts there.
    private val topExclusionPx: Float get() = SwipeGesture.TOP_EXCLUSION_DP * activity.resources.displayMetrics.density

    /** The window root: both pages, and the swipe watched in its dispatchTouchEvent. */
    val root: SwipeFrame = SwipeFrame(activity, object : SwipeFrame.Host {
        override fun claims(dir: SwipeDir, downY: Float): Boolean {
            // A flick down on favorites scrolled down scrolls them back, not to the recents.
            if (dir == SwipeDir.DOWN && !state.open && homePage.scroller.canScrollVertically(-1)) return false
            return SwipeGesture.claims(state.open, dir, downY, topExclusionPx)
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
            else -> downY >= topExclusionPx && !homePage.scroller.canScrollVertically(-1)
        }

        override fun pageHeight(): Float = views.pageHeight

        override fun onDragStart(dir: SwipeDir): Float = beginDrag(dir, Hold.DRAG)

        override fun onDrag(dir: SwipeDir, offset: Float) = drag(offset)

        override fun onDragEnd(dir: SwipeDir, end: DragEnd) = endDrag(end)
    }).apply {
        addView(homePage.view, FrameLayout.LayoutParams(MATCH, MATCH))
        addView(appDrawer.view, FrameLayout.LayoutParams(MATCH, MATCH))
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
            drag(backPreviewFrom + backEvent.progress * views.pageHeight * BACK_PREVIEW_FRACTION)
            // The drag's own fade starts far past the preview; let home show through faintly.
            views.homeAlpha = backEvent.progress * BACK_PREVIEW_HOME_ALPHA
        }

        override fun onBackCancelled() {
            // HOME or the screen going off may have closed the drawer meanwhile: then nothing was held.
            if (endPreview()) {
                views.fallBack(open = true, fromTop = state.fromTop)
                // The mode it missed while it was held.
                modeChanged(appDrawer.mode)
            }
        }

        override fun onBackInvoked() {
            val previewed = endPreview()
            if (!state.open) return
            if (closes()) close()
            else {
                // The prompt changed during the gesture (a hardware key): undo the preview.
                if (previewed) views.fallBack(open = true, fromTop = state.fromTop)
                appDrawer.setPrompt("")
                modeChanged(appDrawer.mode)
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
        views.statusBar(true)
        onOpen()
        views.bringDrawerOn(fromTop, place)
        if (appDrawer.promptText != initialPrompt) appDrawer.setPrompt(initialPrompt)
        else if (initialPrompt.isEmpty()) {
            // A drag re-sorted it before showing it (beginDrag).
            if (fromHidden) resortByUsage()
            appDrawer.scrollToTop()
        }
        views.turn(open = true, fromTop = fromTop)
        // Posted: let the just-shown drawer be laid out once before the prompt asks for the IME.
        if (initialPrompt.isEmpty()) appDrawer.view.post { if (state.open) appDrawer.showKeyboard() }
    }

    /** Turn back to home, clearing the prompt; [animate] = false when going to the background anyway. */
    fun close(animate: Boolean = true) {
        if (!state.open) {
            // A finger may be pulling the drawer up: end that drag here, or its release would
            // still open the drawer.
            if (state.hold != Hold.NONE) {
                state = state.released()
                root.abortDrag()
                if (animate) {
                    views.fallBack(open = false, fromTop = state.fromTop)
                } else {
                    state = state.gone()
                    views.showHomeNow()
                }
            }
            return
        }
        state = state.closed()
        onClose()
        root.abortDrag()
        views.statusBar(false)
        appDrawer.hideKeyboard()
        views.blockDrawerFocus()
        appDrawer.releasePromptFocus()
        views.stopTurn(forDrag = false)
        if (!animate) {
            clearPromptIfClosed()
            state = state.gone()
            views.showHomeNow()
            return
        }
        // The prompt goes with the page (onSlideEnd): cleared before the slide, the list would
        // switch mid-way.
        views.turn(open = false, fromTop = state.fromTop)
    }

    /**
     * Back in front: an app that showed the status bar must not leave it up on the home page.
     * A resume without a stop (a dialog or translucent activity on top) returns to an open
     * drawer; the recents view deliberately stays without the keyboard.
     */
    fun resumed() {
        views.statusBar(state.open)
        if (state.open && appDrawer.promptText !in KEYBOARDLESS) appDrawer.showKeyboard()
    }

    /** The command line changed its mode: out of the recents the drawer changes sides ([PageState.following]). */
    fun modeChanged(mode: Mode) {
        state = state.following(mode)
    }

    private fun clearPromptIfClosed() {
        if (!state.open && appDrawer.promptText.isNotEmpty()) appDrawer.setPrompt("")
    }

    /** Most used first changes with every launch, and none re-sorts a drawer out of sight. */
    private fun resortByUsage() {
        if (byUsage()) appDrawer.rebuildRows()
    }

    /**
     * A page drag takes over: stop any running turn and return the offset already travelled —
     * non-zero when the drawer is caught mid-turn, so it continues from where it is.
     */
    private fun beginDrag(dir: SwipeDir, by: Hold): Float {
        views.stopTurn(forDrag = true)
        val height = views.pageHeight
        if (state.open) {
            state = state.held(by, dir)
            return views.drawerOffset
        }
        val fromTop = dir == SwipeDir.DOWN
        val wasShowing = state.showing
        val place = state.needsPlacing(fromTop)
        // Focus stays blocked: the drawer is not the page in front until the drag commits.
        state = state.held(by, dir)
        if (place) views.place(fromTop)
        // The recents must be what the pull reveals; a settle back clears the prompt again.
        val prompt = if (fromTop) RECENTS_PROMPT else ""
        if (!wasShowing) {
            if (appDrawer.promptText != prompt) appDrawer.setPrompt(prompt)
            else if (prompt.isEmpty()) resortByUsage()
            if (prompt.isEmpty()) appDrawer.scrollToTop()
        } else if (appDrawer.promptText != prompt) {
            appDrawer.setPrompt(prompt)
        }
        views.showDrawer(fromTop)
        return height - views.drawerOffset
    }

    /** [offset] runs along the drag: how far it opened, or how far it closed. */
    private fun drag(offset: Float) {
        // A turn made some other way let go of the page: this drag no longer moves it.
        if (state.hold == Hold.NONE) return
        // How much of the drawer shows.
        val shown = if (state.open) views.pageHeight - offset else offset
        views.dragTo(shown, state.fromTop)
    }

    private fun endDrag(end: DragEnd) {
        // Belt and braces next to abortDrag: a drag only ever ends on the page it grabbed.
        if (state.hold == Hold.NONE) return
        val outcome = state.dragEnded(end)
        state = state.released()
        when (outcome) {
            DragOutcome.OPEN -> open(if (state.fromTop) RECENTS_PROMPT else "", fromTop = state.fromTop)
            DragOutcome.CLOSE -> close()
            DragOutcome.FALL_HOME -> views.fallBack(open = false, fromTop = state.fromTop)
            DragOutcome.FALL_OPEN -> {
                views.fallBack(open = true, fromTop = state.fromTop)
                // The mode it missed while it was held.
                modeChanged(appDrawer.mode)
            }
        }
    }

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        /** How far a back gesture's preview pulls the drawer down, as a fraction of the page. */
        const val BACK_PREVIEW_FRACTION = 0.25f
        const val BACK_PREVIEW_HOME_ALPHA = 0.5f
        const val RECENTS_PROMPT = "?"

        /** The prompts a resume leaves without the keyboard: the recents, and "~verify"'s report. */
        val KEYBOARDLESS = setOf(RECENTS_PROMPT, LauncherLogic.VERIFY)
    }
}
