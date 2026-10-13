package com.github.reygnn.verso_launcher

import android.content.Context
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.widget.FrameLayout

/**
 * The root of the window: a FrameLayout stacking the home page and the drawer, which watches
 * every touch for the two ways of turning the page — a page DRAG that follows the finger, and
 * Kolibri's decisive FLICK.
 *
 * Kolibri's pattern (HomeGestureLayout / SwipeDownDismissLayout): the watching happens in
 * [dispatchTouchEvent], NOT in onInterceptTouchEvent. A ListView or ScrollView calls
 * requestDisallowInterceptTouchEvent(true) on its parent the moment it starts scrolling, and
 * from then on onInterceptTouchEvent is never called again for that gesture — exactly the
 * pull that should move the page would go unseen. dispatchTouchEvent runs on the parent
 * unconditionally.
 *
 * Read-only until decided: children get every event as usual, so taps, long-presses and
 * scrolls work untouched. Two things can take the gesture over, after which the children get
 * an ACTION_CANCEL (a scrolling list stops cleanly, the drawer's tap guard drops its pending
 * click) and the rest of the gesture is the frame's:
 *  - a DRAG ([SwipeGesture.dragDirection]) once [canDrag] says the content under the finger
 *    cannot scroll that way any more — checked on every move, so a list scrolled to its end
 *    hands over to the drag in the same gesture. The page then follows the finger
 *    ([onDrag]) and the release decides ([SwipeGesture.dragEnd], [onDragEnd]);
 *  - a FLICK ([SwipeGesture.decisive] + [claims]) for what is no drag: the recents swipe on
 *    home, and opening the drawer while the favorites can still scroll.
 */
internal class SwipeFrame(context: Context) : FrameLayout(context) {

    /** Asked once a decisive flick is recognised: is it ours? Gets the flick's direction and
     *  the y its finger went down at. */
    var claims: (SwipeDir, Float) -> Boolean = { _, _ -> false }

    /** Called (once per gesture) for a claimed flick. */
    var onSwipe: (SwipeDir) -> Unit = {}

    /** Whether the drawer is the page in front — decides which way a drag may go. */
    var isDrawerOpen: () -> Boolean = { false }

    /** May a drag going this way start now? False while the content under the finger can
     *  still scroll that way, or anything else (a dialog) owns the gesture. */
    var canDrag: (SwipeDir) -> Boolean = { false }

    /** The page height a drag travels over, in px. */
    var pageHeight: () -> Float = { height.toFloat() }

    /** A drag takes over; returns the offset it starts from (non-zero when it grabs a page
     *  that is still moving from an earlier turn). */
    var onDragStart: (SwipeDir) -> Float = { 0f }

    /** The page follows the finger: [Float] is the offset travelled in the drag's direction. */
    var onDrag: (SwipeDir, Float) -> Unit = { _, _ -> }

    /** The finger lifted (or the gesture was cancelled): finish the turn or fall back. */
    var onDragEnd: (SwipeDir, DragEnd) -> Unit = { _, _ -> }

    /** Asked on every DOWN: should this gesture be kept from the children entirely (taps
     *  and long-presses swallowed)? Used while a page turn is still animating. A swallowed
     *  gesture is still watched, so it can turn the page like any other swipe. */
    var swallowTaps: () -> Boolean = { false }

    private val config = ViewConfiguration.get(context)
    private val slop = config.scaledTouchSlop.toFloat()
    private val minDistance = slop * SwipeGesture.SLOP_MULTIPLIER
    private val dragFlingVelocity = config.scaledMinimumFlingVelocity * SwipeGesture.DRAG_FLING_MULTIPLIER

    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var lastY = 0f
    // False once the gesture can no longer be a swipe of ours (a second finger came down).
    private var watching = false
    private var triggered = false
    private var swallowing = false
    private var dragDir: SwipeDir? = null
    private var dragOffset = 0f
    // This gesture's limit: dragMax, or more when the drag grabbed a page that was further
    // along than that (a drawer that had only just started opening).
    private var dragLimit = 0f
    private var velocity: VelocityTracker? = null

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                downTime = ev.eventTime
                lastY = ev.y
                watching = true
                triggered = false
                dragDir = null
                swallowing = swallowTaps()
                (velocity ?: VelocityTracker.obtain().also { velocity = it }).apply {
                    clear()
                    addMovement(ev)
                }
            }
            // A second finger makes ev.x/ev.y (pointer index 0) jump to another finger once the
            // first lifts, which would read as a huge swipe. One-finger gestures only: a drag
            // in progress falls back, and nothing new starts.
            MotionEvent.ACTION_POINTER_DOWN -> {
                watching = false
                endDrag(DragEnd.CANCEL)
            }
            MotionEvent.ACTION_MOVE -> {
                velocity?.addMovement(ev)
                val dir = dragDir
                if (dir != null) {
                    val delta = if (dir == SwipeDir.UP) lastY - ev.y else ev.y - lastY
                    lastY = ev.y
                    dragOffset = SwipeGesture.dragOffset(dragOffset, delta, dragLimit)
                    onDrag(dir, dragOffset)
                    return true
                }
                lastY = ev.y
                if (watching && !triggered) {
                    val dx = ev.x - downX
                    val dy = ev.y - downY
                    val drag = SwipeGesture.dragDirection(isDrawerOpen(), dx, dy, slop)
                    if (drag != null && canDrag(drag)) {
                        triggered = true
                        dragDir = drag
                        if (!swallowing) cancelChildren(ev)
                        // Start where the page is, not where the finger went down: the slop
                        // already travelled is not added, so the page does not jump — not even
                        // when it is caught beyond the usual limit (then that is this limit).
                        val start = onDragStart(drag).coerceAtLeast(0f)
                        dragLimit = maxOf(dragMax(drag), start)
                        dragOffset = start
                        onDrag(drag, dragOffset)
                        return true
                    }
                    val flick = SwipeGesture.decisive(dx, dy, ev.eventTime - downTime, minDistance)
                    if (flick != null && claims(flick, downY)) {
                        triggered = true
                        if (!swallowing) cancelChildren(ev)
                        onSwipe(flick)
                        return true
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                val dir = dragDir
                if (dir != null) {
                    val vt = velocity
                    var toward = 0f
                    if (vt != null) {
                        vt.addMovement(ev)
                        vt.computeCurrentVelocity(1000)
                        // Screen y grows downward: a drag up travels against it.
                        toward = if (dir == SwipeDir.UP) -vt.yVelocity else vt.yVelocity
                    }
                    endDrag(SwipeGesture.dragEnd(dragOffset, pageHeight(), toward, dragFlingVelocity))
                    return true
                }
            }
            MotionEvent.ACTION_CANCEL -> endDrag(DragEnd.CANCEL)
        }
        if (triggered || swallowing) return true
        val consumed = super.dispatchTouchEvent(ev)
        // Claim the DOWN even when no child took it (blank wallpaper, the empty space below a
        // short favorites list): otherwise the rest of the gesture would never be dispatched
        // here and a swipe over empty space could not be seen.
        return consumed || ev.actionMasked == MotionEvent.ACTION_DOWN
    }

    /** Opening follows the finger all the way; a closing pull stops short of the bottom
     *  (Unity's rubber-band cap), the release slides the rest. */
    private fun dragMax(dir: SwipeDir): Float =
        if (dir == SwipeDir.UP) pageHeight() else pageHeight() * SwipeGesture.DRAG_MAX_CLOSE_FRACTION

    /**
     * The page was turned some other way while a drag held it — HOME, Back, the screen going
     * off. The drag is over without a release decision: no [onDragEnd] (it would act on a page
     * that is no longer the one it grabbed), and the rest of the gesture stays swallowed so it
     * cannot start anything new.
     */
    fun abortDrag() {
        dragDir = null
    }

    private fun endDrag(end: DragEnd) {
        val dir = dragDir ?: return
        dragDir = null
        onDragEnd(dir, end)
    }

    /** Let whichever child holds the gesture (a scrolling list, a pressed row) release it. */
    private fun cancelChildren(source: MotionEvent) {
        val cancel = MotionEvent.obtain(source).apply { action = MotionEvent.ACTION_CANCEL }
        super.dispatchTouchEvent(cancel)
        cancel.recycle()
    }

    override fun onDetachedFromWindow() {
        endDrag(DragEnd.CANCEL)
        velocity?.recycle()
        velocity = null
        super.onDetachedFromWindow()
    }
}
