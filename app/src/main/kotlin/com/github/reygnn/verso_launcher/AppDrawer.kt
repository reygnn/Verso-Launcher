package com.github.reygnn.verso_launcher

import android.app.Activity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.LinearLayout

/**
 * The back page: the list ([DrawerList]) above the command line ([DrawerPrompt]). Every change of
 * the prompt or the data is one [LauncherLogic.drawerView], which the list draws and which answers
 * an Enter or a tap; the side effects go back to the Activity through the lambdas.
 *
 * The widgets stay inside the two parts: callers say what they want done (set the prompt, scroll
 * to the top). Tests reach a widget through the view tree (ViewTrees.collect).
 */
internal class AppDrawer(
    activity: Activity,
    private val state: LauncherState,
    palette: Palette,
    private val onAction: (DrawerAction<AppEntry>) -> Unit,
    onLongPress: (AppEntry) -> Unit,
    private val onFiltered: () -> Unit,
    private val onModeChanged: (Mode) -> Unit,
) {
    // What is on screen. Before the first filter: the bare drawer, nothing listed yet.
    private var shown: DrawerView<AppEntry> =
        DrawerView(Mode.NORMAL, trimmed = "", rows = emptyList(), topAnchored = true, emptyHint = null)

    val mode: Mode get() = shown.mode

    private val list = DrawerList(
        activity = activity,
        palette = palette,
        onTap = { position -> onAction(shown.tap(position)) },
        onLongPress = onLongPress,
        onType = ::typeIntoPrompt,
    )

    private val prompt = DrawerPrompt(
        activity = activity,
        palette = palette,
        onChanged = ::applyFilter,
        onEnter = { onAction(shown.enter()) },
    )

    // No background or scrim (a scrim flickered on the wallpaper): the drawer hides home instead.
    val view: LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        fitsSystemWindows = false
        visibility = View.GONE
        descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        addView(list.view, LinearLayout.LayoutParams(MATCH, 0, 1f))
        addView(prompt.view, LinearLayout.LayoutParams(MATCH, WRAP))
        // Padding (not translation) so the list shrinks and stays scrollable. No
        // WindowInsetsAnimation: a per-frame relayout makes the list blink. Bars ignoring
        // visibility, or the list jumps as the status bar toggles mid-turn (hard rule 12).
        val pad = activity.dp(12)
        setOnApplyWindowInsetsListener { v, insets ->
            val i = android.graphics.Insets.max(
                insets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()),
                insets.getInsets(WindowInsets.Type.ime()),
            )
            v.setPadding(i.left + pad, i.top + pad, i.right + pad, i.bottom + pad)
            insets
        }
    }

    fun applyPalette(p: Palette) {
        prompt.applyPalette(p)
        list.applyPalette(p)
    }

    val promptText: String get() = prompt.text

    /**
     * Sets the prompt, the caret at its end. Synchronous: the rows are filtered and [mode] is the
     * new text's before this returns, so a command may clear the prompt and then run.
     */
    fun setPrompt(text: String) = prompt.set(text)

    /** The drawer is turning away: its prompt must not keep the focus (and with it the IME). */
    fun releasePromptFocus() = prompt.releaseFocus()

    /** The list's visual top is showing: a drag down there is the page's, not the list's. */
    val listAtTop: Boolean get() = list.atTop

    /** The list's visual bottom is showing (the recents close upward from there). */
    val listAtBottom: Boolean get() = list.atBottom

    /** The A-to-Z list rises from its top, not where it was last left. */
    fun scrollToTop() = list.scrollToTop()

    /** The same prompt, the rows built anew: the "%" order changes with every launch. */
    fun rebuildRows() = applyFilter(promptText)

    /** After a change to the apps or the config; typing alone never changes the favorites. */
    fun refilter() {
        applyFilter(promptText)
        onFiltered()
    }

    fun typeIntoPrompt(c: String) = prompt.type(c)

    fun showKeyboard() = prompt.showKeyboard()

    fun hideKeyboard() = prompt.hideKeyboard()

    private fun applyFilter(raw: String) {
        val previous = shown.mode
        shown = LauncherLogic.drawerView(
            raw,
            DrawerData(
                apps = state.apps,
                loaded = state.appsLoaded,
                hidden = state.config.hidden,
                favorites = state.config.favorites,
                tags = state.config.tags,
                recentKeys = state.recentKeys,
                byUsage = state.config.byUsage,
                usage = state.usage,
                now = System.currentTimeMillis(),
                report = state::report,
            ),
        )
        list.show(shown)
        if (shown.mode != previous) onModeChanged(shown.mode)
    }

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}

/** The character a plain key press types, or null — see [LauncherLogic.typedChar]. */
internal fun printable(event: KeyEvent): String? = LauncherLogic.typedChar(
    unicodeChar = event.unicodeChar,
    chord = event.isCtrlPressed || event.isMetaPressed ||
        event.metaState and KeyEvent.META_ALT_LEFT_ON != 0,
)
