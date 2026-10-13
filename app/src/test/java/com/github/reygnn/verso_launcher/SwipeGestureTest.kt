package com.github.reygnn.verso_launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for [SwipeGesture] — the pure half of the home <-> drawer swipe. The touch
 * plumbing in [SwipeFrame] only feeds deltas in and acts on these answers, so this is where
 * "tap vs. swipe", "which way", "whose gesture" and "far/fast enough" are pinned.
 */
class SwipeGestureTest {

    private val slop = 10f

    // ---- direction ------------------------------------------------------------

    @Test fun `inside the slop nothing is decided yet`() {
        assertNull(SwipeGesture.direction(0f, 0f, slop))
        assertNull(SwipeGesture.direction(10f, -10f, slop))  // exactly on the slop: still a tap
    }

    @Test fun `screen y grows downward, so a finger moving up is UP`() {
        assertEquals(SwipeDir.UP, SwipeGesture.direction(2f, -30f, slop))
        assertEquals(SwipeDir.DOWN, SwipeGesture.direction(-2f, 30f, slop))
    }

    @Test fun `vertical must dominate, a diagonal leaning sideways is SIDEWAYS`() {
        assertEquals(SwipeDir.SIDEWAYS, SwipeGesture.direction(40f, -30f, slop))
        assertEquals(SwipeDir.SIDEWAYS, SwipeGesture.direction(-30f, 30f, slop))  // a tie is not vertical
        assertEquals(SwipeDir.UP, SwipeGesture.direction(29f, -30f, slop))
    }

    // ---- claims ---------------------------------------------------------------

    @Test fun `home takes an upward swipe once its favorites cannot scroll further`() {
        assertTrue(SwipeGesture.claims(drawerOpen = false, SwipeDir.UP, contentCanScroll = false))
        assertFalse(SwipeGesture.claims(drawerOpen = false, SwipeDir.UP, contentCanScroll = true))
    }

    @Test fun `home takes a downward swipe (recents) once its favorites are at the top`() {
        assertTrue(SwipeGesture.claims(drawerOpen = false, SwipeDir.DOWN, contentCanScroll = false))
        assertFalse(SwipeGesture.claims(drawerOpen = false, SwipeDir.DOWN, contentCanScroll = true))
    }

    @Test fun `home ignores sideways swipes`() {
        assertFalse(SwipeGesture.claims(drawerOpen = false, SwipeDir.SIDEWAYS, contentCanScroll = false))
    }

    @Test fun `the drawer takes a downward swipe only with its list at the top`() {
        assertTrue(SwipeGesture.claims(drawerOpen = true, SwipeDir.DOWN, contentCanScroll = false))
        assertFalse(SwipeGesture.claims(drawerOpen = true, SwipeDir.DOWN, contentCanScroll = true))
    }

    @Test fun `the drawer leaves upward and sideways swipes to its list`() {
        assertFalse(SwipeGesture.claims(drawerOpen = true, SwipeDir.UP, contentCanScroll = false))
        assertFalse(SwipeGesture.claims(drawerOpen = true, SwipeDir.SIDEWAYS, contentCanScroll = false))
    }

    // ---- reached / flung ------------------------------------------------------

    @Test fun `distance counts only in the swipe's own direction`() {
        assertTrue(SwipeGesture.reached(SwipeDir.UP, dy = -64f, distance = 64f))
        assertFalse(SwipeGesture.reached(SwipeDir.UP, dy = -63f, distance = 64f))
        assertFalse(SwipeGesture.reached(SwipeDir.UP, dy = 100f, distance = 64f))  // went back down
        assertTrue(SwipeGesture.reached(SwipeDir.DOWN, dy = 64f, distance = 64f))
        assertFalse(SwipeGesture.reached(SwipeDir.SIDEWAYS, dy = 500f, distance = 64f))
    }

    @Test fun `a fast flick in the swipe's direction fires, one back the other way does not`() {
        assertTrue(SwipeGesture.flung(SwipeDir.UP, velocityY = -900f, minVelocity = 500f))
        assertFalse(SwipeGesture.flung(SwipeDir.UP, velocityY = -400f, minVelocity = 500f))
        assertFalse(SwipeGesture.flung(SwipeDir.UP, velocityY = 900f, minVelocity = 500f))
        assertTrue(SwipeGesture.flung(SwipeDir.DOWN, velocityY = 900f, minVelocity = 500f))
        assertFalse(SwipeGesture.flung(SwipeDir.SIDEWAYS, velocityY = 9000f, minVelocity = 500f))
    }
}
