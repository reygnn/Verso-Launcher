package com.github.reygnn.verso_launcher

import org.junit.Assert.assertEquals
import org.junit.Test

/** The mode a prompt opens and the hint of an empty list; the rows and the actions have tests of their own. */
class LauncherLogicTest {

    // ---- parseMode ----------------------------------------------------------

    @Test fun `empty and plain text are NORMAL`() {
        assertEquals(Mode.NORMAL, LauncherLogic.parseMode(""))
        assertEquals(Mode.NORMAL, LauncherLogic.parseMode("gmail"))
        assertEquals(Mode.NORMAL, LauncherLogic.parseMode("*"))
    }

    @Test fun `percent is the order toggle, whatever follows it`() {
        assertEquals(Mode.ORDER, LauncherLogic.parseMode("%"))
        assertEquals(Mode.ORDER, LauncherLogic.parseMode("%abc"))
        assertEquals(Mode.NORMAL, LauncherLogic.parseMode("a%"))
    }

    /** The edit sigils are gone: "-", "!", "!!" and "##" are plain text now. */
    @Test fun `the old edit sigils are a plain search, a double hash a tag filter`() {
        for (prompt in listOf("-", "-maps", "!", "!maps", "!!", "!!maps")) {
            assertEquals("\"$prompt\"", Mode.NORMAL, LauncherLogic.parseMode(prompt))
        }
        assertEquals(Mode.TAG_FILTER, LauncherLogic.parseMode("##work"))
    }

    @Test fun `hash is tag-filter`() {
        assertEquals(Mode.TAG_FILTER, LauncherLogic.parseMode("#"))
        assertEquals(Mode.TAG_FILTER, LauncherLogic.parseMode("#work"))
    }

    @Test fun `question mark is recents`() {
        assertEquals(Mode.RECENTS, LauncherLogic.parseMode("?"))
        assertEquals(Mode.RECENTS, LauncherLogic.parseMode("?maps"))
    }

    @Test fun `tilde text is COMMAND mode`() {
        assertEquals(Mode.COMMAND, LauncherLogic.parseMode("~"))
        assertEquals(Mode.COMMAND, LauncherLogic.parseMode("~restart"))
        assertEquals(Mode.COMMAND, LauncherLogic.parseMode("~zzz"))
    }

    @Test fun `an empty list says why, per mode`() {
        assertEquals(EmptyHint.NO_APPS, LauncherLogic.emptyHint(Mode.NORMAL, ""))
        assertEquals(EmptyHint.NO_MATCH, LauncherLogic.emptyHint(Mode.NORMAL, "xyz"))
        assertEquals(EmptyHint.NO_MATCH, LauncherLogic.emptyHint(Mode.NORMAL, "x*"))
        assertEquals(EmptyHint.NO_RECENTS, LauncherLogic.emptyHint(Mode.RECENTS, "?"))
        assertEquals(EmptyHint.NO_TAGS, LauncherLogic.emptyHint(Mode.TAG_FILTER, "#"))
        assertEquals(EmptyHint.NO_TAG_MATCH, LauncherLogic.emptyHint(Mode.TAG_FILTER, "#wo"))
        assertEquals(EmptyHint.NO_COMMAND, LauncherLogic.emptyHint(Mode.COMMAND, "~xyz"))
    }

    /** "C#" and "a~b" are searches: a sigil counts only as the first character. */
    @Test fun `a sigil inside the text is no mode`() {
        assertEquals(Mode.NORMAL, LauncherLogic.parseMode("c#"))
        assertEquals(Mode.NORMAL, LauncherLogic.parseMode("a~b"))
        assertEquals(Mode.NORMAL, LauncherLogic.parseMode("x?"))
        assertEquals(Mode.NORMAL, LauncherLogic.parseMode("50%"))
    }
}
