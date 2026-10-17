package com.github.reygnn.verso_launcher

import android.app.Activity
import android.app.AlertDialog
import android.os.Looper
import android.text.InputFilter
import android.view.View
import android.widget.EditText
import android.widget.MultiAutoCompleteTextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Robolectric: the behaviour lives in real widgets (an [EditText] with an [InputFilter], [AlertDialog]
 * buttons). A bare Activity from [Robolectric.buildActivity] gives the window token — no androidx.test.
 */
@RunWith(RobolectricTestRunner::class)
class RenameDialogFilterTest {

    @get:Rule val locale = DefaultLocaleRule()

    private val activity: Activity =
        Robolectric.buildActivity(Activity::class.java).setup().get()

    private val fgColor = 0xFFD4D4D4.toInt()

    private class Harness(val dialog: AlertDialog) {
        var committed: Pair<String, List<String>>? = null
    }

    private fun show(
        initialName: String = "",
        initialTags: String = "",
        allTags: List<String> = emptyList(),
    ): Harness {
        lateinit var harness: Harness
        val dialog = RenameDialog.show(
            context = activity,
            title = "Gmail",
            initialName = initialName,
            initialTags = initialTags,
            allTags = allTags,
            fgColor = fgColor,
            onAppInfo = {},
        ) { name, tags -> harness.committed = name to tags }
        harness = Harness(dialog)
        return harness
    }


    private fun Harness.tagsField(): MultiAutoCompleteTextView =
        dialog.window!!.decorView.collect<MultiAutoCompleteTextView>().single()

    private fun Harness.nameField(): EditText =
        dialog.window!!.decorView.collect<EditText>().single { it !is MultiAutoCompleteTextView }

    /** AlertDialog posts the click through a Handler and the main looper is paused: drain it. */
    private fun Harness.click(which: Int) {
        dialog.getButton(which).performClick()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun Harness.clickOk() = click(AlertDialog.BUTTON_POSITIVE)

    // ---- the tag InputFilter -------------------------------------------------

    @Test fun `the tags field carries exactly one filter - the fold, replacing defaults`() {
        // RenameDialog sets exactly one filter; more means a framework default (isSingleLine) slipped back in.
        assertEquals(1, show().tagsField().filters.size)
    }

    @Test fun `the filter folds an uppercase run to lowercase`() {
        val filter = show().tagsField().filters.single()
        // A changed run is rewritten; an already-folded one is accepted unchanged (null).
        assertEquals("work", filter.filter("WORK", 0, 4, null, 0, 0).toString())
        assertEquals("chat", filter.filter("Chat", 0, 4, null, 0, 0).toString())
        assertNull(filter.filter("work", 0, 4, null, 0, 0))
    }

    @Test fun `the filter folds with ROOT even under a Turkish default locale`() {
        java.util.Locale.setDefault(java.util.Locale.forLanguageTag("tr"))
        val filter = show().tagsField().filters.single()
        // Under tr, "I".lowercase() is the dotless "ı"; the field must show the ROOT fold.
        assertEquals("i", filter.filter("I", 0, 1, null, 0, 0).toString())
    }

    /** NFC makes the fold shorter than its source ("e" + U+0301 -> "é"); the widget must not throw. */
    @Test fun `a length-changing fold is returned and the real widget accepts it`() {
        val harness = show()
        val field = harness.tagsField()

        assertEquals("\u00e9", field.filters.single().filter("e\u0301", 0, 2, null, 0, 0).toString())

        field.setText("")
        field.text.append("e\u0301")
        assertEquals("\u00e9", field.text.toString())
    }

    /** Not the "i" + combining dot that ROOT lowercasing alone produces. */
    @Test fun `the capital dotted I folds to a plain i in the tag field`() {
        val harness = show()
        val field = harness.tagsField()

        field.setText("")
        field.text.append("İş")
        assertEquals("iş", field.text.toString())
    }

    // ---- the commit contract -------------------------------------------------

    @Test fun `OK hands back a trimmed name and canonical, de-duplicated tags`() {
        val harness = show(initialName = "  My Gmail  ", initialTags = "#Work, Chat, work")
        harness.clickOk()

        val (name, tags) = harness.committed!!
        assertEquals("My Gmail", name)
        assertEquals(listOf("work", "chat"), tags)
    }

    @Test fun `OK on empty fields hands back an empty name and no tags`() {
        val harness = show(initialName = "", initialTags = "   ")
        harness.clickOk()

        val (name, tags) = harness.committed!!
        assertEquals("", name)
        assertTrue(tags.isEmpty())
    }

    @Test fun `Cancel commits nothing`() {
        val harness = show(initialName = "changed", initialTags = "work")
        harness.click(AlertDialog.BUTTON_NEGATIVE)
        assertNull(harness.committed)
    }

    // ---- the name field's filters -----------------------------------------------

    @Test fun `the name field drops look-alike and control characters as typed`() {
        val field = show().nameField()
        field.setText("Sig\u202Enal\u200B\n")
        assertEquals("Signal", field.text.toString())
    }

    @Test fun `the name field stops at the longest name a config may hold`() {
        val field = show().nameField()
        field.setText("a".repeat(LauncherLogic.MAX_NAME_LENGTH + 50))
        assertEquals(LauncherLogic.MAX_NAME_LENGTH, field.text.length)
    }
}
