package com.github.reygnn.verso_launcher

import android.app.Activity
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.TextWatcher
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView

/**
 * The back page: the list above the command line, what the list shows, and the stale-tap guard.
 * Decisions are LauncherLogic's; side effects go back to the Activity through the lambdas.
 *
 * The widgets stay inside: callers say what they want done (clear the prompt, scroll to the top),
 * so the rows, the reorder pick and the stale-tap generations change only here. Tests reach a
 * widget through the view tree (ViewTrees.collect).
 */
internal class AppDrawer(
    private val activity: Activity,
    private val state: LauncherState,
    private var palette: Palette,
    private val onAction: (DrawerAction<AppEntry>) -> Unit,
    private val onLongPress: (AppEntry) -> Unit,
    private val onFiltered: () -> Unit,
    private val onModeChanged: (Mode) -> Unit,
) {
    val view: LinearLayout

    private val outlinePx get() = HomeStyle.OUTLINE_DP * activity.resources.displayMetrics.density

    var mode = Mode.NORMAL
        private set

    // FAV_REORDER: the favorite picked up, or null.
    var reorderPick: String? = null
        private set

    // TAG_EDIT: the folded tag after "##"; null while the bare "##" overview is up.
    var tagEditTag: String? = null
        private set

    // Stale-tap guard (see TapGuard): bumped on every replacement of [shown].
    private var shownGeneration = 0
    // Live while the finger is down: the long-press fires before ACTION_UP.
    private var touchDownGeneration = TapGuard.NO_TOUCH

    // Kept past ACTION_UP for the click posted after it. Must stay separate from
    // touchDownGeneration — sharing one field silently disables one of the two guards.
    private var clickDownGeneration = TapGuard.NO_TOUCH

    private var shown: List<Row<AppEntry>> = emptyList()

    private lateinit var clearButton: OutlinedTextView
    // Recoloured in place: the Editor loads its cursor drawable once and ignores a new one.
    private val cursorDrawable = GradientDrawable()
    private lateinit var emptyView: OutlinedTextView
    private lateinit var listView: ListView
    private lateinit var prompt: OutlinedEditText
    private lateinit var adapter: AppListAdapter

    private val listTypeface: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)

    init {
        // No background or scrim (a scrim flickered on the wallpaper): the drawer hides home instead.
        view = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = false
            visibility = View.GONE
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        }

        // Labels the click of a row for screen readers ("double-tap to hide"), keeping the list's own
        // item click: a delegate on the row would replace the one the list sets there.
        listView = object : ListView(activity) {
            override fun onInitializeAccessibilityNodeInfoForItem(view: View, position: Int, info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfoForItem(view, position, info)
                val entry = (shown.getOrNull(position) as? AppRow)?.entry ?: return
                rowA11y(entry).second?.let { info.addAction(AccessibilityNodeInfo.AccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, it)) }
            }
        }.apply {
            divider = null
            // A hardware keyboard's focus can land in the list: send typing back to the prompt.
            setOnKeyListener { _, _, event ->
                val c = if (event.action == KeyEvent.ACTION_DOWN) printable(event) else null
                if (c != null) typeIntoPrompt(c)
                c != null
            }
            isVerticalScrollBarEnabled = false
            isStackFromBottom = true
            // Observe only, never consume: the ListView still scrolls and detects clicks.
            @Suppress("ClickableViewAccessibility")
            setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        touchDownGeneration = shownGeneration
                        clickDownGeneration = TapGuard.NO_TOUCH
                    }
                    MotionEvent.ACTION_UP -> {
                        clickDownGeneration = touchDownGeneration
                        touchDownGeneration = TapGuard.NO_TOUCH
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        touchDownGeneration = TapGuard.NO_TOUCH
                        clickDownGeneration = TapGuard.NO_TOUCH
                    }
                }
                false
            }
            // A touch that became a scroll makes no click: it must leave no mark that would void
            // the next click without a touch (keyboard, Switch Access).
            setOnScrollListener(object : AbsListView.OnScrollListener {
                override fun onScrollStateChanged(view: AbsListView, scrollState: Int) {
                    if (scrollState == AbsListView.OnScrollListener.SCROLL_STATE_TOUCH_SCROLL) {
                        touchDownGeneration = TapGuard.NO_TOUCH
                    }
                }

                override fun onScroll(view: AbsListView, first: Int, visible: Int, total: Int) = Unit
            })
            setOnItemClickListener { _, _, position, _ ->
                if (!clickStillValid()) return@setOnItemClickListener
                // getOrNull: a load may have shrunk shown before this posted click runs.
                onAction(LauncherLogic.tapAction(mode, shown.getOrNull(position)))
            }
            setOnItemLongClickListener { _, _, position, _ ->
                val row = shown.getOrNull(position) as? AppRow
                    ?: return@setOnItemLongClickListener false
                // Row type before the guard: a tag row must not consume the marker.
                if (!longPressStillValid()) return@setOnItemLongClickListener false
                onLongPress(row.entry)
                true
            }
        }
        emptyView = OutlinedTextView(activity).apply {
            textSize = 18f
            // Italic like the prompt's hint: a hint, not an app row.
            setTypeface(listTypeface, Typeface.ITALIC)
            gravity = Gravity.BOTTOM or Gravity.START
            setPadding(0, 0, 0, 10.dp())
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        listView.emptyView = emptyView
        val listFrame = FrameLayout(activity).apply {
            addView(listView, FrameLayout.LayoutParams(MATCH, MATCH))
            addView(emptyView, FrameLayout.LayoutParams(MATCH, MATCH))
        }
        view.addView(listFrame, LinearLayout.LayoutParams(MATCH, 0, 1f))

        clearButton = OutlinedTextView(activity).apply {
            text = "×"
            typeface = Typeface.MONOSPACE
            textSize = 24f
            setTextColor(palette.text)
            gravity = Gravity.CENTER
            minWidth = 48.dp()
            contentDescription = activity.getString(R.string.clear_prompt)
            isFocusable = false  // keep focus (and the IME) on the prompt when tapped
            visibility = View.GONE
            setOnClickListener {
                prompt.setText("")
                showKeyboard()
            }
        }

        prompt = OutlinedEditText(activity).apply {
            // In the text colour, set apart by the slant alone.
            hint = SpannableString(activity.getString(R.string.hint_search)).apply {
                setSpan(StyleSpan(Typeface.ITALIC), 0, length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
            }
            setHintTextColor(palette.text)
            setTextColor(palette.text)
            textCursorDrawable = cursorDrawable.apply { setSize(2.dp(), 1) }
            typeface = Typeface.MONOSPACE
            textSize = 20f
            background = null
            // Room for the outline's outer half: the view clips at its bounds.
            setPadding(2.dp(), 2.dp(), 2.dp(), 2.dp())
            isSingleLine = true
            // A pasted line break would only look like a space here: it becomes one (LauncherLogic.promptRun).
            filters = arrayOf(InputFilter { source, start, end, _, _, _ ->
                LauncherLogic.promptRun(source.subSequence(start, end))
            })
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_GO
            // TalkBack reads the visible hint's ">" as "greater than", and on a page turn it speaks the
            // focused field, not the pane title (seen on the A17): a spoken hint that names the page.
            accessibilityDelegate = object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    val spoken = activity.getString(R.string.a11y_prompt_hint)
                    info.hintText = spoken
                    // Empty, the field reports its hint as its text too.
                    if (info.isShowingHintText) info.text = spoken
                }

                // The focus event carries the visible hint as well, and that is what TalkBack speaks
                // as the drawer opens (the node alone changed nothing on the A17).
                override fun onPopulateAccessibilityEvent(host: View, event: AccessibilityEvent) {
                    super.onPopulateAccessibilityEvent(host, event)
                    if ((host as TextView).text.isNullOrEmpty()) {
                        event.text.clear()
                        event.text.add(activity.getString(R.string.a11y_prompt_hint))
                    }
                }
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    val text = s?.toString().orEmpty()
                    clearButton.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
                    applyFilter(text)
                }
            })
            setOnEditorActionListener { _, actionId, event ->
                // A key Enter calls this for down, repeats and up: only the first down acts.
                val imeAction = actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE
                val key = event?.let {
                    EnterKey(
                        down = it.action == KeyEvent.ACTION_DOWN,
                        repeatCount = it.repeatCount,
                        isEnterKey = it.keyCode == KeyEvent.KEYCODE_ENTER || it.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER,
                    )
                }
                when (LauncherLogic.enterEdge(imeAction, key)) {
                    EnterEdge.PASS -> return@setOnEditorActionListener false
                    EnterEdge.CONSUME -> return@setOnEditorActionListener true
                    EnterEdge.ACT -> Unit
                }
                val command = LauncherLogic.resolveCommand(promptText.trim())
                // The last row sits right above the prompt: it is Enter's target.
                onAction(
                    LauncherLogic.enterAction(
                        mode = mode,
                        command = command,
                        promptBlank = prompt.text.isNullOrBlank(),
                        lastRow = shown.lastOrNull(),
                    )
                )
                true
            }
        }
        val promptRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(8.dp(), 0, 8.dp(), 0)
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = 48.dp()
            addView(prompt, LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(clearButton, LinearLayout.LayoutParams(WRAP, MATCH))
        }
        view.addView(promptRow, LinearLayout.LayoutParams(MATCH, WRAP))

        // Padding (not translation) so the list shrinks and stays scrollable. No
        // WindowInsetsAnimation: a per-frame relayout makes the list blink. Bars ignoring
        // visibility, or the list jumps as the status bar toggles mid-turn (hard rule 12).
        val pad = 12.dp()
        view.setOnApplyWindowInsetsListener { v, insets ->
            val i = android.graphics.Insets.max(
                insets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()),
                insets.getInsets(WindowInsets.Type.ime()),
            )
            v.setPadding(i.left + pad, i.top + pad, i.right + pad, i.bottom + pad)
            insets
        }

        adapter = AppListAdapter()
        listView.adapter = adapter
    }

    fun applyPalette(p: Palette) {
        palette = p
        prompt.setTextColor(palette.text)
        prompt.setHintTextColor(palette.text)
        clearButton.setTextColor(palette.text)
        prompt.setOutline(outlinePx, palette.outline)
        clearButton.setOutline(outlinePx, palette.outline)
        // The theme's accent all but vanishes on a light wallpaper.
        cursorDrawable.setColor(p.text)
        prompt.invalidate()
        prompt.highlightColor = p.selection
        prompt.textSelectHandle?.let { prompt.setTextSelectHandle(it.mutate().apply { setTint(p.text) }) }
        prompt.textSelectHandleLeft?.let { prompt.setTextSelectHandleLeft(it.mutate().apply { setTint(p.text) }) }
        prompt.textSelectHandleRight?.let { prompt.setTextSelectHandleRight(it.mutate().apply { setTint(p.text) }) }
        emptyView.setTextColor(palette.text)
        emptyView.setOutline(outlinePx, palette.outline)
        adapter.notifyDataSetChanged()
    }

    /** The rows' markers changed, the row set did not. */
    fun notifyChanged() = adapter.notifyDataSetChanged()

    val promptText: String get() = prompt.text?.toString().orEmpty()

    /** Synchronous: the TextWatcher has re-filtered (mode back to NORMAL) before this returns. */
    fun clearPrompt() = setPrompt("")

    /** The drawer is turning away: its prompt must not keep the focus (and with it the IME). */
    fun releasePromptFocus() = prompt.clearFocus()

    /** The list's visual top is showing: a drag down there is the page's, not the list's. */
    val listAtTop: Boolean get() = !listView.canScrollVertically(-1)

    /** The list's visual bottom is showing (the recents close upward from there). */
    val listAtBottom: Boolean get() = !listView.canScrollVertically(1)

    /** The A-to-Z list rises from its top, not where it was last left. */
    fun scrollToTop() {
        if (shown.isNotEmpty()) listView.setSelection(0)
    }

    /** The same prompt, the rows built anew: the "%" order changes with every launch. */
    fun rebuildRows() = applyFilter(promptText)

    /** "!!": the favorite [key] is held until it is dropped onto another row or tapped again. */
    fun pickUp(key: String) {
        reorderPick = key
        adapter.notifyDataSetChanged()
    }

    fun dropPick() {
        reorderPick = null
        adapter.notifyDataSetChanged()
    }

    private fun applyFilter(raw: String) {
        val q = raw.trim()
        val previous = mode
        mode = LauncherLogic.parseMode(q)
        // The recents open with focus on their first row: the list's name says where that is.
        val listName = activity.getString(if (mode == Mode.RECENTS) R.string.a11y_pane_recents else R.string.a11y_pane_drawer)
        if (listView.contentDescription != listName) listView.contentDescription = listName
        // Only on change: restarting input on every keystroke makes the IME flicker.
        val wantedAction = if (LauncherLogic.isEditMode(mode)) EditorInfo.IME_ACTION_DONE else EditorInfo.IME_ACTION_GO
        if (prompt.imeOptions != wantedAction) {
            prompt.imeOptions = wantedAction
            // Posted: we are inside the IME's own edit; keys sent before the rebind would be lost.
            prompt.post { activity.getSystemService(InputMethodManager::class.java).restartInput(prompt) }
        }
        // The bare drawer reads top-down; everything else sits above the prompt, where Enter acts.
        val topAnchored = mode == Mode.NORMAL && q.isEmpty()
        val anchorFlipped = listView.isStackFromBottom == topAnchored
        if (anchorFlipped) listView.isStackFromBottom = !topAnchored
        if (mode != Mode.FAV_REORDER) reorderPick = null
        tagEditTag = LauncherLogic.tagEditTagFor(mode, q)
        shown = LauncherLogic.rowsFor(
            mode = mode,
            trimmed = q,
            allApps = state.apps,
            hidden = state.config.hidden,
            favorites = state.config.favorites,
            tags = state.config.tags,
            recentKeys = state.recentKeys,
            tagEditTag = tagEditTag,
            byUsage = state.config.byUsage,
            usage = state.usage,
            now = System.currentTimeMillis(),
        )
        // A pick whose row is gone would make the next tap move an invisible favorite.
        if (reorderPick != null && shown.none { (it as? AppRow)?.entry?.key == reorderPick }) reorderPick = null
        // The one place shown is assigned.
        shownGeneration++
        // Not before the apps load ("no apps" would be false). Only on change: a live region
        // would make TalkBack repeat it on every keystroke.
        val hint = if (!state.appsLoaded) "" else activity.getString(
            when (LauncherLogic.emptyHint(mode, q, tagEditTag)) {
                EmptyHint.NO_MATCH -> R.string.empty_no_match
                EmptyHint.NO_APPS -> R.string.empty_no_apps
                EmptyHint.NO_TAGS -> R.string.empty_no_tags
                EmptyHint.NO_TAG_MATCH -> R.string.empty_no_tag_match
                EmptyHint.NO_FAVORITES -> R.string.empty_no_favorites
                EmptyHint.NO_RECENTS -> R.string.empty_no_recents
                EmptyHint.NO_COMMAND -> R.string.empty_no_command
            }
        )
        if (emptyView.text.toString() != hint) emptyView.text = hint
        adapter.notifyDataSetChanged()
        // On an anchor flip the ListView keeps the old position: pin it to the right end.
        if (anchorFlipped && topAnchored && shown.isNotEmpty()) listView.setSelection(0)
        if (anchorFlipped && !topAnchored && shown.isNotEmpty()) listView.setSelection(shown.lastIndex)
        if (mode != previous) onModeChanged(mode)
    }

    /** After a change to the apps or the config; typing alone never changes the favorites. */
    fun refilter() {
        applyFilter(promptText)
        onFiltered()
    }

    fun typeIntoPrompt(c: String) {
        prompt.requestFocus()
        prompt.append(c)
        prompt.setSelection(prompt.length())
    }

    /** Consumes the marker, so only the click this touch leads to is guarded. */
    private fun clickStillValid(): Boolean {
        val downGeneration = clickDownGeneration
        clickDownGeneration = TapGuard.NO_TOUCH
        return TapGuard.stillValid(downGeneration, shownGeneration)
    }

    private fun longPressStillValid(): Boolean =
        TapGuard.stillValid(touchDownGeneration, shownGeneration)

    /** Caret at the end: setText alone parks it at 0, in front of a drilled "#tag". */
    fun setPrompt(text: String) {
        prompt.setText(text)
        prompt.setSelection(text.length)
    }

    fun showKeyboard() {
        // Not InputMethodManager.showSoftInput: unreliable straight from onResume.
        prompt.requestFocus()
        prompt.windowInsetsController?.show(WindowInsets.Type.ime())
    }

    fun hideKeyboard() {
        val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(prompt.windowToken, 0)
    }

    private fun Int.dp(): Int = activity.dp(this)

    private inner class AppListAdapter : BaseAdapter() {
        override fun getCount(): Int = shown.size
        override fun getItem(position: Int): Any = shown[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val tv = (convertView as? TextView) ?: OutlinedTextView(activity).apply {
                textSize = 20f
                gravity = Gravity.CENTER_VERTICAL
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
                val v = 10.dp()
                setPadding(0, v, 0, v)
            }
            // Idempotent: a recycled row already at this palette skips the setters (and the
            // ColorStateList each would allocate); one that predates a palette switch re-applies.
            (tv as? OutlinedTextView)?.applyRowPalette(palette.text, outlinePx, palette.outline)
                ?: tv.setTextColor(palette.text)
            when (val row = shown[position]) {
                is TagRow -> bindPlainRow(tv, listTypeface, row.name)
                is OrderRow -> bindPlainRow(
                    tv, Typeface.MONOSPACE,
                    activity.getString(if (row.toUsage) R.string.order_to_usage else R.string.order_to_name),
                )
                is CommandRow -> bindPlainRow(tv, Typeface.MONOSPACE, row.name)
                is AppRow -> bindAppRow(tv, row.entry)
            }
            return tv
        }

        /** A row without an app; clears what a recycled app row set (edit-mode description, dimming). */
        private fun bindPlainRow(tv: TextView, face: Typeface, text: String) {
            tv.typeface = face
            tv.text = text
            tv.contentDescription = null
            tv.stateDescription = null
            tv.alpha = 1f
        }

        private fun bindAppRow(tv: TextView, entry: AppEntry) {
            val key = entry.key
            val marker = LauncherLogic.rowPrefix(
                mode,
                isHidden = key in state.config.hidden,
                isFavorite = key in state.config.favorites,
                isPicked = key == reorderPick,
                isTagged = tagEditTag in state.config.tags[key].orEmpty(),
            )
            // A marker column only lines up in monospace.
            tv.typeface = if (marker.isEmpty()) listTypeface else Typeface.MONOSPACE
            tv.text = marker + entry.label
            tv.alpha = HomeStyle.rowAlpha(entry.status)
            // "[x]" reads as punctuation: the name alone, the state beside it. Set on every bind (recycling).
            tv.contentDescription = if (marker.isEmpty()) null else entry.label
            tv.stateDescription = rowA11y(entry).first
        }
    }

    /**
     * A row as a screen reader takes it: its state ("hidden") and what a double-tap does ("unhide"),
     * both null where there is nothing to say. Pure but for the strings.
     */
    private fun rowA11y(entry: AppEntry): Pair<String?, String?> {
        val key = entry.key
        fun s(id: Int, vararg args: Any) = activity.getString(id, *args)
        return when (mode) {
            Mode.HIDDEN_EDIT ->
                if (key in state.config.hidden) s(R.string.a11y_state_hidden) to s(R.string.a11y_do_unhide)
                else s(R.string.a11y_state_visible) to s(R.string.a11y_do_hide)
            Mode.FAV_EDIT ->
                if (key in state.config.favorites) s(R.string.a11y_state_favorite) to s(R.string.a11y_do_unfavorite)
                else s(R.string.a11y_state_not_favorite) to s(R.string.a11y_do_favorite)
            Mode.FAV_REORDER -> when {
                reorderPick == null -> null to s(R.string.a11y_do_pick_up)
                key == reorderPick -> s(R.string.a11y_state_picked_up) to s(R.string.a11y_do_put_back)
                else -> null to s(R.string.a11y_do_move_here)
            }
            Mode.TAG_EDIT -> {
                val tag = tagEditTag.orEmpty()
                if (tag in state.config.tags[key].orEmpty()) s(R.string.a11y_state_tagged, tag) to s(R.string.a11y_do_untag)
                else s(R.string.a11y_state_not_tagged, tag) to s(R.string.a11y_do_tag)
            }
            Mode.NORMAL, Mode.RECENTS, Mode.TAG_FILTER, Mode.COMMAND, Mode.ORDER ->
                statusState(activity, entry.status) to statusAction(activity, entry.status)
        }
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
