package com.github.reygnn.verso_launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The page turn's state on plain values (hard rule 11): which page is up, the drawer's side, what
 * holds it. PageTurnTest runs the same turns on real views.
 */
class PageStateTest {

    private val home = PageState()
    private val drawer = home.opened(top = false)
    private val recents = home.opened(top = true)

    @Test fun `home is closed, with the drawer out of sight and nothing held`() {
        assertFalse(home.open)
        assertFalse(home.showing)
        assertEquals(Hold.NONE, home.hold)
    }

    @Test fun `the drawer closes down, the recents close up`() {
        assertEquals(SwipeDir.DOWN, drawer.closeDir)
        assertEquals(SwipeDir.UP, recents.closeDir)
    }

    // ---- where an opening drawer starts from -------------------------------------------

    @Test fun `a drawer out of sight is placed, whatever side it was on last`() {
        assertTrue(home.needsPlacing(top = false))
        assertTrue(home.needsPlacing(top = true))
        val wasRecents = recents.closed().gone()
        assertTrue("the same side as last time, but hidden", wasRecents.needsPlacing(top = true))
    }

    /** A drawer caught as it slides away goes on from where it is, unless it must come from the other side. */
    @Test fun `a drawer still on screen is placed only for the other side`() {
        val closing = drawer.closed()
        assertTrue(closing.showing)
        assertFalse(closing.needsPlacing(top = false))
        assertTrue(closing.needsPlacing(top = true))
    }

    // ---- turns ----------------------------------------------------------------------------

    @Test fun `closing keeps the drawer on screen until its slide has ended`() {
        val closing = drawer.closed()
        assertFalse(closing.open)
        assertTrue(closing.showing)
        assertFalse(closing.gone().showing)
    }

    /** A slide's end that arrives after the drawer was opened again must not hide it. */
    @Test fun `an open drawer is never gone`() {
        assertEquals(drawer, drawer.gone())
    }

    // ---- holds --------------------------------------------------------------------------

    @Test fun `a pull from home brings the drawer on screen on the side it reveals`() {
        val up = home.held(Hold.DRAG, SwipeDir.UP)
        assertEquals(PageState(open = false, fromTop = false, showing = true, hold = Hold.DRAG), up)
        val down = home.held(Hold.DRAG, SwipeDir.DOWN)
        assertEquals(PageState(open = false, fromTop = true, showing = true, hold = Hold.DRAG), down)
    }

    @Test fun `a hold on the open drawer keeps its side`() {
        assertTrue(recents.held(Hold.DRAG, SwipeDir.UP).fromTop)
        assertFalse(drawer.held(Hold.BACK, SwipeDir.DOWN).fromTop)
    }

    @Test fun `a drag ends on the page it took hold of`() {
        val opening = home.held(Hold.DRAG, SwipeDir.UP)
        assertEquals(DragOutcome.OPEN, opening.dragEnded(DragEnd.COMMIT))
        assertEquals(DragOutcome.FALL_HOME, opening.dragEnded(DragEnd.CANCEL))
        val closing = drawer.held(Hold.DRAG, SwipeDir.DOWN)
        assertEquals(DragOutcome.CLOSE, closing.dragEnded(DragEnd.COMMIT))
        assertEquals(DragOutcome.FALL_OPEN, closing.dragEnded(DragEnd.CANCEL))
    }

    /** Hard rule 11: a page turned some other way (HOME, Back, a key) aborts a running drag. */
    @Test fun `every turn lets go of the page`() {
        for (by in listOf(Hold.DRAG, Hold.BACK)) {
            assertEquals(Hold.NONE, home.held(by, SwipeDir.UP).opened(top = false).hold)
            assertEquals(Hold.NONE, drawer.held(by, SwipeDir.DOWN).closed().hold)
        }
    }

    @Test fun `letting go changes neither the page nor the side`() {
        val held = recents.held(Hold.BACK, SwipeDir.UP)
        assertEquals(recents, held.released())
    }

    // ---- the mode ------------------------------------------------------------------------

    @Test fun `out of the recents the drawer goes back down like a drawer`() {
        assertFalse(recents.following(Mode.NORMAL).fromTop)
        assertFalse(recents.following(Mode.TAG_FILTER).fromTop)
        assertTrue("still the recents", recents.following(Mode.RECENTS).fromTop)
    }

    @Test fun `a held page keeps its side until the hold ends`() {
        for (by in listOf(Hold.DRAG, Hold.BACK)) {
            val held = recents.held(by, SwipeDir.UP)
            assertTrue("no jump under the finger", held.following(Mode.NORMAL).fromTop)
            assertFalse(held.released().following(Mode.NORMAL).fromTop)
        }
    }

    /** A recents pull that falls back clears its "?": the mode changes on a closed page, whose side stays. */
    @Test fun `a mode change on the closed page changes nothing`() {
        val fellBack = home.held(Hold.DRAG, SwipeDir.DOWN).released()
        assertEquals(fellBack, fellBack.following(Mode.NORMAL))
    }
}
