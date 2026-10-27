package com.github.reygnn.verso_launcher

import org.junit.Assert.assertEquals
import org.junit.Test

class CommandDialogTextTest {

    /** Resource id and count, so a line shows which string and number it was. */
    private val words = { id: Int, count: Int? -> if (count == null) "$id" else "$id=$count" }

    /** A planted file that empties the favorites must say "0", not leave the line out. */
    @Test fun `restore shows all four counts, zeros too`() {
        val text = CommandDialogText.restore(VersoConfig(hidden = setOf("a/A")), words)
        assertEquals(
            listOf(
                "${R.string.restore_intro}", "",
                "${R.string.count_favorites}=0", "${R.string.count_hidden}=1",
                "${R.string.count_names}=0", "${R.string.count_tags}=0", "",
                "${R.string.restore_undo_note}",
            ),
            text.lines(),
        )
    }
}
