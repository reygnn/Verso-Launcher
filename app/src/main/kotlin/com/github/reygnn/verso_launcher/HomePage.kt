package com.github.reygnn.verso_launcher

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.net.Uri
import android.os.BatteryManager
import android.os.Bundle
import android.os.SystemClock
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast

/**
 * The front page, Kolibri Launcher's home layout: clock, date + battery (each a double-tap
 * target), the favorites. The numbers live in [HomeStyle].
 */
internal class HomePage(
    private val activity: Activity,
    private val onLaunch: (AppEntry) -> Unit,
    private val onEdit: (AppEntry) -> Unit,
    private val onOpenDrawer: () -> Unit,
    private val onOpenRecents: () -> Unit,
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

    private val clock: OutlinedTextClock
    private val date: OutlinedTextClock
    private val batteryText: OutlinedTextView
    private val chargingIcon: OutlinedImageView
    private val protectedIcon: OutlinedImageView

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
        batteryText.outlined()
        batteryText.setTextColor(textColor)
        chargingIcon.setColors(textColor, outlineColor)
        protectedIcon.setColors(textColor, outlineColor)
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
            onDoubleTap(R.string.a11y_open_alarms) { openAlarms() }
        }
        val infoPad = HomeStyle.INFO_TOUCH_PADDING_DP.dp()
        date = OutlinedTextClock(activity).apply {
            format12Hour = localPattern("EEEdMMM")
            format24Hour = localPattern("EEEdMMM")
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, HomeStyle.INFO_DP)
            outlined()
            setPadding(infoPad, infoPad, infoPad, infoPad)
            onDoubleTap(R.string.a11y_open_calendar) { openCalendar() }
        }
        batteryText = OutlinedTextView(activity).apply {
            text = BatteryInfo.UNKNOWN_LABEL
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, HomeStyle.INFO_DP)
            outlined()
            setTextColor(textColor)  // no press dimming on a double-tap target
            setPadding(infoPad, infoPad, infoPad, infoPad)
            onDoubleTap(R.string.a11y_open_battery) { openBatteryUsage() }
        }
        chargingIcon = stateIcon(R.drawable.ic_bolt_outlined)
        protectedIcon = stateIcon(R.drawable.ic_shield_outlined)

        val iconSize = HomeStyle.ICON_SIZE_DP.dp()
        val infoRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(date, LinearLayout.LayoutParams(WRAP, WRAP))
            addView(batteryText, LinearLayout.LayoutParams(WRAP, WRAP).apply {
                marginStart = HomeStyle.INFO_GAP_DP.dp()
            })
            addView(chargingIcon, LinearLayout.LayoutParams(iconSize, iconSize))
            addView(protectedIcon, LinearLayout.LayoutParams(iconSize, iconSize))
        }
        val timeContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.START
            addView(clock, LinearLayout.LayoutParams(WRAP, WRAP))
            addView(infoRow, LinearLayout.LayoutParams(WRAP, WRAP).apply {
                topMargin = HomeStyle.INFO_ROW_TOP_MARGIN_DP.dp()
            })
        }

        view = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = false
            addView(timeContainer, LinearLayout.LayoutParams(WRAP, WRAP).apply {
                topMargin = HomeStyle.CLOCK_TOP_MARGIN_DP.dp()
            })
            addView(scroller, LinearLayout.LayoutParams(MATCH, 0, 1f).apply {
                topMargin = HomeStyle.FAVORITES_TOP_MARGIN_DP.dp()
            })
            // Cutout too: Android 16 ignores the portrait lock on large screens. IgnoringVisibility
            // (hard rule 12), or the page would jump with every page turn.
            val pad = HomeStyle.PAGE_PADDING_DP.dp()
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
            accessibilityDelegate = PageActions()
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
            val pad = HomeStyle.INFO_TOUCH_PADDING_DP.dp()
            favorites.addView(OutlinedTextView(activity).apply {
                text = activity.getString(R.string.home_hint)
                setTextSize(TypedValue.COMPLEX_UNIT_DIP, HomeStyle.INFO_DP)
                outlined()
                setPadding(pad, pad, pad, pad)
                setOnClickListener { onOpenDrawer() }
            }, LinearLayout.LayoutParams(MATCH, WRAP))
            return
        }

        val dm = activity.resources.displayMetrics
        val textPx = HomeStyle.favoriteTextSizePx(
            minPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, HomeStyle.INFO_DP, dm),
            maxPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, HomeStyle.CLOCK_DP, dm) *
                HomeStyle.MAX_FAVORITE_RELATIVE_TO_CLOCK,
        )
        val padV = HomeStyle.favoriteVerticalPaddingPx(textPx)
        val padH = HomeStyle.FAVORITE_PADDING_H_DP.dp()
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
                contentDescription = unavailableDescription(activity, entry)
                setOnClickListener { onLaunch(entry) }
                setOnLongClickListener { onEdit(entry); true }
            }, LinearLayout.LayoutParams(WRAP, WRAP))
        }
    }

    fun scrollToTop() = scroller.smoothScrollTo(0, 0)

    // ---- battery ----------------------------------------------------------------

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = showBattery(intent)
    }
    private var batteryRegistered = false

    fun startBattery() {
        if (batteryRegistered) return
        // NOT_EXPORTED still receives this system broadcast.
        // no battery line beats a home page that cannot start
        val sticky = @Suppress("TooGenericExceptionCaught") try {
            activity.registerReceiver(
                batteryReceiver,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED),
                Context.RECEIVER_NOT_EXPORTED,
            )
        } catch (e: RuntimeException) {
            Log.w("Verso", "battery receiver registration failed", e)
            return
        }
        // Flag first, so stopBattery unregisters even if showing the first reading throws.
        batteryRegistered = true
        showBattery(sticky)
    }

    fun stopBattery() {
        if (!batteryRegistered) return
        batteryRegistered = false
        activity.unregisterReceiver(batteryReceiver)
    }

    private fun showBattery(intent: Intent?) {
        if (intent == null) return
        val label = BatteryInfo.label(
            BatteryInfo.percent(
                intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1),
                intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1),
            )
        )
        // The broadcast repeats while charging; each setText would re-lay out the page.
        if (batteryText.text.toString() != label) batteryText.text = label
        val state = BatteryInfo.chargeState(
            intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1),
            intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0),
        )
        chargingIcon.visibility = if (state == ChargeState.CHARGING) View.VISIBLE else View.GONE
        protectedIcon.visibility = if (state == ChargeState.PROTECTED) View.VISIBLE else View.GONE
        // One stop for TalkBack: the icons are none, their state is read here.
        batteryText.contentDescription = activity.getString(
            when (state) {
                ChargeState.CHARGING -> R.string.a11y_battery_charging
                ChargeState.PROTECTED -> R.string.a11y_battery_protected
                ChargeState.NONE -> R.string.a11y_battery
            },
            label,
        )
    }

    // ---- double-tap targets ---------------------------------------------------------

    private fun openAlarms() = startGuarded(Intent(AlarmClock.ACTION_SHOW_ALARMS))

    private fun openCalendar() {
        val time = Uri.withAppendedPath(CalendarContract.CONTENT_URI, "time")
        startGuarded(Intent(Intent.ACTION_VIEW).setData(ContentUris.withAppendedId(time, System.currentTimeMillis())))
    }

    private fun openBatteryUsage() = startGuarded(Intent(Intent.ACTION_POWER_USAGE_SUMMARY))

    private fun startGuarded(intent: Intent) {
        // any app may refuse the intent (none there, denied, anything else): say so, never crash
        @Suppress("TooGenericExceptionCaught") try {
            activity.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: RuntimeException) {
            Log.w("Verso", "start failed: $intent", e)
            noApp()
        }
    }

    private fun noApp() =
        Toast.makeText(activity, activity.getString(R.string.toast_no_app), Toast.LENGTH_SHORT).show()

    // ---- helpers --------------------------------------------------------------------

    /** The state is read with the battery line (showBattery): no stop of its own for TalkBack. */
    private fun stateIcon(res: Int) = OutlinedImageView(activity).apply {
        setImageResource(res)
        setColors(textColor, outlineColor)
        val p = HomeStyle.ICON_PADDING_DP.dp()
        setPadding(p, p, p, p)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        visibility = View.GONE
    }

    private fun OutlinedTextView.outlined() {
        setTextColor(pressableText)
        setOutline(outlinePx, outlineColor)
    }

    private fun OutlinedTextClock.outlined() {
        setTextColor(textColor)
        setOutline(outlinePx, outlineColor)
    }

    /** TalkBack's "double-tap to activate" arrives as ONE click, which the gate would swallow:
     *  an accessibility click runs [onActivate] directly. */
    private fun View.onDoubleTap(label: Int, onActivate: () -> Unit) {
        val gate = DoubleTapGate()
        setOnClickListener { if (gate.tap(SystemClock.uptimeMillis())) onActivate() }
        accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                // Same id as the plain click: this relabels it.
                info.addAction(
                    AccessibilityNodeInfo.AccessibilityAction(
                        AccessibilityNodeInfo.ACTION_CLICK,
                        activity.getString(label),
                    )
                )
            }

            override fun performAccessibilityAction(host: View, action: Int, args: Bundle?): Boolean {
                if (action == AccessibilityNodeInfo.ACTION_CLICK) {
                    onActivate()
                    return true
                }
                return super.performAccessibilityAction(host, action, args)
            }
        }
    }

    private fun Int.dp(): Int = activity.dp(this)

    private fun localPattern(skeleton: String): String =
        android.text.format.DateFormat.getBestDateTimePattern(java.util.Locale.getDefault(), skeleton)

    /** The page swipes as actions in TalkBack's menu: up to all apps, down to the recent ones. */
    private inner class PageActions : View.AccessibilityDelegate() {
        override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(host, info)
            info.addAction(
                AccessibilityNodeInfo.AccessibilityAction(R.id.action_open_drawer, activity.getString(R.string.a11y_open_drawer))
            )
            info.addAction(
                AccessibilityNodeInfo.AccessibilityAction(R.id.action_open_recents, activity.getString(R.string.a11y_open_recents))
            )
        }

        override fun performAccessibilityAction(host: View, action: Int, args: Bundle?): Boolean = when (action) {
            R.id.action_open_drawer -> true.also { onOpenDrawer() }
            R.id.action_open_recents -> true.also { onOpenRecents() }
            else -> super.performAccessibilityAction(host, action, args)
        }
    }

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}

internal fun unavailableDescription(context: android.content.Context, entry: AppEntry): String? =
    when (entry.status) {
        AppStatus.READY -> null
        AppStatus.PAUSED -> context.getString(R.string.a11y_app_paused, entry.label)
        AppStatus.ARCHIVED -> context.getString(R.string.a11y_app_archived, entry.label)
    }
