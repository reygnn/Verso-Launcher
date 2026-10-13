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
    // Read live, not cached: the manifest handles density changes (display size) itself, so
    // the Activity — and this page — survive them, and rows rendered afterwards must use the
    // new density like the text size beside them does.
    private val density get() = activity.resources.displayMetrics.density
    private val outlinePx get() = HomeStyle.OUTLINE_DP * density
    private val textColor = HomeStyle.TEXT_COLOR
    private val outlineColor = HomeStyle.tonalOutlineColor(textColor)

    // Kolibri's subtle press feedback: the text dims while pressed, no ripple.
    private val pressableText = ColorStateList(
        arrayOf(intArrayOf(android.R.attr.state_pressed), intArrayOf()),
        intArrayOf(HomeStyle.withAlpha(textColor, HomeStyle.PRESSED_ALPHA), textColor),
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

    private val batteryText: OutlinedTextView
    private val chargingIcon: OutlinedImageView
    private val protectedIcon: OutlinedImageView

    /** The page itself, for the Activity's root frame. */
    val view: LinearLayout

    // What render() last drew: (key, label) per favorite, or null if never drawn. Lets the
    // per-keystroke re-render from the drawer skip rebuilding rows that did not change.
    private var rendered: List<Pair<String, String>>? = null

    init {
        val clock = OutlinedTextClock(activity).apply {
            // TextClock follows the system 12/24-hour setting by itself.
            format12Hour = "h:mm a"
            format24Hour = "HH:mm"
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            textSize = HomeStyle.CLOCK_SP
            outlined()
            onDoubleTap { openAlarms() }
        }
        val infoPad = HomeStyle.INFO_TOUCH_PADDING_DP.dp()
        // Kolibri's short date: "Tue, 13 Oct" / "Di., 13 Okt." (localized day/month names).
        val date = OutlinedTextClock(activity).apply {
            format12Hour = "E, d MMM"
            format24Hour = "E, d MMM"
            textSize = HomeStyle.INFO_SP
            outlined()
            setPadding(infoPad, infoPad, infoPad, infoPad)
            onDoubleTap { openCalendar() }
        }
        batteryText = OutlinedTextView(activity).apply {
            text = BatteryInfo.UNKNOWN_LABEL
            textSize = HomeStyle.INFO_SP
            outlined()
            setTextColor(textColor)  // like clock and date: no press dimming on a double-tap target
            setPadding(infoPad, infoPad, infoPad, infoPad)
            onDoubleTap { openBatteryUsage() }
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
            val pad = HomeStyle.PAGE_PADDING_DP.dp()
            setOnApplyWindowInsetsListener { v, insets ->
                val i = insets.getInsets(
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
        if (!loaded) return
        val signature = favs.map { it.key to it.label }
        if (signature == rendered) return
        rendered = signature
        favorites.removeAllViews()

        if (favs.isEmpty()) {
            // A fresh install: say how to get anywhere, instead of an empty page.
            val pad = HomeStyle.INFO_TOUCH_PADDING_DP.dp()
            favorites.addView(OutlinedTextView(activity).apply {
                text = activity.getString(R.string.home_hint)
                textSize = HomeStyle.INFO_SP
                outlined()
                setPadding(pad, pad, pad, pad)
                // Tapping the hint does what it says — a way in for anyone who doesn't swipe.
                setOnClickListener { onOpenDrawer() }
            }, LinearLayout.LayoutParams(MATCH, WRAP))
            return
        }

        val dm = activity.resources.displayMetrics
        val textPx = HomeStyle.favoriteTextSizePx(
            minPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, HomeStyle.INFO_SP, dm),
            maxPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, HomeStyle.CLOCK_SP, dm) *
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
        batteryText.text = BatteryInfo.label(
            BatteryInfo.percent(
                intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1),
                intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1),
            )
        )
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

    /** Fire [action] on a double tap only (Kolibri's clock / date / battery behaviour). */
    private fun View.onDoubleTap(action: () -> Unit) {
        val gate = DoubleTapGate()
        setOnClickListener { if (gate.tap(SystemClock.uptimeMillis())) action() }
    }

    private fun Int.dp(): Int = (this * density).toInt()

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
