package com.github.reygnn.verso_launcher

import android.app.Activity
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/**
 * The back page's rendering on a real widget tree: what the list shows for a prompt, the
 * empty-list hint per mode, a palette switch, and a tap handed to the Activity. The data is a
 * real [LauncherState] whose IO and main "threads" run inline; no LauncherApps involved.
 */
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
    private val drawer = AppDrawer(
        activity = activity,
        state = state,
        palette = HomeStyle.palette(lightWallpaper = false),
        onTap = { taps += it },
        onLongPress = {},
        onEnter = {},
        onFiltered = {},
    )

    /** The rows' texts, as the adapter binds them. */
    private fun rows(): List<String> {
        val adapter = drawer.listView.adapter
        return (0 until adapter.count).map { (adapter.getView(it, null, drawer.listView) as TextView).text.toString() }
    }

    /** The empty-list hint: the outlined text view in the page's own tree (rows live in the adapter). */
    private val emptyHint: TextView get() = drawer.view.collect<OutlinedTextView>().single()

    @Test fun `the plain drawer lists every app A to Z, a search narrows it`() {
        drawer.setPrompt("")
        assertEquals(listOf("Alpha", "Bravo", "Charlie"), rows())
        drawer.setPrompt("br")
        assertEquals(listOf("Bravo"), rows())
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

    @Test fun `a palette switch recolours the prompt, its bar, the hint and the rows`() {
        val light = HomeStyle.palette(lightWallpaper = true)
        drawer.applyPalette(light)
        assertEquals(light.listText, drawer.prompt.currentTextColor)
        assertEquals(light.listDim, drawer.prompt.currentHintTextColor)
        assertEquals(light.promptBar, ((drawer.prompt.parent as View).background as ColorDrawable).color)
        assertEquals(light.listDim, emptyHint.currentTextColor)
        drawer.setPrompt("")
        val row = drawer.listView.adapter.getView(0, null, drawer.listView) as TextView
        assertEquals(light.listText, row.currentTextColor)
    }

    @Test fun `a tap on a row goes to the Activity as the tap's decision`() {
        drawer.setPrompt("br")
        drawer.listView.performItemClick(null, 0, 0)
        assertEquals(listOf<TapAction<AppEntry>>(TapAction.Launch(installed[1])), taps)
    }
}
