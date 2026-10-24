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
        assertEquals("double-tap: close · hold: share", texts.hint)
        assertEquals("close", texts.actionClose)
        assertEquals("share the report", texts.actionShare)
        assertEquals("saved to Download/Guru", texts.saved(1))
        assertEquals("3 reports saved to Download/Guru", texts.saved(3))
    }

    @Test
    @Config(qualifiers = "de")
    fun `German from values-de`() {
        assertEquals("doppeltippen: schließen · halten: teilen", texts.hint)
        assertEquals("schließen", texts.actionClose)
        assertEquals("Bericht teilen", texts.actionShare)
        assertEquals("in Download/Guru gespeichert", texts.saved(1))
        assertEquals("2 Berichte in Download/Guru gespeichert", texts.saved(2))
    }
}
