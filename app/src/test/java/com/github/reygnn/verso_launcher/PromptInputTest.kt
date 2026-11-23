package com.github.reygnn.verso_launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What a key press or a pasted run types into the command line. */
class PromptInputTest {

    // ---- typedChar ----------------------------------------------------------

    /** android.view.KeyCharacterMap.COMBINING_ACCENT: set on a dead key's unicodeChar. */
    private val combiningAccent = 0x80000000.toInt()

    @Test fun `a plain key types its character`() {
        assertEquals("a", PromptInput.typedChar('a'.code, chord = false))
        assertEquals("ß", PromptInput.typedChar('ß'.code, chord = false))
        assertEquals("~", PromptInput.typedChar('~'.code, chord = false))
    }

    /** A dead key carries the COMBINING_ACCENT bit: a negative Int that Character.toChars throws on. */
    @Test fun `a dead key types nothing and does not throw`() {
        assertNull(PromptInput.typedChar(combiningAccent or '^'.code, chord = false))
        assertNull(PromptInput.typedChar(combiningAccent or 0xB4, chord = false))
        assertNull(PromptInput.typedChar(combiningAccent or '`'.code, chord = false))
    }

    @Test fun `zero, control characters and invalid code points type nothing`() {
        assertNull(PromptInput.typedChar(0, chord = false))
        assertNull(PromptInput.typedChar('\n'.code, chord = false))
        assertNull(PromptInput.typedChar('\t'.code, chord = false))
        assertNull(PromptInput.typedChar(0x7F, chord = false))
        assertNull(PromptInput.typedChar(Character.MAX_CODE_POINT + 1, chord = false))
    }

    @Test fun `a supplementary code point types as a surrogate pair`() {
        assertEquals(String(Character.toChars(0x1F600)), PromptInput.typedChar(0x1F600, chord = false))
        assertEquals(String(Character.toChars(Character.MAX_CODE_POINT)), PromptInput.typedChar(Character.MAX_CODE_POINT, chord = false))
    }

    /** AltGr is no chord (the Activity leaves it out), so "@" and "€" on a German layout still type. */
    @Test fun `a chord types nothing`() {
        assertNull(PromptInput.typedChar('a'.code, chord = true))
        assertNull(PromptInput.typedChar(combiningAccent or '^'.code, chord = true))
    }

    /** Typing passes untouched: a rewritten run would drop the keyboard's composing span ("wwo"). */
    @Test fun `promptRun leaves a run without control characters alone`() {
        assertNull(PromptInput.promptRun("Google Maps"))
        assertNull(PromptInput.promptRun("~restart"))
        assertNull(PromptInput.promptRun(""))
    }

    /** A name copied over two lines must still find the app: its words apart, not run together. */
    @Test fun `promptRun turns line breaks and tabs into one space and drops other controls`() {
        assertEquals("Google Maps", PromptInput.promptRun("Google\nMaps"))
        assertEquals("Google Maps", PromptInput.promptRun("Google\r\nMaps"))
        assertEquals("Google Maps", PromptInput.promptRun("Google\n\n\tMaps"))
        assertEquals("a b", PromptInput.promptRun("a\tb"))
        assertEquals("ab", PromptInput.promptRun("a\u0007b"))
        assertEquals("~restart ", PromptInput.promptRun("~restart\n"))
    }
}
