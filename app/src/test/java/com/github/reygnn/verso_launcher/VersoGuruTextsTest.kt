package com.github.reygnn.verso_launcher

import com.github.reygnn.guru.GuruTexts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.lang.reflect.Modifier

/** Guru's words from Verso's resources: English by default, German from values-de. */
@RunWith(RobolectricTestRunner::class)
class VersoGuruTextsTest {

    private val texts get() = VersoGuruTexts(RuntimeEnvironment.getApplication())

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

    /** VersoApp builds its texts once, at the process start; a language changed later must still show. */
    @Test
    fun `read each time, so a language change reaches the alert`() {
        val once = texts
        assertEquals("close", once.actionClose)
        RuntimeEnvironment.setQualifiers("de")
        assertEquals("doppeltippen: schließen · halten: teilen", once.hint)
        assertEquals("schließen", once.actionClose)
        assertEquals("Bericht teilen", once.actionShare)
        assertEquals("in Download/Guru gespeichert", once.saved(1))
    }

    /**
     * Every GuruTexts member has an English default, so one Verso does not override is English on a
     * German device too — and a member a later Guru adds compiles unnoticed. This fails on that pull.
     */
    @Test
    @Config(qualifiers = "de")
    fun `every GuruTexts member is Verso's own`() {
        val members = GuruTexts::class.java.methods.filter { !Modifier.isStatic(it.modifiers) && it.returnType == String::class.java }
        assertTrue(members.isNotEmpty())
        for (m in members) {
            val args = m.parameterTypes.map { 2 }.toTypedArray()
            assertNotEquals("not overridden: ${m.name}", m.invoke(GuruTexts.English, *args), m.invoke(texts, *args))
        }
    }
}
