package com.github.reygnn.verso_launcher

import android.app.Activity
import android.view.MotionEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/** A flipped sign here would make every flick forward cancel and every flick back commit, while
 *  all of [SwipeGestureTest] stays green. */

@RunWith(RobolectricTestRunner::class)
class SwipeFrameTest {

    private val activity: Activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    private val frame = SwipeFrame(activity)

    private var drawerOpen = false
    private var dragAllowed = true
    private var startOffset = 0f
    private val drags = mutableListOf<Pair<SwipeDir, Float>>()
    private val ends = mutableListOf<Pair<SwipeDir, DragEnd>>()
    private val flicks = mutableListOf<SwipeDir>()

    init {
        frame.isDrawerOpen = { drawerOpen }
        frame.canDrag = { dragAllowed }
        frame.pageHeight = { HEIGHT }
        frame.onDragStart = { startOffset }
        frame.onDrag = { dir, offset -> drags += dir to offset }
        frame.onDragEnd = { dir, end -> ends += dir to end }
        frame.claims = { _, _ -> true }
        frame.onSwipe = { flicks += it }
    }

    private var downTime = 0L

    private fun send(action: Int, y: Float, t: Long) {
        if (action == MotionEvent.ACTION_DOWN) downTime = t
        val ev = MotionEvent.obtain(downTime, t, action, 500f, y, 0)
        frame.dispatchTouchEvent(ev)
        ev.recycle()
    }

    /** A finger from [from] to [to] in [steps] moves over [durationMs], then lifted (or not). */
    private fun stroke(from: Float, to: Float, durationMs: Long, steps: Int = 20, lift: Boolean = true) {
        send(MotionEvent.ACTION_DOWN, from, 1000)
        for (i in 1..steps) send(MotionEvent.ACTION_MOVE, from + (to - from) * i / steps, 1000 + durationMs * i / steps)
        if (lift) send(MotionEvent.ACTION_UP, to, 1000 + durationMs)
    }

    @Test fun `a slow pull down past the threshold closes the drawer`() {
        drawerOpen = true
        stroke(from = 100f, to = 500f, durationMs = 4000)  // 40 % of 1000, 0.1 px/ms
        assertEquals(listOf(SwipeDir.DOWN to DragEnd.COMMIT), ends)
        assertTrue(drags.all { it.first == SwipeDir.DOWN })
        assertTrue("the page follows the finger down", drags.last().second > drags.first().second)
    }

    @Test fun `a closing pull stops at 80 percent of the page`() {
        drawerOpen = true
        stroke(from = 100f, to = 1500f, durationMs = 6000, lift = false)  // far past the page
        assertEquals(HEIGHT * SwipeGesture.DRAG_MAX_CLOSE_FRACTION, drags.last().second, 0.001f)
    }

    @Test fun `an opening pull may follow the finger the whole page`() {
        stroke(from = 1900f, to = 100f, durationMs = 6000, lift = false)
        assertEquals(HEIGHT, drags.last().second, 0.001f)
    }

    @Test fun `a slow short pull down settles back`() {
        drawerOpen = true
        stroke(from = 100f, to = 250f, durationMs = 3000)  // 15 %
        assertEquals(listOf(SwipeDir.DOWN to DragEnd.CANCEL), ends)
    }

    @Test fun `pulling home up follows the finger upward and opens past the threshold`() {
        stroke(from = 900f, to = 500f, durationMs = 4000)
        assertEquals(listOf(SwipeDir.UP to DragEnd.COMMIT), ends)
        assertTrue("offset grows as the finger moves up", drags.last().second > drags.first().second)
    }

    @Test fun `a quick short flick forward commits`() {
        drawerOpen = true
        stroke(from = 100f, to = 180f, durationMs = 40, steps = 4)
        assertEquals(listOf(SwipeDir.DOWN to DragEnd.COMMIT), ends)
    }

    @Test fun `pulling far and then flicking back cancels`() {
        drawerOpen = true
        send(MotionEvent.ACTION_DOWN, 100f, 1000)
        for (i in 1..20) send(MotionEvent.ACTION_MOVE, 100f + 30f * i, 1000L + 200L * i)  // to 700, slowly
        for (i in 1..4) send(MotionEvent.ACTION_MOVE, 700f - 60f * i, 5000L + 10L * i)    // fast back up
        send(MotionEvent.ACTION_UP, 460f, 5040)
        assertEquals(listOf(SwipeDir.DOWN to DragEnd.CANCEL), ends)
    }

    @Test fun `a second finger cancels the drag`() {
        drawerOpen = true
        stroke(from = 100f, to = 600f, durationMs = 2000, lift = false)
        send(MotionEvent.ACTION_POINTER_DOWN, 600f, 3100)
        send(MotionEvent.ACTION_UP, 600f, 3200)
        assertEquals(listOf(SwipeDir.DOWN to DragEnd.CANCEL), ends)
    }

    @Test fun `an aborted drag ends without a decision and swallows the rest`() {
        drawerOpen = true
        stroke(from = 100f, to = 600f, durationMs = 2000, lift = false)
        frame.abortDrag()  // HOME pressed meanwhile
        val dragsSoFar = drags.size
        send(MotionEvent.ACTION_MOVE, 650f, 3100)
        send(MotionEvent.ACTION_UP, 650f, 3200)
        assertEquals(emptyList<Pair<SwipeDir, DragEnd>>(), ends)
        assertEquals(dragsSoFar, drags.size)
        assertEquals(emptyList<SwipeDir>(), flicks)
    }

    @Test fun `a page caught beyond the close limit continues from where it is`() {
        drawerOpen = true
        startOffset = 950f  // the drawer had only just started opening
        stroke(from = 100f, to = 120f, durationMs = 400, steps = 4, lift = false)
        assertEquals(950f, drags.first().second, 0.001f)
    }

    @Test fun `when no drag may start, a fast flick up still opens the drawer`() {
        dragAllowed = false  // the favorites can still scroll up
        stroke(from = 900f, to = 600f, durationMs = 100, steps = 5)
        assertEquals(listOf(SwipeDir.UP), flicks)
        assertEquals(emptyList<Pair<SwipeDir, DragEnd>>(), ends)
    }

    @Test fun `a drag wins over the flick it would also be`() {
        stroke(from = 900f, to = 400f, durationMs = 100, steps = 5)
        assertEquals(emptyList<SwipeDir>(), flicks)
        assertEquals(listOf(SwipeDir.UP to DragEnd.COMMIT), ends)
    }

    private companion object {
        const val HEIGHT = 1000f
    }
}
