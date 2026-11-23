package com.github.reygnn.verso_launcher

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.LinearLayout
import android.widget.ScrollView

/**
 * The front page, Kolibri Launcher's home layout: clock, date + battery (each a double-tap
 * target), the favorites. The numbers live in [HomeStyle]; the battery is [BatteryLine]'s, what a
 * double-tap opens [HomeTargets]'.
 */
internal class HomePage(
    private val activity: Activity,
    private val onLaunch: (AppEntry) -> Unit,
    /** A long press: the system's App info, where the app's package is. */
    private val onAppInfo: (AppEntry) -> Unit,
    private val onOpenDrawer: () -> Unit,
    onOpenRecents: () -> Unit,
) {
    private val density get() = activity.resources.displayMetrics.density
    private val outlinePx get() = HomeStyle.OUTLINE_DP * density
    private var textColor = HomeStyle.TEXT_COLOR
    private var outlineColor = HomeStyle.tonalOutlineColor(textColor)

    private var pressableText = pressable(textColor)

    private fun pressable(color: Int) = ColorStateList(
        arrayOf(intArrayOf(android.R.attr.state_pressed), intArrayOf()),
        intArrayOf(HomeStyle.withAlpha(color, HomeStyle.PRESSED_ALPHA), color),
    )

    private val favorites = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }

    /** The swipe frame asks it: while it can still scroll that way, a swipe scrolls it. */
    val scroller: ScrollView = ScrollView(activity).apply {
        isVerticalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        clipToPadding = false  // the outline reaches past the glyphs
        addView(favorites, ViewGroup.LayoutParams(MATCH, WRAP))
    }

    private val targets = HomeTargets(activity)
    private val clock: OutlinedTextClock
    private val date: OutlinedTextClock
    private val battery = BatteryLine(activity, textColor, outlinePx, outlineColor, onOpen = targets::openBatteryUsage)

    val view: LinearLayout

    // Skips the per-keystroke re-render from the drawer; whole entries, so a status change rebuilds.
    private var rendered: List<AppEntry>? = null
    // What is on screen, for a colour change to redraw: only a render that drew. A not-loaded call
    // draws nothing and must not replace it — the cold start's cached favorites would keep the old colour.
    private var lastRender: Pair<List<AppEntry>, Boolean>? = null

    fun applyPalette(p: Palette) {
        if (p.text == textColor) return
        textColor = p.text
        outlineColor = p.outline
        pressableText = pressable(textColor)
        clock.outlined()
        date.outlined()
        battery.applyPalette(textColor, outlinePx, outlineColor)
        rendered = null
        lastRender?.let { (favs, loaded) -> render(favs, loaded) }
    }

    init {
        clock = OutlinedTextClock(activity).apply {
            format12Hour = localPattern("hmm")
            format24Hour = localPattern("Hmm")
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, HomeStyle.CLOCK_DP)
            outlined()
            onDoubleTap(activity.getString(R.string.a11y_open_alarms), targets::openAlarms)
        }
        val infoPad = activity.dp(HomeStyle.INFO_TOUCH_PADDING_DP)
        date = OutlinedTextClock(activity).apply {
            format12Hour = localPattern("EEEdMMM")
            format24Hour = localPattern("EEEdMMM")
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, HomeStyle.INFO_DP)
            outlined()
            setPadding(infoPad, infoPad, infoPad, infoPad)
            onDoubleTap(activity.getString(R.string.a11y_open_calendar), targets::openCalendar)
        }

        val infoRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(date, LinearLayout.LayoutParams(WRAP, WRAP))
            battery.addTo(this)
        }
        val timeContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.START
            addView(clock, LinearLayout.LayoutParams(WRAP, WRAP))
            addView(infoRow, LinearLayout.LayoutParams(WRAP, WRAP).apply {
                topMargin = activity.dp(HomeStyle.INFO_ROW_TOP_MARGIN_DP)
            })
        }

        view = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = false
            addView(timeContainer, LinearLayout.LayoutParams(WRAP, WRAP).apply {
                topMargin = activity.dp(HomeStyle.CLOCK_TOP_MARGIN_DP)
            })
            addView(scroller, LinearLayout.LayoutParams(MATCH, 0, 1f).apply {
                topMargin = activity.dp(HomeStyle.FAVORITES_TOP_MARGIN_DP)
            })
            // Cutout too: Android 16 ignores the portrait lock on large screens. IgnoringVisibility
            // (hard rule 12), or the page would jump with every page turn.
            val pad = activity.dp(HomeStyle.PAGE_PADDING_DP)
            setOnApplyWindowInsetsListener { v, insets ->
                val i = insets.getInsetsIgnoringVisibility(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
                )
                v.setPadding(i.left + pad, i.top + pad, i.right + pad, i.bottom + pad)
                insets
            }
            // The swipes are invisible to a screen reader: the page itself is a stop (a touch on an
            // empty spot lands on it) and carries them as actions. Seen on the A17: without it TalkBack
            // found nothing there to focus, and the actions could not be reached.
            contentDescription = activity.getString(R.string.a11y_home)
            isScreenReaderFocusable = true
            accessibilityDelegate = PageActions(activity, onOpenDrawer, onOpenRecents)
        }
    }

    /**
     * [loaded] false draws nothing, rather than flashing the "no favorites" hint on a cold start.
     * Listeners close over the entry, not a position, so a reload cannot redirect a tap.
     */
    fun render(favs: List<AppEntry>, loaded: Boolean) {
        if (!loaded) return
        lastRender = favs to loaded
        if (favs == rendered) return
        rendered = favs
        favorites.removeAllViews()

        if (favs.isEmpty()) {
            val pad = activity.dp(HomeStyle.INFO_TOUCH_PADDING_DP)
            favorites.addView(OutlinedTextView(activity).apply {
                text = activity.getString(R.string.home_hint)
                setTextSize(TypedValue.COMPLEX_UNIT_DIP, HomeStyle.INFO_DP)
                outlined()
                setPadding(pad, pad, pad, pad)
                setOnClickListener { onOpenDrawer() }
            }, LinearLayout.LayoutParams(MATCH, WRAP))
        }

        // With no favorites the loop below adds nothing: the hint is all there is.
        val dm = activity.resources.displayMetrics
        val textPx = HomeStyle.favoriteTextSizePx(
            minPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, HomeStyle.INFO_DP, dm),
            maxPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, HomeStyle.CLOCK_DP, dm) *
                HomeStyle.MAX_FAVORITE_RELATIVE_TO_CLOCK,
        )
        val padV = HomeStyle.favoriteVerticalPaddingPx(textPx)
        val padH = activity.dp(HomeStyle.FAVORITE_PADDING_H_DP)
        // Bold, as Kolibri's favorites are by default.
        val face = Typeface.DEFAULT_BOLD
        for (entry in favs) {
            favorites.addView(OutlinedTextView(activity).apply {
                text = entry.label
                setTextSize(TypedValue.COMPLEX_UNIT_PX, textPx)
                typeface = face
                includeFontPadding = false
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
                setPadding(padH, padV, padH, padV)
                outlined()
                alpha = HomeStyle.rowAlpha(entry.status)
                stateDescription = statusState(activity, entry.status)
                statusAction(activity, entry.status)?.let { accessibilityDelegate = labeledClick(it) }
                setOnClickListener { onLaunch(entry) }
                setOnLongClickListener { onAppInfo(entry); true }
            }, LinearLayout.LayoutParams(WRAP, WRAP))
        }
    }

    fun scrollToTop() = scroller.smoothScrollTo(0, 0)

    /** From here to [stopBattery] the battery line follows the system ([BatteryLine.start]). */
    fun startBattery() = battery.start()

    fun stopBattery() = battery.stop()

    private fun OutlinedTextView.outlined() {
        setTextColor(pressableText)
        setOutline(outlinePx, outlineColor)
    }

    private fun OutlinedTextClock.outlined() {
        setTextColor(textColor)
        setOutline(outlinePx, outlineColor)
    }

    private fun localPattern(skeleton: String): String =
        android.text.format.DateFormat.getBestDateTimePattern(java.util.Locale.getDefault(), skeleton)

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}
