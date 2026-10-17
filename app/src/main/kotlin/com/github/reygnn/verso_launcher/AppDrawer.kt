package com.github.reygnn.verso_launcher

import android.app.Activity
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView

/**
 * The back page ("verso"): Sigil's list above the command line — the counterpart of [HomePage].
 * It owns the views (list, empty-list hint, prompt, ×, the prompt bar), what the list shows
 * (mode, rows, the reorder pick, the tag being edited) and the guard that keeps a tap on the
 * list it was aimed at. The decisions are LauncherLogic's; the side effects of a tap, a
 * long-press or Enter go back to the Activity through the lambdas, as HomePage does.
 * The page turn, the commands, dialogs and edits stay in the Activity.
 */
internal class AppDrawer(
    private val activity: Activity,
    private val state: LauncherState,
    private var palette: Palette,
    private val onTap: (TapAction<AppEntry>) -> Unit,
    private val onLongPress: (AppEntry) -> Unit,
    private val onEnter: (EnterAction<AppEntry>) -> Unit,
    private val onFiltered: () -> Unit,
) {
    /** The page itself: the list above the command line. */
    val view: LinearLayout

    // The drawer sits on the bare wallpaper like the home page, so its rows get the same tonal
    // outline (palette.listOutline) — readable on a bright or busy wallpaper too; its width:
    private val outlinePx get() = HomeStyle.OUTLINE_DP * activity.resources.displayMetrics.density

    var mode = Mode.NORMAL
        private set

    // In FAV_REORDER: the component key of the favorite currently "picked up",
    // or null when nothing is held. First tap picks a row up (marked » in
    // getView), the next tap on another row drops it there; tapping the held row
    // again cancels. Reset whenever the mode is left (see applyFilter), so a stale
    // key from an old reorder session can never move the wrong row later.
    var reorderPick: String? = null

    // In TAG_EDIT: the tag whose membership the checkboxes show, i.e. whatever follows
    // "##", folded. Null while the bare-"##" overview is up (nothing chosen yet), so the
    // row binder and the tap handler have one place to ask "which tag is this about?"
    // instead of re-parsing the prompt. Recomputed by applyFilter on every keystroke.
    var tagEditTag: String? = null
        private set

    // How many times [shown] has been replaced, and what that counter read when the
    // finger last went down. AbsListView POSTS its click and long-press, and it only drops
    // a pending one while its own mDataChanged flag is up — once a layout pass has run the
    // flag is clear again and the gesture lands on whatever row has since moved into that
    // slot. Comparing the two makes a gesture act only on the list it was aimed at.
    private var shownGeneration = 0
    // The generation the list had when THIS gesture's finger went down. Read LIVE by the
    // long-press guard (CheckForLongPress fires before ACTION_UP, finger still down), then
    // reset to NO_TOUCH the moment the gesture ends so a later accessibility click is not
    // measured against a dead gesture.
    private var touchDownGeneration = TapGuard.NO_TOUCH

    // The down-generation handed over at ACTION_UP for the click the framework may still
    // POST. A SEPARATE field from touchDownGeneration precisely because that one is cleared
    // at UP: the click is delivered LATER, so it needs the down-generation preserved to
    // compare against the CURRENT shownGeneration at delivery — that live compare is what
    // catches a list replacement landing between ACTION_UP and the posted click. NO_TOUCH
    // means "no touch-originated click pending", so an accessibility/hardware click passes.
    private var clickDownGeneration = TapGuard.NO_TOUCH

    // What the ListView currently shows: app rows in every mode, or tag-name rows in
    // the bare-"#" overview. Reassigned only by applyFilter (from LauncherLogic.rowsFor).
    var shown: List<Row<AppEntry>> = emptyList()
        private set

    private lateinit var clearButton: TextView
    // The command line and its ×, on their own bar (Palette.promptBar).
    private lateinit var promptRow: LinearLayout
    // The prompt's cursor, recoloured in place by applyPalette: the Editor loads a cursor drawable
    // only once, so setting a new one later is ignored.
    private val cursorDrawable = GradientDrawable()
    private lateinit var emptyView: OutlinedTextView
    lateinit var listView: ListView
        private set
    lateinit var prompt: EditText
        private set
    private lateinit var adapter: AppListAdapter

    // The drawer keeps monospace only where columns matter (the edit modes' [x]/[ ]
    // markers, the "~" commands) and for the command line itself.
    private val listTypeface: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)

    init {
        // The back page. It keeps the Sigil layout exactly — list above, command line below.
        // No background: the system wallpaper (window is transparent + windowShowWallpaper,
        // see VersoTheme) shows through. A semi-transparent scrim flickered on the wallpaper
        // surface in Sigil, so instead of covering the home page the drawer HIDES it (see
        // openDrawer) — the two pages never overlap once a turn has finished.
        view = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = false
            visibility = View.GONE
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        }

        // Results grow upward, sitting right above the command line — except the bare,
        // empty-prompt drawer, which reads top-down A to Z (see applyFilter).
        listView = ListView(activity).apply {
            divider = null  // setDivider(null) already zeroes the divider height
            // Arrow keys or Tab can move a hardware keyboard's focus into the list, which has no
            // type-to-filter: send what is typed there back to the command line.
            setOnKeyListener { _, _, event ->
                val c = if (event.action == KeyEvent.ACTION_DOWN) printable(event) else null
                if (c != null) typeIntoPrompt(c)
                c != null
            }
            isVerticalScrollBarEnabled = false
            isStackFromBottom = true
            // Record which list the finger went down on. Returns false without fail: this
            // must observe the gesture, never consume it — the ListView still has to do
            // its own scrolling and click detection.
            @Suppress("ClickableViewAccessibility")
            setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        touchDownGeneration = shownGeneration
                        clickDownGeneration = TapGuard.NO_TOUCH  // new gesture, nothing to deliver yet
                    }
                    MotionEvent.ACTION_UP -> {
                        // Hand this gesture's down-generation to the click field and clear the
                        // live one. The click compares it against shownGeneration when it is
                        // actually delivered, so a replacement AFTER this UP is still caught.
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
                // getOrNull, not [position]: a background load can complete on the main
                // thread between the frame the user tapped and this click message running,
                // shrinking shown — a stale position would then throw (tapAction maps null
                // to None). The per-mode dispatch is the pure LauncherLogic.tapAction (see
                // LauncherActionsTest); the Activity keeps only the side effects.
                val action = LauncherLogic.tapAction(mode, shown.getOrNull(position))
                // Every change of the config asks editAllowed() first (not loaded, a command running);
                // picking a row to move, drilling into a tag, launching are no edits.
                onTap(action)
            }
            // Long-press: set/clear a custom name (and tags) for an app. Tag rows have
            // no long-press action.
            setOnItemLongClickListener { _, _, position, _ ->
                // Deliberately NOT the click's helper: a long-press fires while the
                // finger is still down, so it must compare live rather than read a verdict
                // that only exists after ACTION_UP. Same exposure though — AbsListView
                // gates it on the mDataChanged flag an intervening layout clears — and a
                // stale one opens the rename dialog for whichever app moved in underneath.
                // Not consumed when stale: let the framework do nothing.
                val row = shown.getOrNull(position) as? AppRow
                    ?: return@setOnItemLongClickListener false  // tag row or stale: not consumed
                // Row type FIRST, guard second: a long-press on a tag row does nothing, and
                // consuming the marker there would leave the click that follows it
                // unguarded.
                if (!longPressStillValid()) return@setOnItemLongClickListener false
                onLongPress(row.entry)
                true
            }
        }
        // The list and, in its place while it is empty, a hint that says why (see emptyHint) —
        // ListView.emptyView swaps the two. Outlined like the rows; a polite live region so a
        // screen reader hears the hint when it appears.
        emptyView = OutlinedTextView(activity).apply {
            textSize = 18f
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

        // A clear button for the prompt, sitting at the end of the command line. Shown
        // only while there IS something to erase (see the TextWatcher), it wipes the line
        // back to the plain drawer without touching the keyboard — so the user can retype right
        // away, unlike Back, which also drops the IME.
        clearButton = TextView(activity).apply {
            text = "×"  // × — a clear glyph that sits right in the monospace line
            typeface = Typeface.MONOSPACE
            textSize = 24f
            setTextColor(palette.listDim)
            gravity = Gravity.CENTER
            minWidth = 48.dp()  // a comfortable tap target next to the narrow glyph
            contentDescription = activity.getString(R.string.clear_prompt)
            isFocusable = false  // keep focus (and the IME) on the prompt when tapped
            visibility = View.GONE
            setOnClickListener {
                prompt.setText("")  // TextWatcher -> applyFilter resets to NORMAL
                // Clearing is for retyping — so the keyboard belongs up, also after the recents
                // view, which opened without it.
                showKeyboard()
            }
        }

        // The prompt: a bare monospace command line at the bottom.
        prompt = EditText(activity).apply {
            hint = activity.getString(R.string.hint_search)
            setHintTextColor(palette.listDim)
            setTextColor(palette.listText)
            textCursorDrawable = cursorDrawable.apply { setSize(2.dp(), 1) }
            typeface = Typeface.MONOSPACE
            textSize = 20f
            background = null
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_GO
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    val text = s?.toString().orEmpty()
                    // Track the button off the actual text, so it appears/vanishes whether
                    // the change came from typing or from a setText("") elsewhere.
                    clearButton.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
                    applyFilter(text)
                }
            })
            setOnEditorActionListener { _, actionId, event ->
                // Act once per Enter: a key Enter calls this for its down, its repeats and its
                // up, each with the GO action — only the first down acts (LauncherLogic.enterEdge).
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
                // A "~" command is one-shot, not a view, so it lives on Enter rather
                // than on a live sigil — nothing happens while it is being typed.
                val command = LauncherLogic.resolveCommand(
                    prompt.text?.toString()?.trim().orEmpty()
                )
                // The whole "what does Enter do?" decision is one pure, exhaustively-tested
                // function (see LauncherLogic.enterAction and LauncherActionsTest); the branch
                // ORDER lives there, load-bearing and pinned. The Activity keeps only the side
                // effects. lastOrNull, not firstOrNull: with isStackFromBottom the list fills
                // upward from the command line, so the LAST row sits directly above the prompt —
                // the natural Enter target.
                onEnter(
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
        // The prompt takes the row's width (weight 1); the clear button rides at its end,
        // as tall as the row so its glyph lines up with the command line.
        promptRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(palette.promptBar)
            // Text and × off the bar's edges.
            setPadding(8.dp(), 0, 8.dp(), 0)
            gravity = Gravity.CENTER_VERTICAL
            // 48dp: the minimum touch target — the bare EditText (no background, no padding)
            // and the × were only ~30dp tall.
            minimumHeight = 48.dp()
            addView(prompt, LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(clearButton, LinearLayout.LayoutParams(WRAP, MATCH))
        }
        view.addView(promptRow, LinearLayout.LayoutParams(MATCH, WRAP))

        // Pad the drawer for the system bars AND the IME together. getInsets(systemBars |
        // ime) returns the max per edge, so the bottom padding is the keyboard height
        // when it's up and the nav-bar height otherwise: the weight-1 ListView shrinks
        // from the bottom — staying FULLY on-screen and scrollable (translating it
        // instead pushed the top of a long list off the top edge) — and the prompt
        // rides up just above the keyboard.
        //
        // Deliberately NO WindowInsetsAnimation callback: applying the inset once, when
        // it settles, keeps the ListView from re-laying-out on every animation frame —
        // that per-frame relayout is what made the list blink/redraw before. The prompt
        // moves in a single step rather than tracking the slide; a fair trade for a
        // launcher's command line, and it never clips or flickers.
        //
        // The bars IGNORING their visibility, like the home page: the status bar is shown and
        // hidden with every page turn, and following it made the list jump mid-slide. The IME
        // only while it is up (getInsetsIgnoringVisibility does not take it).
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

    /**
     * The drawer's part of a palette switch: prompt, hint, ×, the bar, cursor and selection,
     * the empty-list hint — and the rows, via a rebind.
     */
    fun applyPalette(p: Palette) {
        palette = p
        prompt.setTextColor(palette.listText)
        prompt.setHintTextColor(palette.listDim)
        clearButton.setTextColor(palette.listDim)
        promptRow.setBackgroundColor(palette.promptBar)
        // Cursor, selection and its handles in the text's colour: the theme's teal accent all but
        // vanishes on the light bar.
        cursorDrawable.setColor(p.listText)
        prompt.invalidate()
        prompt.highlightColor = p.selection
        prompt.textSelectHandle?.let { prompt.setTextSelectHandle(it.mutate().apply { setTint(p.listText) }) }
        prompt.textSelectHandleLeft?.let { prompt.setTextSelectHandleLeft(it.mutate().apply { setTint(p.listText) }) }
        prompt.textSelectHandleRight?.let { prompt.setTextSelectHandleRight(it.mutate().apply { setTint(p.listText) }) }
        emptyView.setTextColor(palette.listDim)
        emptyView.setOutline(outlinePx, palette.listOutline)
        adapter.notifyDataSetChanged()
    }

    /** The rows' markers changed, the row set did not. */
    fun notifyChanged() = adapter.notifyDataSetChanged()

    fun applyFilter(raw: String) {
        val q = raw.trim()
        mode = LauncherLogic.parseMode(q)
        // The keyboard's Enter key says what Enter does here: "Done" in the edit modes (it
        // leaves them), "Go" where it launches or runs. Only switched when it changes —
        // restarting the input connection on every keystroke would make the IME flicker.
        val wantedAction = if (LauncherLogic.isEditMode(mode)) EditorInfo.IME_ACTION_DONE else EditorInfo.IME_ACTION_GO
        if (prompt.imeOptions != wantedAction) {
            prompt.imeOptions = wantedAction
            // Posted: this runs inside the keyboard's own edit (afterTextChanged), and keys it
            // sends to the old connection before it rebinds could otherwise be lost.
            prompt.post { activity.getSystemService(InputMethodManager::class.java).restartInput(prompt) }
        }
        // The bare, empty-prompt drawer is a plain A-to-Z list and reads top-down like any
        // app drawer; every other view (search results, sigil modes) keeps Sigil's
        // bottom-anchoring, so results sit right above the command line where Enter acts.
        val topAnchored = mode == Mode.NORMAL && q.isEmpty()
        val anchorFlipped = listView.isStackFromBottom == topAnchored
        if (anchorFlipped) listView.isStackFromBottom = !topAnchored
        // A pickup belongs to a single reorder session: drop it the moment we're no
        // longer in FAV_REORDER, so nothing stale survives into another mode.
        if (mode != Mode.FAV_REORDER) reorderPick = null
        // Whatever follows "##" IS the tag; blank means the overview is showing and no tag
        // is chosen yet. The overview-vs-chosen-tag decision (and the canonicalisation that
        // makes "##Work"/"##work"/"##  work" one tag) is the pure LauncherLogic.tagEditTagFor,
        // tested in LauncherRowsTest — so nothing downstream has to remember to normalise.
        tagEditTag = LauncherLogic.tagEditTagFor(mode, q)
        // The entire "given the prompt, what rows does the list show?" decision now lives
        // as one pure, exhaustively-tested function in LauncherLogic (see rowsFor and
        // LauncherRowsTest). The Activity keeps only the SIDE EFFECTS around it: the
        // reorderPick reset and tagEditTag above, the shownGeneration bump and the empty-
        // recents toast below. tagEditTag is passed in rather than recomputed so the value
        // the adapter reads and the value rowsFor uses can never drift.
        shown = LauncherLogic.rowsFor(
            mode = mode,
            trimmed = q,
            allApps = state.apps,
            hidden = state.config.hidden,
            favorites = state.config.favorites,
            tags = state.config.tags,
            recentKeys = state.recentKeys,
            tagEditTag = tagEditTag,
        )
        // A pick whose row is gone (the app uninstalled meanwhile) is no pick: the next tap would
        // move an invisible favorite instead of picking the one tapped.
        if (reorderPick != null && shown.none { (it as? AppRow)?.entry?.key == reorderPick }) reorderPick = null
        // Any replacement of shown invalidates a tap already in flight (see the click
        // listener). Bumped here, in the ONE place shown is assigned.
        shownGeneration++
        // An empty list explains itself (it is only shown while the list IS empty) — but not
        // before the apps have loaded, when "no apps" would be false. Set only when it changes:
        // the view is a live region, and TalkBack would repeat it on every keystroke.
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
        // Back to the empty prompt: start the A-to-Z list at A, not wherever the
        // bottom-anchored search left the scroll position.
        if (anchorFlipped && topAnchored && shown.isNotEmpty()) listView.setSelection(0)
        // ...and the reverse: leaving the empty prompt, the ListView would otherwise sync to
        // the old first position (0) and show the TOP of a long result list — hiding the row
        // nearest the prompt, which is exactly what Enter acts on. Pin it to the bottom.
        if (anchorFlipped && !topAnchored && shown.isNotEmpty()) listView.setSelection(shown.lastIndex)
        // Favorites, labels or the app set may have changed (reload, rename, reorder,
        // restore): keep the home page in step. A no-op when nothing it shows changed.
        onFiltered()
    }

    /** Re-run [applyFilter] on what the prompt holds now — after the data under it changed. */
    fun refilter() = applyFilter(prompt.text?.toString().orEmpty())

    /** Append [c] to the command line and put the typing back there. */
    fun typeIntoPrompt(c: String) {
        prompt.requestFocus()
        prompt.append(c)
        prompt.setSelection(prompt.length())
    }

    /**
     * May the posted click still act on [shown]?
     *
     * False only when the list was replaced between the finger going down and this click
     * being DELIVERED — then the row at that position is not the one that was aimed at, and
     * acting on it would toggle, launch, re-tag or rename an app the user never touched.
     *
     * The comparison is made live against the CURRENT [shownGeneration] here, not against a
     * verdict frozen at ACTION_UP: [clickDownGeneration] preserves the down-generation past
     * UP for exactly this reason. That closes the window a UP-time snapshot left open — a
     * replacement landing between ACTION_UP and this posted delivery (a background app-load
     * can, which is also why the branches below re-check via getOrNull). Nothing runs
     * between reading the generation here and reading [shown] in the same callback, so the
     * two stay consistent.
     *
     * Consumes the down-generation (resets it to NO_TOUCH), so it guards exactly the one
     * click that touch leads to and a following accessibility click passes. A NO_TOUCH
     * value passes anyway: accessibility services and hardware keys invoke the click action
     * directly, with no MotionEvent at all, and vetoing those would make the launcher
     * unusable with a screen reader.
     *
     * This reads [clickDownGeneration]; the long-press reads the LIVE [touchDownGeneration]
     * via [longPressStillValid]. The pure arithmetic in [TapGuard.stillValid] is shared,
     * but the FIELD each passes must not be — feeding a long-press the click's field (or
     * vice versa) is the mistake that made the long-press guard a no-op once already.
     */
    private fun clickStillValid(): Boolean {
        val downGeneration = clickDownGeneration
        clickDownGeneration = TapGuard.NO_TOUCH
        return TapGuard.stillValid(downGeneration, shownGeneration)
    }

    /**
     * The same question for a LONG-PRESS, which needs a different answer — and getting that
     * wrong is what made the previous version of this guard a no-op.
     *
     * AbsListView posts CheckForLongPress on ACTION_DOWN and fires it about half a second
     * later WHILE THE FINGER IS STILL DOWN. It therefore arrives strictly BEFORE this
     * gesture's own ACTION_UP — so there is no verdict for it to read, and reading one
     * would only ever return a leftover from some earlier gesture. Instead the comparison
     * is made live: touchDownGeneration still holds THIS gesture's value, because the
     * gesture has not ended yet.
     *
     * NO_TOUCH means no touch is in progress — an accessibility service invoking the
     * long-click action directly — and passes, for the same reason [clickStillValid] lets
     * an unattributed click through.
     */
    private fun longPressStillValid(): Boolean =
        TapGuard.stillValid(touchDownGeneration, shownGeneration)

    /**
     * Set the prompt programmatically and leave the caret at the END, ready to keep typing —
     * [android.widget.EditText.setText] on its own parks it at 0, which would make the next
     * keystroke land in FRONT of a drilled "#tag".
     *
     * Nothing depends on the caret sitting anywhere in particular: the "*" drawer filter reads
     * a star at either end (see [LauncherLogic.starPrefix]), precisely so it survives whatever
     * the IME does with the caret when setText restarts the input connection. setText fires the
     * TextWatcher synchronously, exactly as typing does.
     */
    fun setPrompt(text: String) {
        prompt.setText(text)
        prompt.setSelection(text.length)
    }

    fun showKeyboard() {
        // requestFocus makes the prompt the input target; the window insets controller is
        // the modern IME show that cooperates with the app-owned insets above. The legacy
        // InputMethodManager.showSoftInput wants the view already laid out and the window
        // focused, so it is unreliable straight from onResume — the controller is not.
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
            // Tag rows and app rows are the same monospace TextView, so a recycled view
            // is reused across both freely.
            val tv = (convertView as? TextView) ?: OutlinedTextView(activity).apply {
                setTextColor(palette.listText)
                setOutline(outlinePx, palette.listOutline)
                textSize = 20f
                gravity = Gravity.CENTER_VERTICAL
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
                val v = 10.dp()
                setPadding(0, v, 0, v)
            }
            // Every bind, not only on creation: a recycled row may predate a palette switch.
            tv.setTextColor(palette.listText)
            (tv as? OutlinedTextView)?.setOutline(outlinePx, palette.listOutline)
            when (val row = shown[position]) {
                // Tag overview (bare "#"): plain tag name; a screen reader reads it fine,
                // so clear any stale edit-mode description from the recycled view.
                is TagRow -> {
                    tv.typeface = listTypeface
                    tv.text = row.name
                    tv.contentDescription = null
                    tv.alpha = 1f
                }
                // "~" overview: the command's own spelling is the label, and it reads
                // aloud as-is, so clear any stale edit-mode description off the recycled view.
                is CommandRow -> {
                    tv.typeface = Typeface.MONOSPACE  // commands are typed text: show them so
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
            // The edit-mode marker glyph is chosen by the pure LauncherLogic.rowPrefix
            // (unit-tested in LauncherRowsTest); the read modes return "" so the label
            // stands alone. "[ ] "/"[x] " and "» "/"  " are equal-width, so labels stay
            // column-aligned.
            val marker = LauncherLogic.rowPrefix(
                mode,
                isHidden = key in state.config.hidden,
                isFavorite = key in state.config.favorites,
                isPicked = key == reorderPick,
                isTagged = tagEditTag in state.config.tags[key].orEmpty(),
            )
            // A marker column ("[x] ", "» ") only lines up in a monospace face; the plain
            // read modes carry none and use the drawer's ordinary sans.
            tv.typeface = if (marker.isEmpty()) listTypeface else Typeface.MONOSPACE
            tv.text = marker + entry.label
            // A paused or archived app is dimmed: still there, but a tap brings it back first.
            tv.alpha = HomeStyle.rowAlpha(entry.status)
            // Accessibility: the "[x]"/"[ ]" glyph reads as literal punctuation to a
            // screen reader, so in the edit modes give the row a spoken description of
            // its state and what a tap does. NORMAL rows read their label fine, so the
            // description is cleared (null falls back to the text) — also stops a stale
            // edit-mode description clinging to a recycled convertView.
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
                        reorderPick == null -> R.string.a11y_reorder_pick   // nothing held
                        key == reorderPick  -> R.string.a11y_reorder_picked // this row held
                        else                -> R.string.a11y_reorder_drop   // a drop target
                    },
                    entry.label,
                )
                Mode.TAG_EDIT -> activity.getString(
                    if (tagEditTag in state.config.tags[key].orEmpty()) R.string.a11y_tag_on
                    else R.string.a11y_tag_off,
                    entry.label,
                    tagEditTag.orEmpty(),
                )
                // The read modes read the label as-is — unless the dimming says something a
                // screen reader would otherwise not hear.
                Mode.NORMAL, Mode.RECENTS, Mode.TAG_FILTER, Mode.COMMAND -> unavailableDescription(activity, entry)
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
