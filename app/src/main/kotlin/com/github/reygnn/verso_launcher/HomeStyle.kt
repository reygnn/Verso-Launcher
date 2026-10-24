package com.github.reygnn.verso_launcher

import android.content.Context

import java.text.NumberFormat
import java.util.Locale

import kotlin.math.pow

/**
 * The front page's look: Kolibri Launcher's home screen with its default settings baked in.
 * Sources: LayoutCalculator + AppConstants (sizes), dimens.xml (spacing),
 * ObserveUiColorsUseCase.calculateTonalShadowColor (outline), ClockDelegate (battery).
 */
internal object HomeStyle {

    // ---- Kolibri dimens (dp / sp) ---------------------------------------------
    const val PAGE_PADDING_DP = 24
    const val CLOCK_TOP_MARGIN_DP = 48
    const val INFO_ROW_TOP_MARGIN_DP = 4
    const val FAVORITES_TOP_MARGIN_DP = 8
    /** Enlarges the date and battery double-tap targets. */
    const val INFO_TOUCH_PADDING_DP = 6
    const val INFO_GAP_DP = 8
    const val FAVORITE_PADDING_H_DP = 6
    const val ICON_SIZE_DP = 28
    const val ICON_PADDING_DP = 4

    // dp, not sp: at the largest font scale a 60sp clock wraps its AM/PM. The drawer keeps sp.
    const val CLOCK_DP = 60f
    /** Also the smallest a favorite can be. */
    const val INFO_DP = 18f

    // ---- Kolibri defaults -----------------------------------------------------
    /** Kolibri's size slider (0..2) at its default. */
    const val LAYOUT_SCALE = 0.6f
    const val MAX_FAVORITE_RELATIVE_TO_CLOCK = 0.75f
    const val VERTICAL_PADDING_FACTOR = 0.6f
    const val FAVORITES_BOLD = true
    const val OUTLINE_DP = 2f
    const val PRESSED_ALPHA = 180
    const val TEXT_COLOR = 0xFFFFFFFF.toInt()

    /** Unity Kolibri's "smart contrast", keyed on WallpaperColors.HINT_SUPPORTS_DARK_TEXT. */
    fun palette(lightWallpaper: Boolean): Palette =
        if (lightWallpaper) {
            Palette(text = 0xFF000000.toInt(), darkBarIcons = true)
        } else {
            Palette(text = TEXT_COLOR, darkBarIcons = false)
        }

    const val UNAVAILABLE_ALPHA = 0.45f

    fun rowAlpha(status: AppStatus): Float = if (status == AppStatus.READY) 1f else UNAVAILABLE_ALPHA

    const val DOUBLE_TAP_MS = 300L

    /** A [scale] above 1 deliberately overshoots [maxPx], as Kolibri's 0..2 slider does. */
    fun favoriteTextSizePx(minPx: Float, maxPx: Float, scale: Float = LAYOUT_SCALE): Float {
        val safeMin = minPx.coerceAtLeast(1f)
        val safeMax = maxPx.coerceAtLeast(safeMin)
        return safeMin + (safeMax - safeMin) * scale.coerceIn(0f, 2f)
    }

    fun favoriteVerticalPaddingPx(textSizePx: Float, factor: Float = VERTICAL_PADDING_FACTOR): Int =
        (textSizePx * factor.coerceIn(0f, 2f)).toInt()

    /** WCAG relative luminance; alpha is ignored. */
    fun luminance(argb: Int): Double {
        fun linear(c: Int): Double {
            val v = c / 255.0
            return if (v < 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * linear((argb shr 16) and 0xFF) +
            0.7152 * linear((argb shr 8) and 0xFF) +
            0.0722 * linear(argb and 0xFF)
    }

    /** A translucent rim — white for dark text, black for light, weaker towards mid-tones. */
    fun tonalOutlineColor(textColor: Int): Int {
        val l = luminance(textColor)
        fun lerp(a: Double, b: Double, f: Double) = (a + f.coerceIn(0.0, 1.0) * (b - a)).coerceIn(0.0, 1.0)
        fun alpha(a: Double) = (a * 255).toInt().coerceIn(0, 255)
        return when {
            l < 0.1 -> argb(204, 255, 255, 255)
            l < 0.5 -> argb(alpha(lerp(0.75, 0.4, (l - 0.1) / 0.4)), 255, 255, 255)
            l < 0.9 -> argb(alpha(lerp(0.3, 0.6, (l - 0.5) / 0.4)), 0, 0, 0)
            else -> argb(153, 0, 0, 0)
        }
    }

    fun withAlpha(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)

    fun argb(a: Int, r: Int, g: Int, b: Int): Int = (a shl 24) or (r shl 16) or (g shl 8) or b
}

internal enum class ChargeState { NONE, CHARGING, PROTECTED }

/** The sticky ACTION_BATTERY_CHANGED extras, read as Kolibri's ClockDelegate does. */
internal object BatteryInfo {
    // android.os.BatteryManager.BATTERY_STATUS_* (stable platform API values).
    const val STATUS_CHARGING = 2
    const val STATUS_NOT_CHARGING = 4
    const val STATUS_FULL = 5

    const val UNKNOWN_LABEL = "---%"

    /** Capped at 100: a level above its scale is a misreport. */
    fun percent(level: Int, scale: Int): Int? =
        if (level < 0 || scale <= 0) null else (level.toLong() * 100 / scale).toInt().coerceAtMost(100)

    fun label(percent: Int?, locale: Locale = Locale.getDefault()): String =
        percent?.let { NumberFormat.getPercentInstance(locale).format(it / 100.0) } ?: UNKNOWN_LABEL

    /** Plugged in but not charging: the system holds the charge back (battery protection). */
    fun chargeState(status: Int, plugged: Int): ChargeState = when {
        status == STATUS_CHARGING || status == STATUS_FULL -> ChargeState.CHARGING
        plugged != 0 && status == STATUS_NOT_CHARGING -> ChargeState.PROTECTED
        else -> ChargeState.NONE
    }
}

/** [tap] is true on the second tap within [windowMs]; a third quick tap starts over. */
internal class DoubleTapGate(private val windowMs: Long = HomeStyle.DOUBLE_TAP_MS) {
    private var lastTap = NONE

    fun tap(nowMs: Long): Boolean {
        val isDouble = lastTap != NONE && nowMs - lastTap in 0..windowMs
        lastTap = if (isDouble) NONE else nowMs
        return isDouble
    }

    private companion object {
        const val NONE = Long.MIN_VALUE
    }
}

/** One text colour for home and drawer alike, and what follows from it. */
internal data class Palette(
    val text: Int,
    val darkBarIcons: Boolean,
) {
    // Computed once, not a getter: every drawer row asks for it on each bind. A body val, so it
    // stays out of equals/hashCode/copy (it follows from text anyway).
    val outline: Int = HomeStyle.tonalOutlineColor(text)
    val selection: Int get() = (text and 0xFFFFFF) or 0x66000000
}

internal fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
