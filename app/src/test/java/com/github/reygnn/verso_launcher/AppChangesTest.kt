package com.github.reygnn.verso_launcher

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * When a change to the apps becomes a refresh, on plain values: the timer is a list of what was
 * asked of it. MainActivityAppsTest runs the same rules through the real window and a real timer.
 */
class AppChangesTest {

    private val timer = mutableListOf<String>()
    private var refreshes = 0

    private val changes = AppChanges(
        schedule = { delayMs -> timer += "schedule $delayMs" },
        cancel = { timer += "cancel" },
        refresh = { refreshes++ },
    )

    /** In front with the first list in: where most of a launcher's life is spent. */
    private fun inFront() {
        changes.resumed(incomplete = false)
        refreshes = 0
    }

    @Test fun `the first resume loads the list, the next one does not`() {
        changes.resumed(incomplete = false)
        assertEquals(1, refreshes)
        changes.paused()
        changes.resumed(incomplete = false)
        assertEquals("nothing changed: no enumeration at a return home", 1, refreshes)
    }

    @Test fun `an incomplete load is tried again at every resume`() {
        inFront()
        repeat(2) {
            changes.paused()
            changes.resumed(incomplete = true)
        }
        assertEquals(2, refreshes)
    }

    @Test fun `a change in front starts the timer and refreshes only when it runs out`() {
        inFront()
        changes.changed()
        assertEquals(listOf("schedule ${AppChanges.SETTLE_MS}"), timer)
        assertEquals(0, refreshes)
        changes.settled()
        assertEquals(1, refreshes)
    }

    @Test fun `a burst restarts the timer and is one refresh`() {
        inFront()
        repeat(3) { changes.changed() }
        assertEquals(3, timer.size)
        changes.settled()
        assertEquals(1, refreshes)
    }

    @Test fun `a change behind another app starts no timer and waits for the resume`() {
        inFront()
        changes.paused()
        repeat(20) { changes.changed() }
        assertEquals(emptyList<String>(), timer)
        assertEquals(0, refreshes)
        changes.resumed(incomplete = false)
        assertEquals("twenty changes, one reload", 1, refreshes)
    }

    @Test fun `a pause cancels a burst still settling, and the resume collects it`() {
        inFront()
        changes.changed()
        changes.paused()
        assertEquals("cancel", timer.last())
        changes.resumed(incomplete = false)
        assertEquals(1, refreshes)
    }

    @Test fun `a pause with no timer running cancels nothing`() {
        inFront()
        changes.paused()
        assertEquals(emptyList<String>(), timer)
        changes.changed()
        changes.settled()                // in front: the timer of an earlier burst ran out
        changes.paused()
        assertEquals("the timer is over, not cancelled", listOf<String>(), timer.filter { it == "cancel" })
    }

    /** The timer is the caller's: should one outlive its cancel, it refreshes nothing behind another app. */
    @Test fun `a timer that runs out behind another app only marks the list stale`() {
        inFront()
        changes.paused()
        changes.settled()
        assertEquals(0, refreshes)
        changes.resumed(incomplete = false)
        assertEquals(1, refreshes)
    }

    @Test fun `a new window or state loads again at the next resume, once`() {
        inFront()
        changes.paused()
        changes.markStale()
        changes.resumed(incomplete = false)
        assertEquals(1, refreshes)
        changes.paused()
        changes.resumed(incomplete = false)
        assertEquals(1, refreshes)
    }

    @Test fun `marked stale in front, the list waits for the next resume`() {
        inFront()
        changes.markStale()
        assertEquals("no refresh by itself: a restart recreates the window, whose resume loads", 0, refreshes)
        changes.paused()
        changes.resumed(incomplete = false)
        assertEquals(1, refreshes)
    }
}
