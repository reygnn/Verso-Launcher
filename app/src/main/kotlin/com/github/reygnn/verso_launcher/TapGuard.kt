package com.github.reygnn.verso_launcher

/**
 * Stale-click test for list rows. AbsListView posts its click and long-press, and one layout
 * pass clears its `mDataChanged`, so a list replaced mid-gesture would get the action on
 * whatever row moved into the touched slot: an action passes only if the list generation is
 * still the one its touch began on.
 */
internal object TapGuard {

    /** No touch (accessibility service, hardware key): must pass, or a screen reader can't
     *  launch anything. Generations start at 0, so -1 never collides. */
    const val NO_TOUCH = -1

    /** Pass the generation current when the action is delivered, so a replacement between
     *  finger-up and the posted action is still caught. */
    fun stillValid(downGeneration: Int, shownGeneration: Int): Boolean =
        downGeneration == NO_TOUCH || downGeneration == shownGeneration
}
