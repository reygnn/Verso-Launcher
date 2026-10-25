package com.github.reygnn.verso_launcher

import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.widget.FrameLayout

/**
 * The window root (home page + drawer), watching every touch for the page turn (hard rule 11).
 *
 * In [dispatchTouchEvent], not onInterceptTouchEvent: a scrolling list calls
 * requestDisallowInterceptTouchEvent(true), after which intercept is never called again for
 * that gesture. Children see every event until a drag or flick takes over; then they get an
 * ACTION_CANCEL. [canDrag] is asked on every move, so a list scrolled to its end hands over
 * to the drag in the same gesture.
 */
// Built in code only (PageTurn), never inflated: the host is part of the view.
@SuppressLint("ViewConstructor")
internal class SwipeFrame(context: Context, val host: Host) : FrameLayout(context) {

    /**
     * What the page turn answers and does. No member has a default, so a wiring left out does not
     * compile (PageTurn is the one host; SwipeFrameTest has its own).
     */
    interface Host {
        /** Is this flick ours? Direction and the y of the down. */
        fun claims(dir: SwipeDir, downY: Float): Boolean

        fun onSwipe(dir: SwipeDir)

        /** Toward the side the open drawer came from; null while it is closed. */
        fun closeDirection(): SwipeDir?

        /** Direction and the y of the down. False while the content under the finger can still
         *  scroll that way, or a dialog is open. */
        fun canDrag(dir: SwipeDir, downY: Float): Boolean

        fun pageHeight(): Float

        /** Returns the start offset: non-zero when it grabs a page still moving from an earlier turn. */
        fun onDragStart(dir: SwipeDir): Float

        fun onDrag(dir: SwipeDir, offset: Float)

        fun onDragEnd(dir: SwipeDir, end: DragEnd)
    }

    private val config = ViewConfiguration.get(context)
    private val slop = config.scaledTouchSlop.toFloat()
    private val minDistance = slop * SwipeGesture.SLOP_MULTIPLIER
    private val dragFlingVelocity = config.scaledMinimumFlingVelocity * SwipeGesture.DRAG_FLING_MULTIPLIER

    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var lastY = 0f
    private var watching = false
    private var triggered = false
    private var dragDir: SwipeDir? = null
    private var dragOffset = 0f
    // dragMax, or more when the drag grabbed a page already beyond it.
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
                (velocity ?: VelocityTracker.obtain().also { velocity = it }).apply {
                    clear()
                    addMovement(ev)
                }
            }
            // ev.y (pointer 0) jumps to another finger once the first lifts: a huge fake swipe.
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
                    host.onDrag(dir, dragOffset)
                    return true
                }
                lastY = ev.y
                if (watching && !triggered) {
                    val dx = ev.x - downX
                    val dy = ev.y - downY
                    val drag = SwipeGesture.dragDirection(host.closeDirection(), dx, dy, slop)
                    if (drag != null && host.canDrag(drag, downY)) {
                        triggered = true
                        dragDir = drag
                        cancelChildren(ev)
                        // Start where the page is: the slop travelled is not added, so it does not jump.
                        val start = host.onDragStart(drag).coerceAtLeast(0f)
                        dragLimit = maxOf(dragMax(), start)
                        dragOffset = start
                        host.onDrag(drag, dragOffset)
                        return true
                    }
                    val flick = SwipeGesture.decisive(dx, dy, ev.eventTime - downTime, minDistance)
                    if (flick != null && host.claims(flick, downY)) {
                        triggered = true
                        cancelChildren(ev)
                        host.onSwipe(flick)
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
                        toward = if (dir == SwipeDir.UP) -vt.yVelocity else vt.yVelocity
                    }
                    endDrag(SwipeGesture.dragEnd(dragOffset, host.pageHeight(), toward, dragFlingVelocity))
                    return true
                }
            }
            MotionEvent.ACTION_CANCEL -> endDrag(DragEnd.CANCEL)
        }
        if (triggered) return true
        val consumed = super.dispatchTouchEvent(ev)
        // Claim an unhandled DOWN (blank wallpaper), or the rest of the gesture never comes here.
        return consumed || ev.actionMasked == MotionEvent.ACTION_DOWN
    }

    /** A closing pull stops short of the edge (Unity's cap); the release slides the rest. */
    private fun dragMax(): Float =
        if (host.closeDirection() == null) host.pageHeight() else host.pageHeight() * SwipeGesture.DRAG_MAX_CLOSE_FRACTION

    val isDragging: Boolean get() = dragDir != null

    /** The page was turned some other way (HOME, Back, screen off): no [onDragEnd], which would
     *  act on a page it no longer holds; the rest of the gesture stays swallowed. */
    fun abortDrag() {
        dragDir = null
    }

    private fun endDrag(end: DragEnd) {
        val dir = dragDir ?: return
        dragDir = null
        host.onDragEnd(dir, end)
    }

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
