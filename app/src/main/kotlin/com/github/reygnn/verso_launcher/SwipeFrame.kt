package com.github.reygnn.verso_launcher

import android.content.Context
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout

/**
 * The root of the window: a FrameLayout stacking the home page and the drawer, which watches
 * every touch for a decisive vertical swipe and turns the page when it sees one.
 *
 * Kolibri's pattern (HomeGestureLayout / SwipeDownDismissLayout): the watching happens in
 * [dispatchTouchEvent], NOT in onInterceptTouchEvent. A ListView or ScrollView calls
 * requestDisallowInterceptTouchEvent(true) on its parent the moment it starts scrolling, and
 * from then on onInterceptTouchEvent is never called again for that gesture — exactly the
 * flick that should turn the page would go unseen. dispatchTouchEvent runs on the parent
 * unconditionally.
 *
 * Read-only until decided: children get every event as usual, so taps, long-presses and slow
 * scrolls work untouched. The moment [SwipeGesture.decisive] and [claims] agree, the children
 * get an ACTION_CANCEL (a scrolling list stops cleanly, the drawer's tap guard drops its
 * pending click), [onSwipe] fires once, and the rest of the gesture is the frame's.
 */
internal class SwipeFrame(context: Context) : FrameLayout(context) {

    /** Asked once a decisive swipe is recognised: is it ours? Gets the swipe's direction and
     *  the y its finger went down at. */
    var claims: (SwipeDir, Float) -> Boolean = { _, _ -> false }

    /** Called (once per gesture) for a claimed swipe. */
    var onSwipe: (SwipeDir) -> Unit = {}

    /** Asked on every DOWN: should this gesture be kept from the children entirely (taps
     *  and long-presses swallowed)? Used while a page turn is still animating. A swallowed
     *  gesture is still watched, so it can turn the page like any other swipe. */
    var swallowTaps: () -> Boolean = { false }

    private val minDistance =
        ViewConfiguration.get(context).scaledTouchSlop.toFloat() * SwipeGesture.SLOP_MULTIPLIER

    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    // False once the gesture can no longer be a swipe of ours (a second finger came down).
    private var watching = false
    private var triggered = false
    private var swallowing = false

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                downTime = ev.eventTime
                watching = true
                triggered = false
                swallowing = swallowTaps()
            }
            // A second finger makes ev.x/ev.y (pointer index 0) jump to another finger once the
            // first lifts, which would read as a huge swipe. One-finger gestures only.
            MotionEvent.ACTION_POINTER_DOWN -> watching = false
            MotionEvent.ACTION_MOVE -> if (watching && !triggered) {
                val dir = SwipeGesture.decisive(
                    dx = ev.x - downX,
                    dy = ev.y - downY,
                    dtMs = ev.eventTime - downTime,
                    minDistance = minDistance,
                )
                if (dir != null && claims(dir, downY)) {
                    triggered = true
                    if (!swallowing) cancelChildren(ev)
                    onSwipe(dir)
                    return true
                }
            }
        }
        if (triggered || swallowing) return true
        val consumed = super.dispatchTouchEvent(ev)
        // Claim the DOWN even when no child took it (blank wallpaper, the empty space below a
        // short favorites list): otherwise the rest of the gesture would never be dispatched
        // here and a swipe over empty space could not be seen.
        return consumed || ev.actionMasked == MotionEvent.ACTION_DOWN
    }

    /** Let whichever child holds the gesture (a scrolling list, a pressed row) release it. */
    private fun cancelChildren(source: MotionEvent) {
        val cancel = MotionEvent.obtain(source).apply { action = MotionEvent.ACTION_CANCEL }
        super.dispatchTouchEvent(cancel)
        cancel.recycle()
    }
}
