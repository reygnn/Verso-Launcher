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

    @Test fun `the drawer takes down (close) from anywhere, even the top band`() {
        assertTrue(SwipeGesture.claims(drawerOpen = true, SwipeDir.DOWN, downY = 10f, topExclusionPx = band))
        assertTrue(SwipeGesture.claims(drawerOpen = true, SwipeDir.DOWN, downY = 900f, topExclusionPx = band))
    }

    @Test fun `the drawer leaves up to its list`() {
        assertFalse(SwipeGesture.claims(drawerOpen = true, SwipeDir.UP, downY = 900f, topExclusionPx = band))
    }
}
