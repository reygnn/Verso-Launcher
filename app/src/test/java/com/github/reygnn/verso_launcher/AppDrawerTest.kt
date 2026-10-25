package com.github.reygnn.verso_launcher

import android.app.Activity
import android.graphics.Typeface
import android.text.Spanned
import android.text.style.StyleSpan
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/** A real [LauncherState] whose IO and main "threads" run inline. */
@RunWith(RobolectricTestRunner::class)
class AppDrawerTest {

    private val activity: Activity = Robolectric.buildActivity(Activity::class.java).setup().get()

    private fun app(label: String) = AppEntry(label, "com.${label.lowercase()}", "com.${label.lowercase()}.Main", label)
    private val installed = listOf(app("Alpha"), app("Bravo"), app("Charlie"))

    private val state = testLauncherState(
        io = { task -> task.run(); true },
        main = { task -> task.run() },
        loadConfig = { VersoConfig(favorites = listOf(installed[1].key)) },
        enumerateApps = { Enumeration(installed, true) },
        serialize = { "" },
        writeConfig = { true },
        onRefreshed = { _, _ -> },
    ).also { it.refresh() }

    private val taps = mutableListOf<DrawerAction<AppEntry>>()
    private var filtered = 0
    private val modes = mutableListOf<Mode>()
    private val drawer = AppDrawer(
        activity = activity,
        state = state,
        palette = HomeStyle.palette(lightWallpaper = false),
        onAction = { taps += it },
        onLongPress = {},
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

    /** The one-line field shows a line break as a space; the text gets a space, so a search sees one. */
    @Test fun `a pasted line break becomes a space in the prompt, typing stays as typed`() {
        prompt.setText("Alpha\nBravo")
        assertEquals("Alpha Bravo", prompt.text.toString())
        prompt.setText("")
        prompt.text.append("br")
        assertEquals(listOf("Bravo"), rows())
    }

    /**
     * A paused or archived app's row is dimmed, like its favorite on home; a recycled row that then
     * shows a command must lose the dimming (bindPlainRow).
     */
    @Test fun `a paused app's row is dimmed, a ready one and a recycled command row are not`() {
        val paused = AppEntry("Delta", "com.delta", "com.delta.Main", "Delta", status = AppStatus.PAUSED)
        val withPaused = testLauncherState(
            io = { task -> task.run(); true },
            main = { task -> task.run() },
            loadConfig = { VersoConfig() },
            enumerateApps = { Enumeration(installed + paused, true) },
            serialize = { "" },
            writeConfig = { true },
            onRefreshed = { _, _ -> },
        ).also { it.refresh() }
        val drawer = AppDrawer(
            activity = activity,
            state = withPaused,
            palette = HomeStyle.palette(lightWallpaper = false),
            onAction = {},
            onLongPress = {},
            onFiltered = {},
            onModeChanged = {},
        )
        val list = drawer.view.collect<ListView>().single()
        drawer.setPrompt("")
        val rows = (0 until list.adapter.count).map { list.adapter.getView(it, null, list) as TextView }
        assertEquals(listOf("Alpha", "Bravo", "Charlie", "Delta"), rows.map { it.text.toString() })
        assertEquals(1f, rows[0].alpha, 0f)
        assertEquals(HomeStyle.UNAVAILABLE_ALPHA, rows[3].alpha, 0f)

        drawer.setPrompt("~")
        val recycled = list.adapter.getView(0, rows[3], list)
        assertEquals("the same view, now a command row", rows[3], recycled)
        assertEquals(1f, recycled.alpha, 0f)
    }

    /**
     * On a page turn TalkBack speaks the focused field, not the pane title, and reads the visible
     * hint's ">" as "greater than" (both heard on the A17): an empty field speaks a hint that names
     * the page — in its node and in its focus event, which is what TalkBack spoke. Typed text stays.
     */
    @Test fun `the empty prompt speaks a hint that names the page, typed text stays as typed`() {
        val spoken = activity.getString(R.string.a11y_prompt_hint)
        drawer.setPrompt("")
        val info = android.view.accessibility.AccessibilityNodeInfo()
        prompt.onInitializeAccessibilityNodeInfo(info)
        assertEquals(spoken, info.hintText)
        assertEquals(spoken, info.text.toString())
        val event = android.view.accessibility.AccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_FOCUSED)
        prompt.onPopulateAccessibilityEvent(event)
        assertEquals(listOf(spoken), event.text.map { it.toString() })

        drawer.setPrompt("br")
        val typed = android.view.accessibility.AccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_FOCUSED)
        prompt.onPopulateAccessibilityEvent(typed)
        assertEquals(listOf("br"), typed.text.map { it.toString() })
    }

    /** The recents open with focus on their first row, so the list's name says where that is (A17). */
    @Test fun `the list is named after what it shows`() {
        drawer.setPrompt("?")
        assertEquals(activity.getString(R.string.a11y_pane_recents), list.contentDescription)
        drawer.setPrompt("")
        assertEquals(activity.getString(R.string.a11y_pane_drawer), list.contentDescription)
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
        assertEquals(light.text, prompt.currentTextColor)
        assertEquals(light.text, prompt.currentHintTextColor)
        assertEquals(light.text, emptyHint.currentTextColor)
        drawer.setPrompt("")
        val row = list.adapter.getView(0, null, list) as TextView
        assertEquals(light.text, row.currentTextColor)
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
        assertEquals(listOf<DrawerAction<AppEntry>>(DrawerAction.Launch(installed[1])), taps)
    }

    private val permit: LauncherState.EditPermit get() = checkNotNull(state.editPermit(checkNotNull(CommandGate().idle())))

    @Test fun `a picked favorite is marked until it is dropped`() {
        state.toggleFavorite(permit, installed[0].key)  // two favorites: the other row must not change
        drawer.setPrompt("!!")
        val before = rows()
        assertEquals(2, before.size)
        drawer.pickUp(installed[1].key)
        assertEquals(installed[1].key, drawer.reorderPick)
        assertEquals(before.map { if (it.endsWith("Bravo")) "\u00BB Bravo" else it }, rows())
        drawer.dropPick()
        assertNull(drawer.reorderPick)
        assertEquals(before, rows())
    }

    /** The next tap would otherwise move a favorite no longer on screen. */
    @Test fun `a pick whose row is gone is let go`() {
        drawer.setPrompt("!!")
        drawer.pickUp(installed[1].key)
        state.toggleFavorite(permit, installed[1].key)  // no longer a favorite, so no longer a row
        drawer.refilter()
        assertNull(drawer.reorderPick)
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
