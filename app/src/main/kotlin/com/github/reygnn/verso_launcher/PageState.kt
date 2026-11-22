package com.github.reygnn.verso_launcher

/** What holds the page between its two rests: a finger ([SwipeFrame]) or a Back gesture's preview. */
internal enum class Hold { NONE, DRAG, BACK }

/** What comes of a drag that was let go ([PageState.dragEnded]). */
internal enum class DragOutcome {
    /** The drag from home went far enough: the drawer opens. */
    OPEN,

    /** The drag on the open drawer went far enough: it closes. */
    CLOSE,

    /** The drag from home fell short: the drawer drops back out of sight. */
    FALL_HOME,

    /** The drag on the open drawer fell short: it slides back into place. */
    FALL_OPEN,
}

/**
 * The page turn's state (hard rule 11): which page is up, which side the drawer is on, whether it
 * is on screen, and what holds it. One immutable value, so a change is one of the transitions
 * below and none is made halfway. [PageTurn] keeps the current one and moves the views after it.
 *
 * Invariant: every turn ([opened], [closed]) lets go of the page. That is hard rule 11's "a page
 *   turned some other way aborts a running drag": whatever that drag or Back preview still
 *   reports finds nothing held, and does nothing.
 *
 * @property open THE source of truth for which page is up. The views trail it by the length of
 *   the page-turn animation, so nothing may read their visibility instead.
 * @property fromTop the side the drawer is on. The recents come down from the top like a HUD and
 *   go back up; the drawer rises from below.
 * @property showing the drawer is on screen, at rest or moving. It still is while it slides away,
 *   so a turn that catches it there goes on from where it is.
 * @property hold what holds the page between its rests, if anything.
 */
internal data class PageState(
    val open: Boolean = false,
    val fromTop: Boolean = false,
    val showing: Boolean = false,
    val hold: Hold = Hold.NONE,
) {
    /** Back toward the side the drawer came from. */
    val closeDir: SwipeDir get() = if (fromTop) SwipeDir.UP else SwipeDir.DOWN

    /**
     * Whether a drawer coming from the top ([top]) or from below must first be put at its hidden
     * place: one that is out of sight, or on screen on the other side. One already showing on this
     * side (dragged there, or caught as it closes) goes on from where it is.
     */
    fun needsPlacing(top: Boolean): Boolean = !showing || top != fromTop

    /** The drawer is the page in front, on the side it came from. */
    fun opened(top: Boolean): PageState = copy(open = true, fromTop = top, showing = true, hold = Hold.NONE)

    /** Home is the page in front. The drawer is still [showing] until it has slid away ([gone]). */
    fun closed(): PageState = copy(open = false, hold = Hold.NONE)

    /** The drawer is out of sight. An open one never is: a slide that ends after a new turn changes nothing. */
    fun gone(): PageState = if (open) this else copy(showing = false)

    /**
     * Something takes hold of the page, moving toward [dir]. From home that brings the drawer on
     * screen, on the side the pull reveals: down for the recents from the top, up for the drawer
     * from below. An open drawer keeps its side.
     */
    fun held(by: Hold, dir: SwipeDir): PageState =
        if (open) copy(hold = by) else copy(fromTop = dir == SwipeDir.DOWN, showing = true, hold = by)

    /** The hold is over; which page is up has not changed. */
    fun released(): PageState = copy(hold = Hold.NONE)

    /** What a drag that ends with [end] comes to, from the page it took hold of. */
    fun dragEnded(end: DragEnd): DragOutcome = when {
        !open && end == DragEnd.COMMIT -> DragOutcome.OPEN
        open && end == DragEnd.COMMIT -> DragOutcome.CLOSE
        !open -> DragOutcome.FALL_HOME
        else -> DragOutcome.FALL_OPEN
    }

    /**
     * The drawer after its command line changed to [mode]. Out of the recents (the "×" or typing)
     * it is the plain drawer, and goes back down like one.
     *
     * Why: a held page keeps its side, or the drawer would jump to the other one under the finger.
     *   It follows once the hold ends without closing it.
     */
    fun following(mode: Mode): PageState {
        val leftRecents = open && fromTop && mode != Mode.RECENTS
        return if (leftRecents && hold == Hold.NONE) copy(fromTop = false) else this
    }
}
