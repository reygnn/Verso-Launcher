package com.github.reygnn.verso_launcher

import org.junit.Assert.assertEquals
import org.junit.Test

class CommandDialogTextTest {

    /** Resource id and count, so a line shows which string and number it was. */
    private val words = { id: Int, count: Int? -> if (count == null) "$id" else "$id=$count" }

    @Test fun `cleanup shows only the sections it would change`() {
        val text = CommandDialogText.cleanup(Orphans(setOf("a/A", "b/B"), favorites = 1, hidden = 0, names = 2, tags = 0), words)
        assertEquals(
            listOf(
                "${R.string.cleanup_apps}=2", "",
                "${R.string.cleanup_favorites}=1", "${R.string.cleanup_names}=2", "",
                "${R.string.cleanup_undo_note}",
            ),
            text.lines(),
        )
    }

    /** A planted file that empties the favorites must say "0", not leave the line out. */
    @Test fun `restore shows all four counts, zeros too`() {
        val text = CommandDialogText.restore(VersoConfig(hidden = setOf("a/A")), words)
        assertEquals(
            listOf(
                "${R.string.restore_intro}", "",
                "${R.string.cleanup_favorites}=0", "${R.string.cleanup_hidden}=1",
                "${R.string.cleanup_names}=0", "${R.string.cleanup_tags}=0", "",
                "${R.string.restore_undo_note}",
            ),
            text.lines(),
        )
    }
}
