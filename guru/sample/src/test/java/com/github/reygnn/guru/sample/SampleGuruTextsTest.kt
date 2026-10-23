package com.github.reygnn.guru.sample

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class SampleGuruTextsTest {

    private val texts get() = SampleGuruTexts(RuntimeEnvironment.getApplication())

    @Test
    fun `English by default`() {
        assertEquals("double-tap: continue · hold: send · back: later", texts.hint)
        assertEquals("+1 more", texts.more(1))
        assertEquals("Guru: 1 report sent", texts.sent(1, 1))
        assertEquals("Guru: 3 reports sent", texts.sent(3, 3))
        assertEquals("Guru: not sent, kept for the next start", texts.sent(0, 2))
        assertEquals("Guru: 1 of 2 sent, the rest kept", texts.sent(1, 2))
    }

    @Test
    @Config(qualifiers = "de")
    fun `German from values-de`() {
        assertEquals("doppeltippen: weiter · halten: senden · zurück: später", texts.hint)
        assertEquals("weiter, Bericht verwerfen", texts.actionContinue)
        assertEquals("Bericht senden", texts.actionSend)
        assertEquals("+2 weitere", texts.more(2))
        assertEquals("Guru: 1 von 2 gesendet, der Rest bleibt", texts.sent(1, 2))
    }
}
