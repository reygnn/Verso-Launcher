package com.github.reygnn.guru

import org.junit.Assert.assertEquals
import org.junit.Test

/** The English defaults; a host's own texts are its resources, tested where they live. */
class GuruTextsTest {

    private val english = GuruTexts.English

    @Test
    fun `saved names the folder - one, or how many`() {
        assertEquals("saved to Download/Guru", english.saved(1))
        assertEquals("3 reports saved to Download/Guru", english.saved(3))
    }

    @Test
    fun `the screen's words name both gestures`() {
        assertEquals("double-tap: close · hold: share", english.hint)
        assertEquals("close", english.actionClose)
        assertEquals("share the report", english.actionShare)
    }

    @Test
    fun `a host overrides only what it translates`() {
        val partial = object : GuruTexts {
            override val actionClose = "schließen"
        }
        assertEquals("schließen", partial.actionClose)
        assertEquals(english.hint, partial.hint)
        assertEquals(english.actionShare, partial.actionShare)
        assertEquals(english.saved(2), partial.saved(2))
    }
}
