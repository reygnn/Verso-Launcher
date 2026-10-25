package com.github.reygnn.verso_launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A "~restore-saf" pick's states: reading, ready, gone — and a read that lands too late. */
class PendingPickTest {

    private val pick = PendingPick()
    private val config = VersoConfig(favorites = listOf("com.zulu/com.zulu.Main"))

    @Test fun `a read config waits until it is taken, once`() {
        val n = pick.begin()
        assertTrue("reading counts as pending", pick.pending)
        assertNull("nothing to take while reading", pick.take())
        assertTrue(pick.arrived(n, config))
        assertTrue("ready counts as pending", pick.pending)
        assertEquals(config, pick.take())
        assertFalse(pick.pending)
        assertNull(pick.take())
    }

    @Test fun `an unreadable file ends the pick, with nothing to take`() {
        val n = pick.begin()
        assertTrue("the read is the pick's: the caller says it failed", pick.arrived(n, null))
        assertFalse(pick.pending)
        assertNull(pick.take())
    }

    @Test fun `a dropped read that lands later changes nothing`() {
        val n = pick.begin()
        assertTrue(pick.drop())
        assertFalse(pick.arrived(n, config))
        assertFalse(pick.pending)
        assertNull(pick.take())
    }

    @Test fun `a ready pick is dropped too`() {
        pick.arrived(pick.begin(), config)
        assertTrue(pick.drop())
        assertNull(pick.take())
    }

    @Test fun `nothing pending, nothing to drop`() {
        assertFalse(pick.drop())
        assertFalse("a read nobody started", pick.arrived(1, config))
    }

    @Test fun `a newer pick supersedes an older read`() {
        val old = pick.begin()
        val new = pick.begin()
        assertFalse(pick.arrived(old, config))
        assertTrue("still reading the newer one", pick.pending)
        assertTrue(pick.arrived(new, null))
        assertFalse(pick.pending)
    }
}
