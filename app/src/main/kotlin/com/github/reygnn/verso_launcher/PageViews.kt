package com.github.reygnn.verso_launcher

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import kotlin.math.abs

/**
 * The two pages as views (hard rules 11 and 12): where the drawer is, how home fades, the status
 * bar that goes with the page, and every duration and curve of a turn. [PageTurn] decides what
 * happens and in which order; this class is what each step does to the views. The order of the
 * calls inside a function was found on devices, and Robolectric models none of it.
 *
 * Thread: main.
 *
 * @param drawer GONE while closed and blocking descendant focus, so the prompt can never raise the
 *   IME on home.
 * @param isOpen which page is up now, asked as home's fade ends: a turn made meanwhile must not
 *   be undone by the end of an older one.
 * @param onSlideEnd the drawer came to rest after sliding away or falling back. Whether it is out
 *   of sight now is the state's to say ([PageState.gone]), and the caller sets [drawerShowing].
 */
internal class PageViews(
    private val activity: Activity,
    private val home: View,
    private val drawer: ViewGroup,
    private val isOpen: () -> Boolean,
    private val onSlideEnd: () -> Unit,
) {
    init {
        // A pane title, for screen readers that announce a page as it turns up. Samsung's TalkBack
        // (A17) speaks the newly focused element instead, which AppDrawer names (hint, list).
        home.accessibilityPaneTitle = activity.getString(R.string.a11y_home)
    }

    /** The window root's height, falling back to the display's before the first layout. */
    val pageHeight: Float
        get() = ((drawer.parent as? View)?.height?.takeIf { it > 0 }
            ?: activity.resources.displayMetrics.heightPixels).toFloat()

    /** How far the drawer is from its place in front, in px: not zero while it turns or is held. */
    val drawerOffset: Float get() = abs(drawer.translationY)

    /** How much of home shows, set by hand where no turn fades it (the Back preview). */
    var homeAlpha: Float
        get() = home.alpha
        set(value) {
            home.alpha = value
        }

    /**
     * Whether the drawer is on screen at all, GONE when not.
     *
     * Invariant: set from [PageState.showing], never read in its place (hard rule 11).
     */
    var drawerShowing: Boolean
        get() = drawer.visibility == View.VISIBLE
        set(value) {
            drawer.visibility = if (value) View.VISIBLE else View.GONE
        }

    /** The status bar goes with the page (hard rule 12): up on the drawer, away on home. */
    fun statusBar(show: Boolean) {
        val controller = activity.window.insetsController ?: return
        controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (show) controller.show(WindowInsets.Type.statusBars())
        else controller.hide(WindowInsets.Type.statusBars())
    }

    /**
     * The drawer becomes the page in front: any running turn stops, it takes the focus, and it is
     * on screen before the slide starts.
     *
     * @param place put it at its hidden place first ([PageState.needsPlacing]). One already
     *   showing on this side (dragged, or caught mid-close) slides on from where it is.
     */
    fun bringDrawerOn(fromTop: Boolean, place: Boolean) {
        home.animate().cancel()
        drawer.animate().cancel()
        drawer.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        showDrawer(fromTop)
        if (place) place(fromTop)
        drawer.requestApplyInsets()
    }

    /** The drawer on screen where it is, with its pane title set before it shows: the recents from the top, all apps from below. */
    fun showDrawer(fromTop: Boolean) {
        drawer.accessibilityPaneTitle =
            activity.getString(if (fromTop) R.string.a11y_pane_recents else R.string.a11y_pane_drawer)
        drawer.visibility = View.VISIBLE
        drawer.alpha = 1f
    }

    /**
     * Where the closed drawer waits: above the page for the recents, below it otherwise.
     *
     * Why: the parent's height, not the drawer's. A drawer that was GONE has not been measured yet.
     */
    fun place(fromTop: Boolean) {
        drawer.translationY = hiddenY(fromTop, pageHeight)
    }

    /**
     * The turn itself: the drawer slides and home fades, to the drawer ([open]) or back home.
     *
     * Why: the drawer's animations take no layer, like the drag that moves it by hand (only home's
     *   fades take one). It only moves, at alpha 1, and translationY is a render-node property,
     *   so each frame replays the rows as they are. A layer would cost a screen-sized texture
     *   per turn and be redrawn whenever the content changes mid-slide, as the keyboard's
     *   padding can.
     */
    fun turn(open: Boolean, fromTop: Boolean) {
        home.setLayerType(View.LAYER_TYPE_NONE, null)  // a drag's layer; withLayer takes over
        if (open) {
            drawer.animate().translationY(0f).setDuration(DRAWER_SLIDE_MS)
                .setInterpolator(AccelerateDecelerateInterpolator()).start()
            home.animate().alpha(0f).setDuration(HOME_FADE_MS).withLayer()
                .setInterpolator(AccelerateInterpolator()).withEndAction {
                    if (isOpen()) home.visibility = View.INVISIBLE
                }.start()
        } else {
            home.animate().alpha(1f).setDuration(HOME_FADE_MS).withLayer()
                .setInterpolator(AccelerateInterpolator()).start()
            drawer.animate().translationY(hiddenY(fromTop, pageHeight)).setDuration(DRAWER_SLIDE_MS)
                .setInterpolator(AccelerateDecelerateInterpolator()).withEndAction(onSlideEnd).start()
        }
    }

    /** A drag that did not commit falls back to the page it started on ([open]: the drawer). */
    fun fallBack(open: Boolean, fromTop: Boolean) {
        home.setLayerType(View.LAYER_TYPE_NONE, null)  // the drag's layer; withLayer takes over
        drawer.animate().translationY(if (open) 0f else hiddenY(fromTop, pageHeight)).setDuration(DRAG_SETTLE_MS)
            .setInterpolator(DecelerateInterpolator()).withEndAction(onSlideEnd).start()
        home.animate().alpha(if (open) 0f else 1f).setDuration(DRAG_SETTLE_MS).withLayer()
            .setInterpolator(DecelerateInterpolator()).withEndAction {
                if (isOpen()) home.visibility = View.INVISIBLE
            }.start()
    }

    /** The drawer is no longer the page in front: nothing in it takes the focus. */
    fun blockDrawerFocus() {
        drawer.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
    }

    /**
     * Stops a running turn where it is, with home on screen under it. Both pages stay as they
     * are, so a turn or a drag that follows goes on from there.
     *
     * @param forDrag a finger takes over, and home gets a layer for it: a full-screen view group
     *   at partial alpha is composed offscreen every frame without one.
     */
    fun stopTurn(forDrag: Boolean) {
        home.animate().cancel()
        drawer.animate().cancel()
        home.visibility = View.VISIBLE
        if (forDrag) home.setLayerType(View.LAYER_TYPE_HARDWARE, null)
    }

    /** The drawer under a finger: [shown] px of it are on the page, and home fades behind it. */
    fun dragTo(shown: Float, fromTop: Boolean) {
        val height = pageHeight
        drawer.translationY = if (fromTop) shown - height else height - shown
        home.alpha = SwipeGesture.homeAlpha(shown, height)
    }

    /** Home at once, with no turn: the window is going to the background anyway. */
    fun showHomeNow() {
        home.animate().cancel()
        drawer.animate().cancel()
        home.setLayerType(View.LAYER_TYPE_NONE, null)
        home.visibility = View.VISIBLE
        home.alpha = 1f
        drawer.translationY = 0f
        drawer.visibility = View.GONE
    }

    private companion object {
        const val DRAWER_SLIDE_MS = 180L
        const val HOME_FADE_MS = 200L
        const val DRAG_SETTLE_MS = 220L
    }
}

/** Where the closed drawer waits on a page of [pageHeight]: above it for the recents, below it otherwise. */
private fun hiddenY(fromTop: Boolean, pageHeight: Float): Float = if (fromTop) -pageHeight else pageHeight
