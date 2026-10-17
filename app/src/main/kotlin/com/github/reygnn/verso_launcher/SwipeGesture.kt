package com.github.reygnn.verso_launcher

import kotlin.math.abs

/** Which way a decisive vertical swipe went. */
internal enum class SwipeDir { UP, DOWN }

/** How a page drag ends when the finger lifts: finish the page turn, or fall back. */
internal enum class DragEnd { COMMIT, CANCEL }

/**
 * The pure decisions behind the home <-> drawer swipe, lifted out of [SwipeFrame] the same
 * way the prompt logic lives in [LauncherLogic]: so "is this a decisive swipe, which way, is it
 * ours" is a JVM-testable mapping instead of arithmetic buried in a touch handler. [SwipeFrame]
 * only feeds it MotionEvent deltas and acts on the answers.
 *
 * Two gestures. The PAGE DRAG (Unity Kolibri's DragToDismissCore, extended to opening; see the
 * "page drag" section) follows the finger once the content under it is at its end, and the
 * release decides. The FLICK is ported from Kolibri's SwipeGestureAnalyzer + GestureThresholds,
 * whose calibration was validated on real devices: it counts once it has travelled
 * [SLOP_MULTIPLIER] touch slops, at an AVERAGE speed of at least [VELOCITY_PX_PER_MS] since the
 * finger went down, along an axis that beats the other by [DOMINANCE], decided DURING the move.
 * It now only serves the recents swipe on home and opening the drawer while the favorites can
 * still scroll (where no drag can start).
 *
 * Units: distances in pixels, velocities in pixels per millisecond (unit-less across
 * densities, like Kolibri's). The frame scales the touch slop and [TOP_EXCLUSION_DP] itself.
 */
internal object SwipeGesture {

    /** How long after the launcher came back a HOME intent may still arrive late (One UI). */
    const val LATE_HOME_MS = 1500L

    /**
     * Whether a HOME intent should leave the page as it is. One UI can deliver HOME after the
     * launcher is already in front again, while the user is already pulling the drawer up — or
     * has just opened it: closing it then swallowed that swipe ("the first swipe after coming
     * back does nothing"). Within [LATE_HOME_MS] of coming back ([sinceStartMs]), a drag or an
     * open drawer is the user's newer intent.
     */
    fun keepPageOnHome(sinceStartMs: Long, dragging: Boolean, drawerOpen: Boolean): Boolean =
        sinceStartMs in 0 until LATE_HOME_MS && (dragging || drawerOpen)

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
     * to the notification shade. The drawer takes no flick at all: closing it is a DRAG
     * ([dragDirection]), so a flick down in a scrolled list scrolls it, as in Unity's Kolibri.
     * Home's "up" usually starts a drag before it could become a flick; the flick is the
     * fallback for when the favorites can still scroll up and the drag therefore can't start.
     */
    fun claims(drawerOpen: Boolean, dir: SwipeDir, downY: Float, topExclusionPx: Float): Boolean =
        when {
            drawerOpen -> false
            dir == SwipeDir.DOWN -> downY >= topExclusionPx
            else -> true
        }

    // ---- page drag ------------------------------------------------------------------------
    //
    // The page follows the finger (Unity's DragToDismissCore, "Pixel-style"): pulling the
    // drawer down closes it, and — beyond Unity, which only flicks it open — pulling home up
    // opens it. The content under the finger gets first call: a drag starts only once it can
    // no longer scroll that way. Releasing decides: a decisive flick wins in either direction,
    // otherwise the travelled distance does.

    /** The share of the page height a drag must cover to finish the turn on release. */
    const val DRAG_COMMIT_FRACTION = 0.28f

    /** A release at least this many times the system's minimum fling speed decides by itself. */
    const val DRAG_FLING_MULTIPLIER = 3f

    /** How far the drawer follows a closing pull, as a share of the page height. */
    const val DRAG_MAX_CLOSE_FRACTION = 0.8f

    /**
     * Home is fully faded once the drawer covers this share of the page. Verso's drawer is
     * transparent over the wallpaper, so home and list text would otherwise overlap for the
     * whole drag; fading home over the first third keeps the overlap short.
     */
    const val HOME_FADE_FRACTION = 1f / 3f

    /** Home's alpha while the drawer is [open] px up a [height]-tall page. */
    fun homeAlpha(open: Float, height: Float): Float =
        (1f - open / (height * HOME_FADE_FRACTION)).coerceIn(0f, 1f)

    /**
     * The direction a page drag would go for a finger that has moved [dx]/[dy] from its down
     * point, or null while it is still inside [slop] or not vertical enough. On home only "up"
     * drags (it opens the drawer); in the drawer only "down" (it closes it).
     */
    fun dragDirection(drawerOpen: Boolean, dx: Float, dy: Float, slop: Float): SwipeDir? {
        if (abs(dy) <= slop || abs(dy) <= abs(dx) * DOMINANCE) return null
        val dir = if (dy < 0) SwipeDir.UP else SwipeDir.DOWN
        return if (dir == (if (drawerOpen) SwipeDir.DOWN else SwipeDir.UP)) dir else null
    }

    /** The drag offset after the finger moved [delta] px in the drag's direction (negative =
     *  back), kept between 0 (where the drag started from) and [max]. */
    fun dragOffset(offset: Float, delta: Float, max: Float): Float = (offset + delta).coerceIn(0f, max)

    /**
     * How a drag at [offset] (of a [height]-tall page) ends for a release at [velocity] px/s in
     * the drag's direction (negative = back). A flick back at [flingVelocity] or faster cancels,
     * even past the threshold — "pull, then flick back" aborts, as in Unity. A flick forward
     * commits, however short. Otherwise the distance decides.
     */
    fun dragEnd(offset: Float, height: Float, velocity: Float, flingVelocity: Float): DragEnd = when {
        velocity <= -flingVelocity -> DragEnd.CANCEL
        velocity >= flingVelocity -> DragEnd.COMMIT
        offset >= height * DRAG_COMMIT_FRACTION -> DragEnd.COMMIT
        else -> DragEnd.CANCEL
    }
}
