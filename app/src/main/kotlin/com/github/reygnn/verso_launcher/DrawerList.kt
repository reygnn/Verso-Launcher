package com.github.reygnn.verso_launcher

import android.app.Activity
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.ListView
import android.widget.TextView

/**
 * The drawer's list above the command line: the rows of one [DrawerView], the hint of an empty
 * list, and the stale-tap guard ([TapGuard]). It draws what [show] hands it and reports a tap by
 * its position; what the tap does is the view's ([DrawerView.tap]).
 *
 * @param onTap a tap that is still valid, by its position in the rows shown.
 * @param onLongPress a long press on an app's row.
 * @param onType a character typed while a hardware keyboard's focus sits in the list.
 */
internal class DrawerList(
    private val activity: Activity,
    private var palette: Palette,
    private val onTap: (position: Int) -> Unit,
    private val onLongPress: (AppEntry) -> Unit,
    private val onType: (String) -> Unit,
) {
    private val outlinePx get() = HomeStyle.OUTLINE_DP * activity.resources.displayMetrics.density

    private var shown: List<Row<AppEntry>> = emptyList()

    // Stale-tap guard (see TapGuard): bumped on every replacement of [shown].
    private var shownGeneration = 0
    // Live while the finger is down: the long-press fires before ACTION_UP.
    private var touchDownGeneration = TapGuard.NO_TOUCH

    // Kept past ACTION_UP for the click posted after it. Must stay separate from
    // touchDownGeneration — sharing one field silently disables one of the two guards.
    private var clickDownGeneration = TapGuard.NO_TOUCH

    private val listTypeface: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)

    private val adapter = AppListAdapter()

    // Labels the click of a row for screen readers ("double-tap to turn paused apps back on"),
    // keeping the list's own item click: a delegate on the row would replace the one the list
    // sets there.
    private val listView: ListView = object : ListView(activity) {
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
            if (c != null) onType(c)
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
        // A position past the rows (a load shrank them before this posted click ran) is the view's
        // to refuse: DrawerView.tap.
        setOnItemClickListener { _, _, position, _ -> if (clickStillValid()) onTap(position) }
        setOnItemLongClickListener { _, _, position, _ ->
            val row = shown.getOrNull(position) as? AppRow
                ?: return@setOnItemLongClickListener false
            // Row type before the guard: a tag row must not consume the marker.
            if (!longPressStillValid()) return@setOnItemLongClickListener false
            // Like a scroll, a long press makes no click: its touch leaves no mark (App info
            // comes back to a list that may have changed, and the next click without a touch
            // must pass).
            touchDownGeneration = TapGuard.NO_TOUCH
            onLongPress(row.entry)
            true
        }
    }

    private val emptyView = OutlinedTextView(activity).apply {
        textSize = HomeStyle.DRAWER_HINT_SP
        // Italic like the prompt's hint: a hint, not an app row.
        setTypeface(listTypeface, Typeface.ITALIC)
        gravity = Gravity.BOTTOM or Gravity.START
        setPadding(0, 0, 0, activity.dp(10))
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }

    /** The list, and over it the hint of an empty one. */
    val view: FrameLayout = FrameLayout(activity).apply {
        addView(listView, FrameLayout.LayoutParams(MATCH, MATCH))
        addView(emptyView, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    init {
        listView.emptyView = emptyView
        listView.adapter = adapter
    }

    /** The list's visual top is showing: a drag down there is the page's, not the list's. */
    val atTop: Boolean get() = !listView.canScrollVertically(-1)

    /** The list's visual bottom is showing (the recents close upward from there). */
    val atBottom: Boolean get() = !listView.canScrollVertically(1)

    /** The A-to-Z list rises from its top, not where it was last left. */
    fun scrollToTop() {
        if (shown.isNotEmpty()) listView.setSelection(0)
    }

    /**
     * Draws [drawer]. Every call replaces the rows, so a tap whose touch began before it is stale
     * ([TapGuard]) even when the same rows come back.
     */
    fun show(drawer: DrawerView<AppEntry>) {
        // The recents open with focus on their first row: the list's name says where that is.
        val listName = activity.getString(if (drawer.mode == Mode.RECENTS) R.string.a11y_pane_recents else R.string.a11y_pane_drawer)
        if (listView.contentDescription != listName) listView.contentDescription = listName
        val anchorFlipped = listView.isStackFromBottom == drawer.topAnchored
        if (anchorFlipped) listView.isStackFromBottom = !drawer.topAnchored
        // The one place shown is assigned.
        shown = drawer.rows
        shownGeneration++
        // Only on change: a live region would make TalkBack repeat it on every keystroke.
        val hint = drawer.emptyHint?.let { activity.getString(it.text()) }.orEmpty()
        if (emptyView.text.toString() != hint) emptyView.text = hint
        adapter.notifyDataSetChanged()
        // On an anchor flip the ListView keeps the old position: pin it to the right end.
        if (anchorFlipped && shown.isNotEmpty()) listView.setSelection(if (drawer.topAnchored) 0 else shown.lastIndex)
    }

    fun applyPalette(p: Palette) {
        palette = p
        emptyView.setTextColor(p.text)
        emptyView.setOutline(outlinePx, p.outline)
        adapter.notifyDataSetChanged()
    }

    /** Consumes the marker, so only the click this touch leads to is guarded. */
    private fun clickStillValid(): Boolean {
        val downGeneration = clickDownGeneration
        clickDownGeneration = TapGuard.NO_TOUCH
        return TapGuard.stillValid(downGeneration, shownGeneration)
    }

    private fun longPressStillValid(): Boolean =
        TapGuard.stillValid(touchDownGeneration, shownGeneration)

    private inner class AppListAdapter : BaseAdapter() {
        override fun getCount(): Int = shown.size
        override fun getItem(position: Int): Any = shown[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val tv = (convertView as? TextView) ?: OutlinedTextView(activity).apply {
                textSize = HomeStyle.DRAWER_ROW_SP
                gravity = Gravity.CENTER_VERTICAL
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
                val v = activity.dp(10)
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
                is ReportRow -> bindPlainRow(tv, listTypeface, findingText(row.finding), wrap = true)
                is AppRow -> bindAppRow(tv, row.entry)
            }
            return tv
        }

        /**
         * A row without an app; clears what a recycled app row set (its state, dimming). [wrap]: a
         * finding of "~verify" is read whole, every other row is one line.
         */
        private fun bindPlainRow(tv: TextView, face: Typeface, text: String, wrap: Boolean = false) {
            if (tv.isSingleLine == wrap) {
                tv.isSingleLine = !wrap
                tv.ellipsize = if (wrap) null else TextUtils.TruncateAt.END
            }
            tv.typeface = face
            tv.text = text
            tv.contentDescription = null
            tv.stateDescription = null
            tv.alpha = 1f
        }

        private fun bindAppRow(tv: TextView, entry: AppEntry) {
            if (!tv.isSingleLine) {
                tv.isSingleLine = true
                tv.ellipsize = TextUtils.TruncateAt.END
            }
            tv.typeface = listTypeface
            tv.text = entry.label
            tv.alpha = HomeStyle.rowAlpha(entry.status)
            // Set on every bind (recycling): a plain row may have been a command row.
            tv.contentDescription = null
            tv.stateDescription = rowA11y(entry).first
        }
    }

    /** A finding of "~verify" in the user's words; the folder is this build's own (".debug"). */
    private fun findingText(finding: ConfigFinding): String = when (finding) {
        is ConfigFinding.NotRead -> when (finding.status) {
            ConfigStatus.TOO_LARGE -> activity.getString(R.string.report_too_large)
            ConfigStatus.UNREADABLE -> activity.getString(R.string.report_unreadable)
            ConfigStatus.MISSING, ConfigStatus.READ -> activity.getString(R.string.report_missing, activity.packageName)
        }
        is ConfigFinding.BadLine -> activity.getString(R.string.report_bad_line, finding.line.number, ConfigReport.shownText(finding.line))
        is ConfigFinding.NoApp -> activity.getString(R.string.report_no_app, finding.line.number, ConfigReport.shownText(finding.line))
        ConfigFinding.ListIncomplete -> activity.getString(R.string.report_list_incomplete)
        ConfigFinding.AllGood -> activity.getString(R.string.report_all_good)
    }

    /**
     * A row as a screen reader takes it: its state ("paused") and what a double-tap does ("turn work
     * apps back on"), both null where there is nothing to say.
     */
    private fun rowA11y(entry: AppEntry): Pair<String?, String?> =
        statusState(activity, entry.status) to statusAction(activity, entry.status)

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    }
}

/** The empty list's words for this hint. */
private fun EmptyHint.text(): Int = when (this) {
    EmptyHint.NO_MATCH -> R.string.empty_no_match
    EmptyHint.NO_APPS -> R.string.empty_no_apps
    EmptyHint.NO_TAGS -> R.string.empty_no_tags
    EmptyHint.NO_TAG_MATCH -> R.string.empty_no_tag_match
    EmptyHint.NO_RECENTS -> R.string.empty_no_recents
    EmptyHint.NO_COMMAND -> R.string.empty_no_command
}
