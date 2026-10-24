package com.github.reygnn.verso_launcher

import android.app.Activity
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
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
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
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
    private lateinit var promptRow: LinearLayout
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

        listView = ListView(activity).apply {
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
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_GO
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
        promptRow = LinearLayout(activity).apply {
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
            // Every bind, not only on creation: a recycled row may predate a palette switch.
            tv.setTextColor(palette.text)
            (tv as? OutlinedTextView)?.setOutline(outlinePx, palette.outline)
            when (val row = shown[position]) {
                // Clear a recycled row's edit-mode description.
                is TagRow -> {
                    tv.typeface = listTypeface
                    tv.text = row.name
                    tv.contentDescription = null
                    tv.alpha = 1f
                }
                is OrderRow -> {
                    tv.typeface = Typeface.MONOSPACE
                    tv.text = activity.getString(if (row.toUsage) R.string.order_to_usage else R.string.order_to_name)
                    tv.contentDescription = null
                    tv.alpha = 1f
                }
                is CommandRow -> {
                    tv.typeface = Typeface.MONOSPACE
                    tv.text = row.name
                    tv.contentDescription = null
                    tv.alpha = 1f
                }
                is AppRow -> bindAppRow(tv, row.entry)
            }
            return tv
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
            // "[x]" reads as punctuation: speak the state instead. Set on every bind (recycling).
            tv.contentDescription = when (mode) {
                Mode.HIDDEN_EDIT -> activity.getString(
                    if (key in state.config.hidden) R.string.a11y_hidden_on else R.string.a11y_hidden_off,
                    entry.label,
                )
                Mode.FAV_EDIT -> activity.getString(
                    if (key in state.config.favorites) R.string.a11y_fav_on else R.string.a11y_fav_off,
                    entry.label,
                )
                Mode.FAV_REORDER -> activity.getString(
                    when {
                        reorderPick == null -> R.string.a11y_reorder_pick
                        key == reorderPick  -> R.string.a11y_reorder_picked
                        else                -> R.string.a11y_reorder_drop
                    },
                    entry.label,
                )
                Mode.TAG_EDIT -> activity.getString(
                    if (tagEditTag in state.config.tags[key].orEmpty()) R.string.a11y_tag_on
                    else R.string.a11y_tag_off,
                    entry.label,
                    tagEditTag.orEmpty(),
                )
                Mode.NORMAL, Mode.RECENTS, Mode.TAG_FILTER, Mode.COMMAND, Mode.ORDER -> unavailableDescription(activity, entry)
            }
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
