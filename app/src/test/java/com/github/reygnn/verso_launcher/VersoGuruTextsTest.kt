package com.github.reygnn.verso_launcher

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Guru's words from Verso's resources: English by default, German from values-de. */
@RunWith(RobolectricTestRunner::class)
class VersoGuruTextsTest {

    private val texts get() = VersoGuruTexts(RuntimeEnvironment.getApplication())

    @Test
    fun `English by default`() {
        assertEquals("double-tap: continue · hold: send · back: later", texts.hint)
        assertEquals("continue, discard the report", texts.actionContinue)
        assertEquals("send the report", texts.actionSend)
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
        assertEquals("+1 weiterer", texts.more(1))
        assertEquals("+2 weitere", texts.more(2))
        assertEquals("Guru: 1 Bericht gesendet", texts.sent(1, 1))
        assertEquals("Guru: 2 Berichte gesendet", texts.sent(2, 2))
        assertEquals("Guru: nicht gesendet, bleibt für den nächsten Start", texts.sent(0, 2))
        assertEquals("Guru: 1 von 2 gesendet, der Rest bleibt", texts.sent(1, 2))
    }
}
