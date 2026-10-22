package com.github.reygnn.verso_launcher

import android.app.Activity
import android.graphics.Typeface
import android.text.Spanned
import android.text.style.StyleSpan
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/** A real [LauncherState] whose IO and main "threads" run inline. */
@RunWith(RobolectricTestRunner::class)
class AppDrawerTest {

    private val activity: Activity = Robolectric.buildActivity(Activity::class.java).setup().get()

    private fun app(label: String) = AppEntry.of(label, "com.${label.lowercase()}", "com.${label.lowercase()}.Main", label)
    private val installed = listOf(app("Alpha"), app("Bravo"), app("Charlie"))

    private val state = testLauncherState(
        io = { task -> task.run(); true },
        main = { task -> task.run() },
        loadConfig = { VersoConfig(favorites = linkedSetOf(installed[1].key)) },
        enumerateApps = { Enumeration(installed, true) },
        serialize = { "" },
        writeConfig = { true },
        onRefreshed = { _, _ -> },
    ).also { it.refresh() }

    private val taps = mutableListOf<TapAction<AppEntry>>()
    private var filtered = 0
    private val modes = mutableListOf<Mode>()
    private val drawer = AppDrawer(
        activity = activity,
        state = state,
        palette = HomeStyle.palette(lightWallpaper = false),
        onTap = { taps += it },
        onLongPress = {},
        onEnter = {},
        onFiltered = { filtered++ },
        onModeChanged = { modes += it },
    )

    // The widgets are the drawer's own: reached through the view tree, like any built in code.
    private val list: ListView get() = drawer.view.collect<ListView>().single()
    private val prompt: EditText get() = drawer.view.collect<EditText>().single()

    private fun rows(): List<String> {
        val adapter = list.adapter
        return (0 until adapter.count).map { (adapter.getView(it, null, list) as TextView).text.toString() }
    }

    private val emptyHint: TextView get() = list.emptyView as TextView

    @Test fun `the plain drawer lists every app A to Z, a search narrows it`() {
        drawer.setPrompt("")
        assertEquals(listOf("Alpha", "Bravo", "Charlie"), rows())
        drawer.setPrompt("br")
        assertEquals(listOf("Bravo"), rows())
    }

    /** Home re-renders from onFiltered: a search cannot change the favorites. */
    @Test fun `typing does not ask home to re-render, a refilter does`() {
        drawer.setPrompt("br")
        drawer.setPrompt("")
        assertEquals(0, filtered)
        drawer.refilter()
        assertEquals(1, filtered)
    }

    @Test fun `an edit mode marks each row`() {
        drawer.setPrompt("!")
        assertEquals(Mode.FAV_EDIT, drawer.mode)
        assertEquals(listOf("[ ] Alpha", "[x] Bravo", "[ ] Charlie"), rows())
    }

    @Test fun `an empty list says why, per mode`() {
        drawer.setPrompt("zzz")
        assertEquals(activity.getString(R.string.empty_no_match), emptyHint.text.toString())
        drawer.setPrompt("#")
        assertEquals(activity.getString(R.string.empty_no_tags), emptyHint.text.toString())
        drawer.setPrompt("~zz")
        assertEquals(activity.getString(R.string.empty_no_command), emptyHint.text.toString())
    }

    @Test fun `a palette switch recolours the prompt, the hint and the rows`() {
        val light = HomeStyle.palette(lightWallpaper = true)
        drawer.applyPalette(light)
        assertEquals(light.listText, prompt.currentTextColor)
        assertEquals(light.listText, prompt.currentHintTextColor)
        assertEquals(light.listText, emptyHint.currentTextColor)
        drawer.setPrompt("")
        val row = list.adapter.getView(0, null, list) as TextView
        assertEquals(light.listText, row.currentTextColor)
    }

    @Test fun `the prompt's hint and the empty-list hint are italic`() {
        val hint = prompt.hint as Spanned
        val span = hint.getSpans(0, hint.length, StyleSpan::class.java).single()
        assertEquals(Typeface.ITALIC, span.style)
        assertEquals(Typeface.ITALIC, emptyHint.typeface.style)
    }

    @Test fun `a mode change is reported once, typing within a mode is not`() {
        drawer.setPrompt("?")
        drawer.setPrompt("?a")
        drawer.setPrompt("")
        assertEquals(listOf(Mode.RECENTS, Mode.NORMAL), modes)
    }

    @Test fun `a tap on a row goes to the Activity as the tap's decision`() {
        drawer.setPrompt("br")
        list.performItemClick(null, 0, 0)
        assertEquals(listOf<TapAction<AppEntry>>(TapAction.Launch(installed[1])), taps)
    }

    @Test fun `a picked favorite is marked until it is dropped`() {
        drawer.setPrompt("!!")
        val before = rows()
        drawer.pickUp(installed[1].key)
        assertEquals(installed[1].key, drawer.reorderPick)
        assertNotEquals(before, rows())
        drawer.dropPick()
        assertNull(drawer.reorderPick)
        assertEquals(before, rows())
    }

    @Test fun `leaving the reorder mode lets the pick go`() {
        drawer.setPrompt("!!")
        drawer.pickUp(installed[1].key)
        drawer.setPrompt("")
        assertNull(drawer.reorderPick)
    }

    /** runCommand and Back rely on it: the mode is NORMAL again before the call returns. */
    @Test fun `clearing the prompt is the plain drawer at once`() {
        drawer.setPrompt("!")
        drawer.clearPrompt()
        assertEquals("", drawer.promptText)
        assertEquals(Mode.NORMAL, drawer.mode)
    }
}
