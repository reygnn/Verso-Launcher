package com.github.reygnn.guru

import org.junit.Assert.assertEquals
import org.junit.Test

/** The English default; a host's own texts are its resources, tested where they live. */
class GuruTextsTest {

    private val english = GuruTexts.English

    @Test
    fun `all sent, one or several`() {
        assertEquals("Guru: report sent", english.sent(1, 1))
        assertEquals("Guru: 3 reports sent", english.sent(3, 3))
    }

    @Test
    fun `none sent is kept`() {
        assertEquals("Guru: not sent, kept for the next start", english.sent(0, 1))
        assertEquals("Guru: not sent, kept for the next start", english.sent(0, 2))
    }

    /** Both neighbours of "all": one short of it is partial, not "all sent". */
    @Test
    fun `some sent says how many of how many`() {
        assertEquals("Guru: 1 of 2 sent, the rest kept", english.sent(1, 2))
        assertEquals("Guru: 2 of 3 sent, the rest kept", english.sent(2, 3))
    }

    @Test
    fun `more counts the reports behind the one shown`() {
        assertEquals("+1 more", english.more(1))
    }

    @Test
    fun `the screen's words name both gestures`() {
        assertEquals("double-tap: continue · hold: send · back: later", english.hint)
        assertEquals("continue, discard the report", english.actionContinue)
        assertEquals("send the report", english.actionSend)
    }
}
