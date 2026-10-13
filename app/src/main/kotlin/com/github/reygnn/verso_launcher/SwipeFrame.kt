package com.github.reygnn.verso_launcher

import android.content.Context
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.widget.FrameLayout

/**
 * The root of the window: a FrameLayout stacking the home page and the drawer, which watches
 * every touch for a vertical swipe and turns the page when it sees one.
 *
 * It OBSERVES first and only steals once sure. Children (favorite rows, list rows, the prompt)
 * get the DOWN as usual, so taps and long-presses work untouched; the frame intercepts only
 * when the finger has left the touch slop vertically AND [claims] says the swipe is ours (see
 * [SwipeGesture.claims]: content that can still scroll that way keeps the gesture). The child
 * then receives a CANCEL — which is exactly what the drawer list's tap guard already treats as
 * "no click pending".
 *
 * On blank space (no child took the DOWN) the events arrive in [onTouchEvent] directly; the
 * same tracker runs there, so a swipe over empty wallpaper works just like one over a row.
 *
 * The page flips DURING the move, as soon as the distance is reached, or on release if the
 * gesture was a fast flick. Either way it fires at most once per gesture.
 */
internal class SwipeFrame(context: Context) : FrameLayout(context) {

    /** Asked once per gesture, the moment its direction is known: is this swipe ours? */
    var claims: (SwipeDir) -> Boolean = { false }

    /** Called (once) when a claimed swipe has gone far or fast enough. */
    var onSwipe: (SwipeDir) -> Unit = {}

    /** Asked on every DOWN: should this gesture be kept from the children entirely (taps
     *  and long-presses swallowed)? Used while a page turn is still animating. A swallowed
     *  gesture is still tracked, so it can turn the page like any other swipe. */
    var swallowTaps: () -> Boolean = { false }

    private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val distance = SwipeGesture.DISTANCE_DP * resources.displayMetrics.density
    private val flingVelocity = SwipeGesture.FLING_DP_PER_S * resources.displayMetrics.density

    private var downX = 0f
    private var downY = 0f
    // Still worth watching: false once the gesture turned out sideways or not ours, so the
    // rest of it is left entirely to the children.
    private var tracking = false
    // The direction we claimed, or null while undecided / not ours.
    private var owned: SwipeDir? = null
    private var fired = false
    private var velocity: VelocityTracker? = null
    // This gesture belongs to the frame from its DOWN on (see swallowTaps).
    private var swallowing = false

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) swallowing = swallowTaps()
        track(ev)
        return owned != null || swallowing
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        // Reached either after we intercepted, or because nothing under the finger took the
        // DOWN (blank space). Consume in both cases so the rest of the gesture keeps coming.
        track(ev)
        return true
    }

    private fun track(ev: MotionEvent) {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Can arrive twice (intercept, then onTouchEvent on blank space): a full reset
                // keeps that idempotent.
                downX = ev.x
                downY = ev.y
                tracking = true
                owned = null
                fired = false
                val vt = velocity ?: VelocityTracker.obtain().also { velocity = it }
                vt.clear()
                vt.addMovement(ev)
            }
            MotionEvent.ACTION_MOVE -> {
                velocity?.addMovement(ev)
                if (!tracking) return
                val dy = ev.y - downY
                val dir = owned ?: run {
                    val d = SwipeGesture.direction(ev.x - downX, dy, slop) ?: return  // in slop
                    if (!claims(d)) {
                        tracking = false  // sideways or the content's: hands off for good
                        return
                    }
                    owned = d
                    d
                }
                if (!fired && SwipeGesture.reached(dir, dy, distance)) {
                    fired = true
                    onSwipe(dir)
                }
            }
            // A second finger makes ev.x/ev.y (pointer index 0) jump to another finger once
            // the first lifts, which would read as a huge swipe. One-finger gestures only:
            // abandon tracking (the gesture is still ours if claimed, it just cannot fire).
            MotionEvent.ACTION_POINTER_DOWN -> tracking = false
            MotionEvent.ACTION_UP -> {
                val dir = owned
                if (dir != null && !fired && tracking) {
                    velocity?.let { vt ->
                        vt.addMovement(ev)
                        vt.computeCurrentVelocity(1000)
                        if (SwipeGesture.flung(dir, vt.yVelocity, flingVelocity)) {
                            fired = true
                            onSwipe(dir)
                        }
                    }
                }
                reset()
            }
            MotionEvent.ACTION_CANCEL -> reset()
        }
    }

    private fun reset() {
        tracking = false
        owned = null
        fired = false
        velocity?.clear()
    }

    override fun onDetachedFromWindow() {
        velocity?.recycle()
        velocity = null
        super.onDetachedFromWindow()
    }
}
