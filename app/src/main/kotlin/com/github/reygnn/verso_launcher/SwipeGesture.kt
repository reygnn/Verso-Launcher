package com.github.reygnn.verso_launcher

import kotlin.math.abs

/** Which way a decisive vertical swipe went. */
internal enum class SwipeDir { UP, DOWN }

/**
 * The pure decisions behind the home <-> drawer swipe, lifted out of [SwipeFrame] the same
 * way the prompt logic lives in [LauncherLogic]: so "is this a decisive swipe, which way, is it
 * ours" is a JVM-testable mapping instead of arithmetic buried in a touch handler. [SwipeFrame]
 * only feeds it MotionEvent deltas and acts on the answers.
 *
 * Ported from Kolibri (SwipeGestureAnalyzer + GestureThresholds), whose calibration was
 * validated on real devices: a swipe counts once it has travelled [SLOP_MULTIPLIER] touch
 * slops, at an AVERAGE speed of at least [VELOCITY_PX_PER_MS] since the finger went down,
 * along an axis that beats the other by [DOMINANCE]. It is decided DURING the move — the page
 * turns while the finger is still travelling, not on release. Velocity, not scroll position,
 * separates the two intents: a slow drag never turns the page (it scrolls whatever is under
 * the finger), a decisive flick always does, wherever the list happens to stand.
 *
 * Units: distances in pixels, velocities in pixels per millisecond (unit-less across
 * densities, like Kolibri's). The frame scales the touch slop and [TOP_EXCLUSION_DP] itself.
 */
internal object SwipeGesture {

    /** Minimum travel, as a multiple of the system touch slop. */
    const val SLOP_MULTIPLIER = 4

    /** Minimum average speed since the down, in px/ms. Only decisive flicks get past it. */
    const val VELOCITY_PX_PER_MS = 1.2f

    /** The vertical travel must beat the horizontal one by this factor (no diagonals). */
    const val DOMINANCE = 1.5f

    /**
     * A band at the top edge, in dp, that belongs to the system: a swipe DOWN starting in it
     * is the user reaching for the notification shade, not for the recently used apps.
     * About the status bar's height plus a small buffer. Only "down" on home is gated.
     */
    const val TOP_EXCLUSION_DP = 48f

    /**
     * The direction of a gesture that has moved [dx]/[dy] in [dtMs] since its down, or null
     * while it is not (yet) a decisive vertical swipe: too short, too slow on average, or not
     * vertical enough. Screen y grows downward, so a finger moving up has dy < 0.
     */
    fun decisive(dx: Float, dy: Float, dtMs: Long, minDistance: Float): SwipeDir? {
        val ax = abs(dx)
        val ay = abs(dy)
        if (ay <= ax * DOMINANCE) return null
        if (ay <= minDistance) return null
        if (ay / dtMs.coerceAtLeast(1L) <= VELOCITY_PX_PER_MS) return null
        return if (dy < 0) SwipeDir.UP else SwipeDir.DOWN
    }

    /**
     * Is a decisive swipe going [dir] the frame's to act on? Home takes "up" (open the drawer)
     * and "down" (open the recently used apps) — Kolibri's two vertical home gestures — except
     * a "down" that started within [topExclusionPx] of the top edge ([downY]), which is left
     * to the notification shade. The drawer only knows "down" (close it); its "up" is a scroll.
     */
    fun claims(drawerOpen: Boolean, dir: SwipeDir, downY: Float, topExclusionPx: Float): Boolean =
        when {
            drawerOpen -> dir == SwipeDir.DOWN
            dir == SwipeDir.DOWN -> downY >= topExclusionPx
            else -> true
        }
}
