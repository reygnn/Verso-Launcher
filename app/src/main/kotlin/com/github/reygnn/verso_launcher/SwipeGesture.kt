package com.github.reygnn.verso_launcher

import kotlin.math.abs

/** Which way a vertical-or-not swipe went, once it has left the touch slop. */
internal enum class SwipeDir { UP, DOWN, SIDEWAYS }

/**
 * The pure decisions behind the home <-> drawer swipe, lifted out of [SwipeFrame] the same
 * way the prompt logic lives in [LauncherLogic]: so "is this a swipe, which way, is it ours,
 * has it gone far/fast enough" is a JVM-testable mapping instead of arithmetic buried in a
 * touch handler. [SwipeFrame] only feeds it MotionEvent deltas and acts on the answers.
 *
 * Units: distances and velocities are in PIXELS (per second); the frame scales the dp
 * constants below by the display density before asking.
 */
internal object SwipeGesture {

    /** How far a swipe must travel before it flips the page, in dp. */
    const val DISTANCE_DP = 64f

    /** A shorter swipe still flips the page if it is released at least this fast, in dp/s. */
    const val FLING_DP_PER_S = 500f

    /**
     * The direction of a gesture that has moved [dx]/[dy] from its down point, or null
     * while it is still inside the [slop] (undecided — a tap or a long-press in the making).
     *
     * Vertical wins only when it DOMINATES: a diagonal leaning sideways is [SwipeDir.SIDEWAYS]
     * and is never the frame's — that keeps a slightly crooked tap-and-drag on a row from
     * flipping the page. Screen y grows downward, so a finger moving up has dy < 0.
     */
    fun direction(dx: Float, dy: Float, slop: Float): SwipeDir? {
        val ax = abs(dx)
        val ay = abs(dy)
        if (ax <= slop && ay <= slop) return null
        return when {
            ay > ax -> if (dy < 0) SwipeDir.UP else SwipeDir.DOWN
            else -> SwipeDir.SIDEWAYS
        }
    }

    /**
     * Is a swipe going [dir] the frame's to take, rather than the content's to scroll?
     *
     * Home takes "up" (open the drawer) and "down" (open the recently used apps) — Kolibri's
     * two vertical home gestures; the drawer only knows "down" (close it). In every case the
     * content under the finger gets first call: while it can still scroll
     * that way ([contentCanScroll]) the swipe scrolls it, and only once it is at its end does
     * the same swipe turn the page. That is the usual launcher contract — a long favorites
     * list or a scrolled drawer never flips away under a plain scroll.
     */
    fun claims(drawerOpen: Boolean, dir: SwipeDir, contentCanScroll: Boolean): Boolean = when {
        dir == SwipeDir.SIDEWAYS -> false
        contentCanScroll -> false
        drawerOpen -> dir == SwipeDir.DOWN
        else -> true  // home: UP and DOWN both turn the page
    }

    /** Has the finger travelled [distance] pixels in [dir]? */
    fun reached(dir: SwipeDir, dy: Float, distance: Float): Boolean = when (dir) {
        SwipeDir.UP -> -dy >= distance
        SwipeDir.DOWN -> dy >= distance
        SwipeDir.SIDEWAYS -> false
    }

    /** Was the gesture released at [velocityY] (px/s, + = downward) fast enough in [dir]? */
    fun flung(dir: SwipeDir, velocityY: Float, minVelocity: Float): Boolean = when (dir) {
        SwipeDir.UP -> -velocityY >= minVelocity
        SwipeDir.DOWN -> velocityY >= minVelocity
        SwipeDir.SIDEWAYS -> false
    }
}
