package com.github.reygnn.verso_launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A "~restore" pick's states: reading, read, asked, confirmed, gone — and a read that lands too late. */
class PendingPickTest {

    private val pick = PendingPick()
    private val config = VersoConfig(favorites = listOf("com.zulu/com.zulu.Main"))

    /** A planted file must not be adopted unseen: nothing to take until the user said OK. */
    @Test fun `a read config is asked about once and taken only once confirmed`() {
        val n = pick.begin()
        assertTrue("reading counts as pending", pick.pending)
        assertNull("nothing to ask about while reading", pick.ask())
        assertTrue(pick.arrived(n, config))
        assertTrue("read counts as pending", pick.pending)
        assertNull("not taken unconfirmed", pick.take())
        assertEquals(config, pick.ask())
        assertNull("asked once", pick.ask())
        assertNull("not taken while asked", pick.take())
        assertTrue("asked counts as pending", pick.pending)
        assertTrue(pick.answer(confirmed = true))
        assertTrue("confirmed counts as pending", pick.pending)
        assertEquals(config, pick.take())
        assertFalse(pick.pending)
        assertNull(pick.take())
    }

    @Test fun `a no ends the pick, with nothing to take`() {
        pick.arrived(pick.begin(), config)
        pick.ask()
        assertTrue(pick.answer(confirmed = false))
        assertFalse(pick.pending)
        assertNull(pick.take())
    }

    @Test fun `an answer counts only while asked, and only once`() {
        assertFalse("nobody asked", pick.answer(confirmed = true))
        pick.arrived(pick.begin(), config)
        assertFalse("read, not yet asked", pick.answer(confirmed = true))
        pick.ask()
        assertTrue(pick.answer(confirmed = true))
        assertFalse("the dismissal after an OK changes nothing", pick.answer(confirmed = false))
        assertEquals(config, pick.take())
    }

    @Test fun `an asked or confirmed pick is dropped too`() {
        pick.arrived(pick.begin(), config)
        pick.ask()
        assertTrue(pick.drop())
        assertFalse(pick.answer(confirmed = true))
        pick.arrived(pick.begin(), config)
        pick.ask()
        pick.answer(confirmed = true)
        assertTrue(pick.drop())
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

    @Test fun `a read pick is dropped too`() {
        pick.arrived(pick.begin(), config)
        assertTrue(pick.drop())
        assertNull(pick.ask())
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
