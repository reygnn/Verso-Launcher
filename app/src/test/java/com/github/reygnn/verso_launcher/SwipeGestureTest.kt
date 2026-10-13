package com.github.reygnn.verso_launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for [SwipeGesture] — the pure half of the home <-> drawer swipe, ported from
 * Kolibri's SwipeGestureAnalyzer. The touch plumbing in [SwipeFrame] only feeds deltas in and
 * acts on these answers, so this is where "flick vs. slow drag", "which way" and "whose
 * gesture" are pinned.
 */
class SwipeGestureTest {

    /** 4 × a 10 px touch slop. */
    private val minDistance = 40f

    // ---- decisive ------------------------------------------------------------

    @Test fun `a fast upward flick is UP, a fast downward one DOWN`() {
        // 200 px in 100 ms = 2 px/ms, well past 1.2.
        assertEquals(SwipeDir.UP, SwipeGesture.decisive(0f, -200f, 100, minDistance))
        assertEquals(SwipeDir.DOWN, SwipeGesture.decisive(0f, 200f, 100, minDistance))
    }

    @Test fun `a slow drag never turns the page, however far it goes`() {
        // 600 px in 1000 ms = 0.6 px/ms: a scroll, not a swipe.
        assertNull(SwipeGesture.decisive(0f, -600f, 1000, minDistance))
        assertNull(SwipeGesture.decisive(0f, 600f, 1000, minDistance))
    }

    @Test fun `the speed threshold is strict, as in Kolibri`() {
        assertNull(SwipeGesture.decisive(0f, -120f, 100, minDistance))   // exactly 1.2 px/ms
        assertEquals(SwipeDir.UP, SwipeGesture.decisive(0f, -121f, 100, minDistance))
    }

    @Test fun `a fast twitch shorter than the distance is not a swipe`() {
        assertNull(SwipeGesture.decisive(0f, -40f, 5, minDistance))       // exactly the distance
        assertEquals(SwipeDir.UP, SwipeGesture.decisive(0f, -41f, 5, minDistance))
    }

    @Test fun `vertical must beat horizontal by the dominance factor`() {
        assertNull(SwipeGesture.decisive(100f, -150f, 50, minDistance))   // exactly 1.5x
        assertEquals(SwipeDir.UP, SwipeGesture.decisive(100f, -151f, 50, minDistance))
        assertNull(SwipeGesture.decisive(300f, -10f, 50, minDistance))    // sideways
    }

    @Test fun `a zero elapsed time does not divide by zero`() {
        assertEquals(SwipeDir.DOWN, SwipeGesture.decisive(0f, 50f, 0, minDistance))
    }

    // ---- claims --------------------------------------------------------------

    private val band = 144f  // 48 dp at 3x

    @Test fun `home takes up (the drawer) from anywhere`() {
        assertTrue(SwipeGesture.claims(drawerOpen = false, SwipeDir.UP, downY = 10f, topExclusionPx = band))
        assertTrue(SwipeGesture.claims(drawerOpen = false, SwipeDir.UP, downY = 1500f, topExclusionPx = band))
    }

    @Test fun `home takes down (the recents) below the top band`() {
        assertTrue(SwipeGesture.claims(drawerOpen = false, SwipeDir.DOWN, downY = 144f, topExclusionPx = band))
        assertTrue(SwipeGesture.claims(drawerOpen = false, SwipeDir.DOWN, downY = 900f, topExclusionPx = band))
    }

    @Test fun `a down that starts in the top band is left to the notification shade`() {
        assertFalse(SwipeGesture.claims(drawerOpen = false, SwipeDir.DOWN, downY = 0f, topExclusionPx = band))
        assertFalse(SwipeGesture.claims(drawerOpen = false, SwipeDir.DOWN, downY = 143.9f, topExclusionPx = band))
    }

    @Test fun `the drawer takes no flick at all - closing it is a drag`() {
        // Unity's Kolibri: a flick down in a scrolled list scrolls it; only the drag closes.
        assertFalse(SwipeGesture.claims(drawerOpen = true, SwipeDir.DOWN, downY = 900f, topExclusionPx = band))
        assertFalse(SwipeGesture.claims(drawerOpen = true, SwipeDir.UP, downY = 900f, topExclusionPx = band))
    }

    // ---- page drag -------------------------------------------------------------

    private val slop = 10f

    @Test fun `home drags up, the drawer drags down - once past the slop`() {
        assertEquals(SwipeDir.UP, SwipeGesture.dragDirection(drawerOpen = false, dx = 0f, dy = -11f, slop = slop))
        assertEquals(SwipeDir.DOWN, SwipeGesture.dragDirection(drawerOpen = true, dx = 0f, dy = 11f, slop = slop))
        assertNull(SwipeGesture.dragDirection(drawerOpen = false, dx = 0f, dy = -10f, slop = slop))  // in the slop
    }

    @Test fun `home does not drag down and the drawer does not drag up`() {
        // Home's down is the recents flick; the drawer's up is a scroll of its list.
        assertNull(SwipeGesture.dragDirection(drawerOpen = false, dx = 0f, dy = 50f, slop = slop))
        assertNull(SwipeGesture.dragDirection(drawerOpen = true, dx = 0f, dy = -50f, slop = slop))
    }

    @Test fun `a drag must be vertical enough`() {
        assertNull(SwipeGesture.dragDirection(drawerOpen = false, dx = 20f, dy = -30f, slop = slop))   // exactly 1.5x
        assertEquals(SwipeDir.UP, SwipeGesture.dragDirection(drawerOpen = false, dx = 20f, dy = -31f, slop = slop))
    }

    @Test fun `the drag offset follows the finger both ways and stays within its range`() {
        assertEquals(30f, SwipeGesture.dragOffset(offset = 10f, delta = 20f, max = 100f))
        assertEquals(5f, SwipeGesture.dragOffset(offset = 10f, delta = -5f, max = 100f))   // pushed back
        assertEquals(0f, SwipeGesture.dragOffset(offset = 10f, delta = -50f, max = 100f))  // not past the start
        assertEquals(100f, SwipeGesture.dragOffset(offset = 90f, delta = 50f, max = 100f)) // not past the cap
    }

    private val height = 1000f
    private val fling = 300f

    @Test fun `without a flick the distance decides at 28 percent`() {
        assertEquals(DragEnd.CANCEL, SwipeGesture.dragEnd(offset = 279f, height = height, velocity = 0f, flingVelocity = fling))
        assertEquals(DragEnd.COMMIT, SwipeGesture.dragEnd(offset = 280f, height = height, velocity = 0f, flingVelocity = fling))
    }

    @Test fun `a flick forward commits however short`() {
        assertEquals(DragEnd.COMMIT, SwipeGesture.dragEnd(offset = 20f, height = height, velocity = 300f, flingVelocity = fling))
        assertEquals(DragEnd.CANCEL, SwipeGesture.dragEnd(offset = 20f, height = height, velocity = 299f, flingVelocity = fling))
    }

    @Test fun `a flick back cancels even past the threshold`() {
        // "Pull far, then flick back" aborts — distance alone must not commit it (Unity's fix).
        assertEquals(DragEnd.CANCEL, SwipeGesture.dragEnd(offset = 700f, height = height, velocity = -300f, flingVelocity = fling))
        assertEquals(DragEnd.COMMIT, SwipeGesture.dragEnd(offset = 700f, height = height, velocity = -299f, flingVelocity = fling))
    }
}
