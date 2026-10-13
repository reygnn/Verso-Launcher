package com.github.reygnn.verso_launcher

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.graphics.Typeface
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
 * The front page: Kolibri Launcher's home layout, with Kolibri's default settings.
 *
 *   [ 24dp page padding inside the system bars ]
 *   48dp gap
 *   12:34                      clock, 60sp sans-serif-light
 *   Di., 13 Okt.   87% ⚡       date + battery (+ charging / protection icon), 18sp
 *   8dp gap
 *   Favorite                   favorites, bold, ~34sp, top-down, scroll if too many
 *   Favorite
 *
 * Every text and icon carries Kolibri's thin tonal outline, so it reads on any wallpaper with
 * no scrim. Clock, date and battery react to a DOUBLE tap (alarms / calendar / battery usage),
 * as in Kolibri — a single stray touch does nothing. The numbers live in [HomeStyle].
 *
 * Pure view work, lifted out of MainActivity so the Activity is left with the wiring: it
 * builds this once, feeds [render] the favorites, starts/stops the battery updates with its
 * own lifecycle, and decides what a tap or long-press on a favorite means ([onLaunch],
 * [onEdit]) and how the drawer opens ([onOpenDrawer]). The page never takes focus, so it never
 * summons the keyboard.
 */
internal class HomePage(
    private val activity: Activity,
    private val onLaunch: (AppEntry) -> Unit,
    private val onEdit: (AppEntry) -> Unit,
    private val onOpenDrawer: () -> Unit,
) {
    // A display-size (density) change recreates the activity and this page with it (the
    // manifest no longer handles "density"), so every value derived from this is fresh.
    private val density get() = activity.resources.displayMetrics.density
    private val outlinePx get() = HomeStyle.OUTLINE_DP * density
    // White on a dark wallpaper, black on a light one (HomeStyle.palette, applyTextColor).
    private var textColor = HomeStyle.TEXT_COLOR
    private var outlineColor = HomeStyle.tonalOutlineColor(textColor)

    // Kolibri's subtle press feedback: the text dims while pressed, no ripple.
    private var pressableText = pressable(textColor)

    private fun pressable(color: Int) = ColorStateList(
        arrayOf(intArrayOf(android.R.attr.state_pressed), intArrayOf()),
        intArrayOf(HomeStyle.withAlpha(color, HomeStyle.PRESSED_ALPHA), color),
    )

    private val favorites = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }

    /**
     * Scrolls the favorites only when there are more than fit. While it can still scroll the
     * swiped way, a swipe scrolls it instead of turning the page (see SwipeGesture.claims), so
     * the Activity's swipe frame asks this view.
     */
    val scroller: ScrollView = ScrollView(activity).apply {
        isVerticalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        clipToPadding = false  // the outline reaches 1dp past the glyphs: don't clip it
        addView(favorites, ViewGroup.LayoutParams(MATCH, WRAP))
    }

    private val clock: OutlinedTextClock
    private val date: OutlinedTextClock
    private val batteryText: OutlinedTextView
    private val chargingIcon: OutlinedImageView
    private val protectedIcon: OutlinedImageView

    /** The page itself, for the Activity's root frame. */
    val view: LinearLayout

    // The favorites last rendered (null if never drawn), so the per-keystroke re-render from
    // the drawer skips rebuilding rows that did not change — whole entries, so a change of
    // label OR status (a work profile paused/resumed, an archived app restored) rebuilds them.
    private var rendered: List<AppEntry>? = null
    // The last render call, so a colour switch can redraw the favorites in the new colours.
    private var lastRender: Pair<List<AppEntry>, Boolean>? = null

    /**
     * Switch the page to [color] (see HomeStyle.palette): clock, date, battery, the state icons
     * and the favorites — each with the matching tonal outline.
     */
    fun applyTextColor(color: Int) {
        if (color == textColor) return
        textColor = color
        outlineColor = HomeStyle.tonalOutlineColor(color)
        pressableText = pressable(color)
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
            // TextClock follows the system 12/24-hour setting by itself; the patterns are the
            // locale's own ("h:mm a" in en-US, "a h:mm" in ko, "H:mm" in de, …).
            format12Hour = localPattern("hmm")
            format24Hour = localPattern("Hmm")
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, HomeStyle.CLOCK_DP)
            outlined()
            onDoubleTap(R.string.a11y_open_alarms) { openAlarms() }
        }
        val infoPad = HomeStyle.INFO_TOUCH_PADDING_DP.dp()
        // A short date in the locale's own order: "Tue, Oct 13" (en-US), "Di., 13. Okt." (de).
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
            setTextColor(textColor)  // like clock and date: no press dimming on a double-tap target
            setPadding(infoPad, infoPad, infoPad, infoPad)
            onDoubleTap(R.string.a11y_open_battery) { openBatteryUsage() }
        }
        chargingIcon = stateIcon(R.drawable.ic_bolt_outlined, R.string.a11y_charging)
        protectedIcon = stateIcon(R.drawable.ic_shield_outlined, R.string.a11y_battery_protected)

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
            // System bars and any camera cutout (portrait is locked on phones, but Android 16
            // ignores that lock on large screens, so landscape can still occur) plus Kolibri's 24dp
            // page padding. The keyboard never shows over this page, so no IME inset.
            // IgnoringVisibility: the status bar is hidden on this page (MainActivity.showStatusBar)
            // and shown in the drawer — padding for the bar either way keeps the clock and the
            // favorites from jumping up and down with every page turn.
            val pad = HomeStyle.PAGE_PADDING_DP.dp()
            setOnApplyWindowInsetsListener { v, insets ->
                val i = insets.getInsetsIgnoringVisibility(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
                )
                v.setPadding(i.left + pad, i.top + pad, i.right + pad, i.bottom + pad)
                insets
            }
            // The swipes are invisible to a screen reader, so "open all apps" is also a
            // custom accessibility action on the page itself (TalkBack's actions menu).
            contentDescription = activity.getString(R.string.a11y_home)
            accessibilityDelegate = OpenDrawerAction()
        }
    }

    /**
     * Show [favs] (launchable favorites, in their stored order) top to bottom, styled like
     * Kolibri's home favorites: bold, sized between the date and three quarters of the clock,
     * padded proportionally, outlined. Each row is only as wide as its text, so the space
     * beside a short name stays free for the swipe.
     *
     * [loaded] false means the app list has not been enumerated yet: then nothing is drawn,
     * rather than flashing the "no favorites" hint on every cold start.
     *
     * Each row's listeners close over ITS entry rather than a position, so a reload landing
     * between finger-down and the click can never redirect a tap to another app.
     */
    fun render(favs: List<AppEntry>, loaded: Boolean) {
        lastRender = favs to loaded
        if (!loaded) return
        if (favs == rendered) return
        rendered = favs
        favorites.removeAllViews()

        if (favs.isEmpty()) {
            // A fresh install: say how to get anywhere, instead of an empty page.
            val pad = HomeStyle.INFO_TOUCH_PADDING_DP.dp()
            favorites.addView(OutlinedTextView(activity).apply {
                text = activity.getString(R.string.home_hint)
                setTextSize(TypedValue.COMPLEX_UNIT_DIP, HomeStyle.INFO_DP)
                outlined()
                setPadding(pad, pad, pad, pad)
                // Tapping the hint does what it says — a way in for anyone who doesn't swipe.
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
        val face = if (HomeStyle.FAVORITES_BOLD) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
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
                // A paused or archived favorite is dimmed like its drawer row; TalkBack hears why.
                alpha = HomeStyle.rowAlpha(entry.status)
                contentDescription = unavailableDescription(activity, entry)
                setOnClickListener { onLaunch(entry) }
                setOnLongClickListener { onEdit(entry); true }
            }, LinearLayout.LayoutParams(WRAP, WRAP))
        }
    }

    /** HOME while already home: back to the first favorite. */
    fun scrollToTop() = scroller.smoothScrollTo(0, 0)

    // ---- battery ----------------------------------------------------------------

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = showBattery(intent)
    }
    private var batteryRegistered = false

    /** Live battery updates while the launcher is visible — call from onStart. */
    fun startBattery() {
        if (batteryRegistered) return
        // ACTION_BATTERY_CHANGED is sticky: registering hands back the current state at once.
        // NOT_EXPORTED still receives this system broadcast.
        val sticky = try {
            activity.registerReceiver(
                batteryReceiver,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED),
                Context.RECEIVER_NOT_EXPORTED,
            )
        } catch (e: RuntimeException) {
            Log.w("Verso", "battery receiver registration failed", e)
            return
        }
        // Flag first: whatever happens while showing the first reading, stopBattery must
        // still unregister what was registered.
        batteryRegistered = true
        showBattery(sticky)
    }

    /** Call from onStop. */
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
        // Only on a change: the broadcast repeats every few seconds while charging, and every
        // setText on this WRAP_CONTENT view would re-lay out the whole page.
        if (batteryText.text.toString() != label) {
            batteryText.text = label
            // "87%" alone reads as "87 percent" — say what it is.
            batteryText.contentDescription = activity.getString(R.string.a11y_battery, label)
        }
        val state = BatteryInfo.chargeState(
            intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1),
            intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0),
        )
        chargingIcon.visibility = if (state == ChargeState.CHARGING) View.VISIBLE else View.GONE
        protectedIcon.visibility = if (state == ChargeState.PROTECTED) View.VISIBLE else View.GONE
    }

    // ---- double-tap targets ---------------------------------------------------------

    /** Clock: the system alarms screen (whatever clock app handles it). */
    private fun openAlarms() = startGuarded(Intent(AlarmClock.ACTION_SHOW_ALARMS))

    /** Date: the calendar, opened at "now" (Kolibri's intent). */
    private fun openCalendar() {
        val uri = CalendarContract.CONTENT_URI.buildUpon().appendPath("time")
        ContentUris.appendId(uri, System.currentTimeMillis())
        startGuarded(Intent(Intent.ACTION_VIEW).setData(uri.build()))
    }

    /** Battery: the system battery-usage screen. */
    private fun openBatteryUsage() = startGuarded(Intent(Intent.ACTION_POWER_USAGE_SUMMARY))

    /** A device without a handler gets a short toast instead of a crashed HOME. */
    private fun startGuarded(intent: Intent) {
        try {
            activity.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            noApp()
        } catch (e: SecurityException) {
            noApp()
        } catch (e: RuntimeException) {
            Log.w("Verso", "start failed: $intent", e)
            noApp()
        }
    }

    private fun noApp() =
        Toast.makeText(activity, activity.getString(R.string.toast_no_app), Toast.LENGTH_SHORT).show()

    // ---- helpers --------------------------------------------------------------------

    private fun stateIcon(res: Int, description: Int) = OutlinedImageView(activity).apply {
        setImageResource(res)
        setColors(textColor, outlineColor)
        val p = HomeStyle.ICON_PADDING_DP.dp()
        setPadding(p, p, p, p)
        contentDescription = activity.getString(description)
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

    /**
     * Fire [onActivate] on a double tap only (Kolibri's clock / date / battery behaviour).
     *
     * A screen reader is the exception: TalkBack's own "double-tap to activate" arrives as ONE
     * click, which the gate would always swallow — and a second activation within 300 ms is
     * practically impossible, so the action was unreachable. An accessibility click therefore
     * runs [onActivate] directly, and TalkBack announces what it does ([label], a string res).
     */
    private fun View.onDoubleTap(label: Int, onActivate: () -> Unit) {
        val gate = DoubleTapGate()
        setOnClickListener { if (gate.tap(SystemClock.uptimeMillis())) onActivate() }
        accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                // Same id as the plain click: this relabels it ("double-tap to open alarms").
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

    /** [skeleton] laid out the way the current locale writes it ("Tue, Oct 13" in en-US,
     *  "Di., 13. Okt." in de) instead of one fixed order for every language. */
    private fun localPattern(skeleton: String): String =
        android.text.format.DateFormat.getBestDateTimePattern(java.util.Locale.getDefault(), skeleton)

    /** "Open all apps" as a TalkBack custom action on the page. */
    private inner class OpenDrawerAction : View.AccessibilityDelegate() {
        override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(host, info)
            info.addAction(
                AccessibilityNodeInfo.AccessibilityAction(
                    R.id.action_open_drawer,
                    activity.getString(R.string.a11y_open_drawer),
                )
            )
        }

        override fun performAccessibilityAction(host: View, action: Int, args: Bundle?): Boolean {
            if (action == R.id.action_open_drawer) {
                onOpenDrawer()
                return true
            }
            return super.performAccessibilityAction(host, action, args)
        }
    }

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}

/**
 * What a screen reader says for a dimmed app (see [AppStatus]) — on the home page and in the
 * drawer alike — or null for a ready one, whose label reads fine as it is.
 */
internal fun unavailableDescription(context: android.content.Context, entry: AppEntry): String? =
    when (entry.status) {
        AppStatus.READY -> null
        AppStatus.PAUSED -> context.getString(R.string.a11y_app_paused, entry.label)
        AppStatus.ARCHIVED -> context.getString(R.string.a11y_app_archived, entry.label)
    }
