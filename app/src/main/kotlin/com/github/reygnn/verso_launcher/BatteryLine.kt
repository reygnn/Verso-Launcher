package com.github.reygnn.verso_launcher

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout

/**
 * The battery on the front page: the percentage, a double-tap target, and beside it the icon of a
 * charge that runs or one the system holds back. Between [start] and [stop] it follows the
 * system's sticky broadcast; what the extras mean is [BatteryInfo]'s.
 *
 * @param onOpen what a double-tap on the percentage does.
 */
internal class BatteryLine(
    private val activity: Activity,
    textColor: Int,
    outlinePx: Float,
    outlineColor: Int,
    onOpen: () -> Unit,
) {
    private val label = OutlinedTextView(activity).apply {
        text = BatteryInfo.UNKNOWN_LABEL
        setTextSize(TypedValue.COMPLEX_UNIT_DIP, HomeStyle.INFO_DP)
        setTextColor(textColor)  // no press dimming on a double-tap target
        setOutline(outlinePx, outlineColor)
        val pad = activity.dp(HomeStyle.INFO_TOUCH_PADDING_DP)
        setPadding(pad, pad, pad, pad)
        onDoubleTap(activity.getString(R.string.a11y_open_battery), onOpen)
    }
    private val chargingIcon = stateIcon(R.drawable.ic_bolt_outlined, textColor, outlineColor)
    private val protectedIcon = stateIcon(R.drawable.ic_shield_outlined, textColor, outlineColor)

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = show(intent)
    }
    private var registered = false

    /** The percentage and its two icons, in reading order, behind what [row] already holds. */
    fun addTo(row: LinearLayout) {
        row.addView(label, LinearLayout.LayoutParams(WRAP, WRAP).apply {
            marginStart = activity.dp(HomeStyle.INFO_GAP_DP)
        })
        val iconSize = activity.dp(HomeStyle.ICON_SIZE_DP)
        row.addView(chargingIcon, LinearLayout.LayoutParams(iconSize, iconSize))
        row.addView(protectedIcon, LinearLayout.LayoutParams(iconSize, iconSize))
    }

    fun applyPalette(textColor: Int, outlinePx: Float, outlineColor: Int) {
        label.setTextColor(textColor)
        label.setOutline(outlinePx, outlineColor)
        chargingIcon.setColors(textColor, outlineColor)
        protectedIcon.setColors(textColor, outlineColor)
    }

    fun start() {
        if (registered) return
        // NOT_EXPORTED still receives this system broadcast. Not caught, like [AppWatch.start].
        val sticky = activity.registerReceiver(
            receiver,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            Context.RECEIVER_NOT_EXPORTED,
        )
        // Flag first, so [stop] unregisters even if showing the first reading throws.
        registered = true
        show(sticky)
    }

    fun stop() {
        if (!registered) return
        registered = false
        activity.unregisterReceiver(receiver)
    }

    private fun show(intent: Intent?) {
        if (intent == null) return
        val percent = BatteryInfo.label(
            BatteryInfo.percent(
                intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1),
                intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1),
            )
        )
        // The broadcast repeats while charging; each setText would re-lay out the page.
        if (label.text.toString() != percent) label.text = percent
        val state = BatteryInfo.chargeState(
            intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1),
            intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0),
        )
        chargingIcon.visibility = if (state == ChargeState.CHARGING) View.VISIBLE else View.GONE
        protectedIcon.visibility = if (state == ChargeState.PROTECTED) View.VISIBLE else View.GONE
        // One stop for TalkBack: the icons are none, their state is read here.
        label.contentDescription = activity.getString(
            when (state) {
                ChargeState.CHARGING -> R.string.a11y_battery_charging
                ChargeState.PROTECTED -> R.string.a11y_battery_protected
                ChargeState.NONE -> R.string.a11y_battery
            },
            percent,
        )
    }

    /** The state is read with the percentage ([show]): no stop of its own for TalkBack. */
    private fun stateIcon(res: Int, textColor: Int, outlineColor: Int) = OutlinedImageView(activity).apply {
        setImageResource(res)
        setColors(textColor, outlineColor)
        val p = activity.dp(HomeStyle.ICON_PADDING_DP)
        setPadding(p, p, p, p)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        visibility = View.GONE
    }

    private companion object {
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}
