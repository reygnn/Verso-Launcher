package com.github.reygnn.verso_launcher

import android.app.Activity
import android.graphics.Typeface
import android.text.Spanned
import android.text.style.StyleSpan
import android.graphics.Insets
import android.os.Looper
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.AbsListView
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.util.ReflectionHelpers

/** A real [LauncherState] whose IO and main "threads" run inline. */
@RunWith(RobolectricTestRunner::class)
class AppDrawerTest {

    private val activity: Activity = Robolectric.buildActivity(Activity::class.java).setup().get()

    private fun app(label: String) = AppEntry(label, "com.${label.lowercase()}", "com.${label.lowercase()}.Main", label)
    private val installed = listOf(app("Alpha"), app("Bravo"), app("Charlie"))

    private val state = testLauncherState(
        io = { task -> task.run() },
        main = { task -> task.run() },
        config = VersoConfig(favorites = listOf(installed[1].key)),
        enumerateApps = { Enumeration(installed, true) },
        onRefreshed = {},
    ).also { it.refresh() }

    private val taps = mutableListOf<DrawerAction<AppEntry>>()
    private var filtered = 0
    private val modes = mutableListOf<Mode>()
    private val longPresses = mutableListOf<AppEntry>()
    private val drawer = AppDrawer(
        activity = activity,
        state = state,
        palette = HomeStyle.palette(lightWallpaper = false),
        onAction = { taps += it },
        onLongPress = { longPresses += it },
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
            io = { task -> task.run() },
            main = { task -> task.run() },
            enumerateApps = { Enumeration(installed + paused, true) },
            onRefreshed = {},
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

    /** The edit sigils are gone: "!" is text like any other, and no label holds it. */
    @Test fun `an old edit sigil is a plain search`() {
        drawer.setPrompt("!")
        assertEquals(Mode.NORMAL, drawer.mode)
        assertEquals(emptyList<String>(), rows())
    }

    @Test fun `an empty list says why, per mode`() {
        drawer.setPrompt("zzz")
        assertEquals(activity.getString(R.string.empty_no_match), emptyHint.text.toString())
        drawer.setPrompt("#")
        assertEquals(activity.getString(R.string.empty_no_tags), emptyHint.text.toString())
        drawer.setPrompt("~zz")
        assertEquals(activity.getString(R.string.empty_no_command), emptyHint.text.toString())
        drawer.setPrompt("#zz")
        assertEquals(activity.getString(R.string.empty_no_tag_match), emptyHint.text.toString())
        drawer.setPrompt("?")
        assertEquals(activity.getString(R.string.empty_no_recents), emptyHint.text.toString())
    }

    @Test fun `no apps at all says so`() {
        val drawer = drawerOf(apps = emptyList())
        drawer.setPrompt("")
        assertEquals(activity.getString(R.string.empty_no_apps), (drawer.list().emptyView as TextView).text.toString())
    }

    /** Before the first list arrives, "no apps" would be false, so the hint stays empty. */
    @Test fun `before the apps are loaded the empty list says nothing`() {
        val drawer = drawerOf(apps = installed, loaded = false)
        drawer.setPrompt("zzz")
        assertEquals("", (drawer.list().emptyView as TextView).text.toString())
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

    private fun rowView(position: Int): TextView = list.adapter.getView(position, null, list) as TextView

    /** What TalkBack would say a double-tap on row [position] does; null for its own words. */
    private fun clickLabel(position: Int): CharSequence? {
        val info = AccessibilityNodeInfo()
        list.onInitializeAccessibilityNodeInfoForItem(rowView(position), position, info)
        return info.actionList.firstOrNull { it.id == AccessibilityNodeInfo.ACTION_CLICK }?.label
    }

    @Test fun `a plain row says nothing beyond its label, and a recycled row forgets what it was`() {
        drawer.setPrompt("~")                     // command rows: monospace, no app behind them
        val recycled = rowView(0)
        drawer.setPrompt("")
        val row = list.adapter.getView(0, recycled, list) as TextView
        assertEquals("Alpha", row.text.toString())
        assertNull(row.contentDescription)
        assertNull(row.stateDescription)
        assertNull(clickLabel(0))
    }

    /** A finger down and up on the list; [scrolled]: it moved far enough to scroll, as the list reports it. */
    private fun touch(scrolled: Boolean) {
        list.dispatchTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 10f, 10f, 0))
        if (scrolled) {
            // The list's own report; a Robolectric list is never laid out tall enough to scroll.
            ReflectionHelpers.getField<AbsListView.OnScrollListener?>(list, "mOnScrollListener")
                ?.onScrollStateChanged(list, AbsListView.OnScrollListener.SCROLL_STATE_TOUCH_SCROLL)
        }
        list.dispatchTouchEvent(MotionEvent.obtain(0, 50, MotionEvent.ACTION_UP, 10f, 10f, 0))
    }

    /** A scroll makes no click, so it leaves no mark: keyboard, Switch Access and Voice Access click without a touch. */
    @Test fun `a click without a touch after a scroll and a new list goes through`() {
        drawer.setPrompt("")
        touch(scrolled = true)
        drawer.setPrompt("br")
        list.performItemClick(null, 0, 0)
        assertEquals(listOf<DrawerAction<AppEntry>>(DrawerAction.Launch(installed[1])), taps)
    }

    /** A long press takes the gesture too (App info, after which the list may have changed). */
    @Test fun `a click without a touch after a long press and a new list goes through`() {
        drawer.setPrompt("")
        list.dispatchTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 10f, 10f, 0))
        // The list fires it while the finger is still down.
        list.onItemLongClickListener.onItemLongClick(list, rowView(0), 0, 0)
        list.dispatchTouchEvent(MotionEvent.obtain(0, 600, MotionEvent.ACTION_UP, 10f, 10f, 0))
        drawer.setPrompt("br")
        list.performItemClick(null, 0, 0)
        assertEquals(listOf<DrawerAction<AppEntry>>(DrawerAction.Launch(installed[1])), taps)
    }

    /** The guard itself: a tap whose list was replaced before its click lands on no other row. */
    @Test fun `the click of a tap whose list was replaced meanwhile is dropped`() {
        drawer.setPrompt("")
        touch(scrolled = false)
        drawer.setPrompt("br")
        list.performItemClick(null, 0, 0)
        assertEquals(emptyList<DrawerAction<AppEntry>>(), taps)
    }

    /** runCommand and Back rely on it: the mode is NORMAL again before the call returns. */
    @Test fun `clearing the prompt is the plain drawer at once`() {
        drawer.setPrompt("?")
        drawer.clearPrompt()
        assertEquals("", drawer.promptText)
        assertEquals(Mode.NORMAL, drawer.mode)
    }

    // ---- a drawer of its own --------------------------------------------------------------

    /** A drawer over [apps] with [config]. If [loaded] is false, no app list has arrived yet. */
    private fun drawerOf(apps: List<AppEntry>, config: VersoConfig = VersoConfig(), loaded: Boolean = true): AppDrawer {
        val state = testLauncherState(
            io = { task -> task.run() },
            main = { task -> task.run() },
            config = config,
            enumerateApps = { Enumeration(apps, true) },
            onRefreshed = {},
        )
        if (loaded) state.refresh()
        return AppDrawer(
            activity = activity,
            state = state,
            palette = HomeStyle.palette(lightWallpaper = false),
            onAction = { taps += it },
            onLongPress = { longPresses += it },
            onFiltered = {},
            onModeChanged = {},
        )
    }

    private fun AppDrawer.list(): ListView = view.collect<ListView>().single()

    private fun AppDrawer.row(position: Int): TextView = list().let { it.adapter.getView(position, null, it) as TextView }

    private val tagged = drawerOf(installed, VersoConfig(tags = mapOf(installed[0].key to listOf("work"))))

    // ---- Enter --------------------------------------------------------------------------

    @Test fun `Enter launches the row right above the prompt`() {
        drawer.setPrompt("a")
        val shown = rows()
        assertTrue("several rows: $shown", shown.size > 1)
        prompt.onEditorAction(EditorInfo.IME_ACTION_GO)
        assertEquals(listOf(shown.last()), taps.map { (it as DrawerAction.Launch).entry.label })
    }

    private fun key(action: Int, code: Int, repeat: Int = 0) = KeyEvent(0L, 0L, action, code, repeat)

    /** A hardware Enter calls the listener for its down, for every repeat and for its up; only the first down may act. */
    @Test fun `a held hardware Enter acts once`() {
        drawer.setPrompt("br")
        prompt.dispatchKeyEvent(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
        prompt.dispatchKeyEvent(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, repeat = 1))
        prompt.dispatchKeyEvent(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        assertEquals(listOf<DrawerAction<AppEntry>>(DrawerAction.Launch(installed[1])), taps)
    }

    @Test fun `the number pad's Enter is an Enter`() {
        drawer.setPrompt("br")
        prompt.dispatchKeyEvent(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_NUMPAD_ENTER))
        prompt.dispatchKeyEvent(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_NUMPAD_ENTER))
        assertEquals(listOf<DrawerAction<AppEntry>>(DrawerAction.Launch(installed[1])), taps)
    }

    /** The prompt's own editor-action listener, as the field calls it. */
    private val editorAction: TextView.OnEditorActionListener
        get() {
            val editor = ReflectionHelpers.getField<Any>(prompt, "mEditor")
            val contentType = ReflectionHelpers.getField<Any>(editor, "mInputContentType")
            return ReflectionHelpers.getField(contentType, "onEditorActionListener")
        }

    /**
     * A key that arrives without the field's action: only the key code tells an Enter.
     *
     * Why: on API 36 the field calls the listener for a hardware Enter with IME_ACTION_GO, so the
     *   key code decides only on this path, which the test above cannot reach.
     */
    @Test fun `a key without an action counts only if it is an Enter`() {
        drawer.setPrompt("br")
        assertFalse(editorAction.onEditorAction(prompt, EditorInfo.IME_NULL, key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_B)))
        assertEquals(emptyList<DrawerAction<AppEntry>>(), taps)
        assertTrue(editorAction.onEditorAction(prompt, EditorInfo.IME_NULL, key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_NUMPAD_ENTER)))
        assertTrue(editorAction.onEditorAction(prompt, EditorInfo.IME_NULL, key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER)))
        assertEquals(List(2) { DrawerAction.Launch(installed[1]) }, taps)
    }

    // ---- the list's anchor, the clear button, keys in the list ---------------------------

    /** The bare drawer reads from the top. Every other list sits on the prompt, where Enter acts. */
    @Test fun `only the bare drawer hangs from the top`() {
        drawer.setPrompt("")
        assertFalse(list.isStackFromBottom)
        drawer.setPrompt("a")
        assertTrue(list.isStackFromBottom)
        drawer.setPrompt("?")
        assertTrue(list.isStackFromBottom)
        drawer.setPrompt("")
        assertFalse(list.isStackFromBottom)
    }

    private val clearButton: TextView get() = drawer.view.collect<TextView>().single { it.text.toString() == "×" }

    @Test fun `the clear button comes with text, and a tap on it empties the prompt`() {
        drawer.setPrompt("")
        assertEquals(View.GONE, clearButton.visibility)
        drawer.setPrompt("?a")
        assertEquals(View.VISIBLE, clearButton.visibility)
        clearButton.performClick()
        assertEquals("", drawer.promptText)
        assertEquals(Mode.NORMAL, drawer.mode)
        assertEquals(View.GONE, clearButton.visibility)
    }

    /** A hardware keyboard's focus can land in the list, and what it types must still reach the prompt. */
    @Test fun `a key typed into the list goes to the prompt`() {
        drawer.setPrompt("")
        // A view group takes a key itself only while it is focused and laid out, so the list is shown first.
        activity.setContentView(drawer.view)
        drawer.view.visibility = View.VISIBLE
        drawer.view.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(list.requestFocus())
        assertTrue(list.dispatchKeyEvent(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_B)))
        assertFalse("the release types nothing", list.dispatchKeyEvent(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_B)))
        assertEquals("b", drawer.promptText)
        assertEquals(listOf("Bravo"), rows())
    }

    // ---- long press ---------------------------------------------------------------------

    private fun longPress(list: ListView, position: Int): Boolean =
        list.onItemLongClickListener.onItemLongClick(list, list.adapter.getView(position, null, list), position, 0)

    private fun fingerDown(list: ListView) =
        list.dispatchTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 10f, 10f, 0))

    @Test fun `a long press on an app row is App info`() {
        drawer.setPrompt("br")
        fingerDown(list)
        assertTrue(longPress(list, 0))
        assertEquals(listOf(installed[1]), longPresses)
    }

    /** A tag row has no App info. The press is not taken, so the list goes on with the touch. */
    @Test fun `a long press on a tag row is not taken`() {
        tagged.setPrompt("#")
        val list = tagged.list()
        fingerDown(list)
        assertFalse(longPress(list, 0))
        assertEquals(emptyList<AppEntry>(), longPresses)
    }

    /** If the finger came down on another list, the press would land on a row it never touched. */
    @Test fun `a long press whose list was replaced meanwhile is dropped`() {
        drawer.setPrompt("")
        fingerDown(list)
        drawer.setPrompt("br")
        assertFalse(longPress(list, 0))
        assertEquals(emptyList<AppEntry>(), longPresses)
    }

    // ---- insets -------------------------------------------------------------------------

    /** The bars count by their size whether they are shown or not (hard rule 12); the keyboard counts where it is larger. */
    @Test fun `the drawer pads for the bars or the keyboard, whichever is larger, plus a margin`() {
        val pad = activity.dp(12)
        val bars = Insets.of(0, 80, 0, 40)
        fun insets(ime: Insets) = WindowInsets.Builder()
            .setInsets(WindowInsets.Type.systemBars(), Insets.NONE)
            .setInsetsIgnoringVisibility(WindowInsets.Type.systemBars(), bars)
            .setInsets(WindowInsets.Type.ime(), ime)
            .build()
        drawer.view.dispatchApplyWindowInsets(insets(ime = Insets.NONE))
        assertEquals(80 + pad, drawer.view.paddingTop)
        assertEquals(40 + pad, drawer.view.paddingBottom)
        drawer.view.dispatchApplyWindowInsets(insets(ime = Insets.of(0, 0, 0, 700)))
        assertEquals(80 + pad, drawer.view.paddingTop)
        assertEquals(700 + pad, drawer.view.paddingBottom)
    }

    // ---- what a row shows -----------------------------------------------------------------

    @Test fun `a paused app's row says paused and what a double-tap does`() {
        val paused = AppEntry("Delta", "com.delta", "com.delta.Main", "Delta", status = AppStatus.PAUSED)
        val drawer = drawerOf(installed + paused)
        drawer.setPrompt("delta")
        val list = drawer.list()
        val row = drawer.row(0)
        assertEquals(activity.getString(R.string.a11y_state_paused), row.stateDescription)
        val info = AccessibilityNodeInfo()
        list.onInitializeAccessibilityNodeInfoForItem(row, 0, info)
        val click = info.actionList.single { it.id == AccessibilityNodeInfo.ACTION_CLICK }
        assertEquals(activity.getString(R.string.a11y_do_turn_on_work), click.label)
    }

    /** The "%" row names what it turns the order into, in the command font. A tag row reads like an app row. */
    @Test fun `the order row and a tag row show their words in their fonts`() {
        drawer.setPrompt("%")
        val order = rowView(0)
        assertEquals(activity.getString(R.string.order_to_usage), order.text.toString())
        assertEquals(Typeface.MONOSPACE, order.typeface)
        tagged.setPrompt("")
        val appFont = tagged.row(0).typeface
        tagged.setPrompt("#")
        val tag = tagged.row(0)
        assertEquals("work", tag.text.toString())
        assertEquals(appFont, tag.typeface)
        assertNotEquals(Typeface.MONOSPACE, tag.typeface)
    }
}
