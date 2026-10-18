package com.github.reygnn.verso_launcher

import kotlin.math.abs

internal enum class SwipeDir { UP, DOWN }

internal enum class DragEnd { COMMIT, CANCEL }

/**
 * The pure decisions behind the page turn (hard rule 11); [SwipeFrame] feeds it deltas. The
 * flick's thresholds are Kolibri's (GestureThresholds), calibrated on real devices; it serves
 * the recents swipe and opening the drawer while the favorites can still scroll.
 * Distances in px, flick velocities in px/ms.
 */
internal object SwipeGesture {

    const val SLOP_MULTIPLIER = 4

    /** Average since the down, not the instantaneous speed. */
    const val VELOCITY_PX_PER_MS = 1.2f

    const val DOMINANCE = 1.5f

    /** A swipe down starting here reaches for the notification shade (status bar + buffer). */
    const val TOP_EXCLUSION_DP = 48f

    fun decisive(dx: Float, dy: Float, dtMs: Long, minDistance: Float): SwipeDir? {
        val ax = abs(dx)
        val ay = abs(dy)
        if (ay <= ax * DOMINANCE) return null
        if (ay <= minDistance) return null
        if (ay / dtMs.coerceAtLeast(1L) <= VELOCITY_PX_PER_MS) return null
        return if (dy < 0) SwipeDir.UP else SwipeDir.DOWN
    }

    /** The drawer takes no flick: closing it is a drag, so a flick in a scrolled list scrolls it. */
    fun claims(drawerOpen: Boolean, dir: SwipeDir, downY: Float, topExclusionPx: Float): Boolean =
        when {
            drawerOpen -> false
            dir == SwipeDir.DOWN -> downY >= topExclusionPx
            else -> true
        }

    // ---- page drag (Unity's DragToDismissCore) ----------------------------------------------

    const val DRAG_COMMIT_FRACTION = 0.28f

    const val DRAG_FLING_MULTIPLIER = 3f

    const val DRAG_MAX_CLOSE_FRACTION = 0.8f

    /** The drawer is transparent, so home fades early to keep home and list text from overlapping. */
    const val HOME_FADE_FRACTION = 1f / 3f

    fun homeAlpha(open: Float, height: Float): Float =
        (1f - open / (height * HOME_FADE_FRACTION)).coerceIn(0f, 1f)

    /**
     * Home drags either way (up: the drawer, down: the recents from the top); an open drawer
     * only toward [closeDir], the way back to the side it came from — null while closed.
     */
    fun dragDirection(closeDir: SwipeDir?, dx: Float, dy: Float, slop: Float): SwipeDir? {
        if (abs(dy) <= slop || abs(dy) <= abs(dx) * DOMINANCE) return null
        val dir = if (dy < 0) SwipeDir.UP else SwipeDir.DOWN
        return if (closeDir == null || dir == closeDir) dir else null
    }

    fun dragOffset(offset: Float, delta: Float, max: Float): Float = (offset + delta).coerceIn(0f, max)

    /** [velocity] in px/s along the drag (negative = back); a flick back cancels even past the threshold. */
    fun dragEnd(offset: Float, height: Float, velocity: Float, flingVelocity: Float): DragEnd = when {
        velocity <= -flingVelocity -> DragEnd.CANCEL
        velocity >= flingVelocity -> DragEnd.COMMIT
        offset >= height * DRAG_COMMIT_FRACTION -> DragEnd.COMMIT
        else -> DragEnd.CANCEL
    }
}
