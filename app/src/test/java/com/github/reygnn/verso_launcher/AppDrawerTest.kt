package com.github.reygnn.verso_launcher

import android.app.Activity
import android.graphics.Typeface
import android.text.Spanned
import android.text.style.StyleSpan
import android.widget.TextView
import org.junit.Assert.assertEquals
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

    private val state = LauncherState(
        io = { task -> task.run(); true },
        main = { task -> task.run() },
        loadConfig = { VersoConfig(favorites = linkedSetOf(installed[1].key)) },
        enumerateApps = { installed },
        serialize = { "" },
        writeConfig = { true },
        onRefreshed = { _, _ -> },
    ).also { it.refresh() }

    private val taps = mutableListOf<TapAction<AppEntry>>()
    private var filtered = 0
    private val drawer = AppDrawer(
        activity = activity,
        state = state,
        palette = HomeStyle.palette(lightWallpaper = false),
        onTap = { taps += it },
        onLongPress = {},
        onEnter = {},
        onFiltered = { filtered++ },
    )

    private fun rows(): List<String> {
        val adapter = drawer.listView.adapter
        return (0 until adapter.count).map { (adapter.getView(it, null, drawer.listView) as TextView).text.toString() }
    }

    private val emptyHint: TextView get() = drawer.listView.emptyView as TextView

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
        assertEquals(light.listText, drawer.prompt.currentTextColor)
        assertEquals(light.listText, drawer.prompt.currentHintTextColor)
        assertEquals(light.listDim, emptyHint.currentTextColor)
        drawer.setPrompt("")
        val row = drawer.listView.adapter.getView(0, null, drawer.listView) as TextView
        assertEquals(light.listText, row.currentTextColor)
    }

    @Test fun `the prompt's hint is italic`() {
        val hint = drawer.prompt.hint as Spanned
        val span = hint.getSpans(0, hint.length, StyleSpan::class.java).single()
        assertEquals(Typeface.ITALIC, span.style)
    }

    @Test fun `a tap on a row goes to the Activity as the tap's decision`() {
        drawer.setPrompt("br")
        drawer.listView.performItemClick(null, 0, 0)
        assertEquals(listOf<TapAction<AppEntry>>(TapAction.Launch(installed[1])), taps)
    }
}
