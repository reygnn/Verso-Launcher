package com.github.reygnn.verso_launcher

import kotlin.math.pow

/**
 * The front page's look, ported from Kolibri Launcher's home screen with Kolibri's DEFAULT
 * settings baked in (Verso has no settings screen). Pure arithmetic only, so the numbers that
 * decide how the home page looks are pinned by a JVM test ([HomeStyleTest]) rather than buried
 * in view code. The Activity converts sp/dp to pixels and applies the results.
 *
 * Kolibri sources these mirror (for the next person who wants to keep the two in step):
 *  - text sizes / padding:  ui/home/LayoutCalculator + AppConstants (DEFAULT_LAYOUT_SCALE,
 *                           MAX_APP_TEXT_SCALE_RELATIVE_TO_TIME, DEFAULT_VERTICAL_PADDING_FACTOR)
 *  - spacing:               res/values/dimens.xml (layout_padding, spacing_xlarge, …)
 *  - outline colour:        domain/usecase/ObserveUiColorsUseCase.calculateTonalShadowColor
 *  - battery:               common-ui/timeinfo/ClockDelegate
 */
internal object HomeStyle {

    // ---- Kolibri dimens (dp / sp) ---------------------------------------------
    /** Outer padding of the home page, inside the system-bar insets. */
    const val PAGE_PADDING_DP = 24
    /** Gap above the clock block. */
    const val CLOCK_TOP_MARGIN_DP = 48
    /** Gap between the clock line and the date/battery line. */
    const val INFO_ROW_TOP_MARGIN_DP = 4
    /** Gap between the clock block and the favorites (Kolibri's content top margin at its
     *  default scale of 0: just spacing_medium). */
    const val FAVORITES_TOP_MARGIN_DP = 8
    /** Padding around the date and battery texts — enlarges their double-tap targets. */
    const val INFO_TOUCH_PADDING_DP = 6
    /** Gap between date and battery. */
    const val INFO_GAP_DP = 8
    /** Horizontal padding of a favorite row. */
    const val FAVORITE_PADDING_H_DP = 6
    /** Charging / protection icon box and its inner padding. */
    const val ICON_SIZE_DP = 28
    const val ICON_PADDING_DP = 4

    /** The clock. */
    const val CLOCK_SP = 60f
    /** Date and battery. Also the SMALLEST a favorite can be. */
    const val INFO_SP = 18f

    // ---- Kolibri defaults -----------------------------------------------------
    /** Favorites size slider (0..2) at Kolibri's default. */
    const val LAYOUT_SCALE = 0.6f
    /** The LARGEST a favorite can be, relative to the clock. */
    const val MAX_FAVORITE_RELATIVE_TO_CLOCK = 0.75f
    /** Vertical padding of a favorite row, as a fraction of its text size. */
    const val VERTICAL_PADDING_FACTOR = 0.6f
    /** Favorites are bold by default. */
    const val FAVORITES_BOLD = true
    /** Width of the contrast outline drawn around every home text, in dp. */
    const val OUTLINE_DP = 2f
    /** Alpha of a favorite's text while pressed (the subtle press feedback, no ripple). */
    const val PRESSED_ALPHA = 180
    /** Kolibri's default text colour: white. */
    const val TEXT_COLOR = 0xFFFFFFFF.toInt()

    /** Two taps within this many ms on the clock / date / battery count as a double tap. */
    const val DOUBLE_TAP_MS = 300L

    /**
     * A favorite's text size: linear between [minPx] (the date size) and [maxPx] (three
     * quarters of the clock), positioned by [scale]. Kolibri's slider runs 0..2, so a scale
     * above 1 deliberately overshoots [maxPx]; anything outside 0..2 is clamped.
     */
    fun favoriteTextSizePx(minPx: Float, maxPx: Float, scale: Float = LAYOUT_SCALE): Float {
        val safeMin = minPx.coerceAtLeast(1f)
        val safeMax = maxPx.coerceAtLeast(safeMin)
        return safeMin + (safeMax - safeMin) * scale.coerceIn(0f, 2f)
    }

    /** A favorite's top/bottom padding, proportional to its text size. */
    fun favoriteVerticalPaddingPx(textSizePx: Float, factor: Float = VERTICAL_PADDING_FACTOR): Int =
        (textSizePx * factor.coerceIn(0f, 2f)).toInt()

    /** WCAG relative luminance (0 = black, 1 = white) of an ARGB colour; alpha is ignored. */
    fun luminance(argb: Int): Double {
        fun linear(c: Int): Double {
            val v = c / 255.0
            return if (v < 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * linear((argb shr 16) and 0xFF) +
            0.7152 * linear((argb shr 8) and 0xFF) +
            0.0722 * linear(argb and 0xFF)
    }

    /**
     * The outline colour for a given text colour: a translucent contrast rim — white for dark
     * text, black for light text, its strength easing off towards mid-tones. This is what lets
     * the home text stay readable on any wallpaper without a scrim.
     */
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

    /** [color] with its alpha replaced by [alpha] (0..255). */
    fun withAlpha(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)

    fun argb(a: Int, r: Int, g: Int, b: Int): Int = (a shl 24) or (r shl 16) or (g shl 8) or b
}

/** What the little icon after the battery percentage shows. */
internal enum class ChargeState { NONE, CHARGING, PROTECTED }

/**
 * Reading the sticky ACTION_BATTERY_CHANGED extras, as Kolibri's ClockDelegate does. The
 * BatteryManager status values are mirrored as constants so this stays JVM-testable.
 */
internal object BatteryInfo {
    // android.os.BatteryManager.BATTERY_STATUS_* (stable platform API values).
    const val STATUS_CHARGING = 2
    const val STATUS_NOT_CHARGING = 4
    const val STATUS_FULL = 5

    /** Shown when the level cannot be read. */
    const val UNKNOWN_LABEL = "---%"

    /** Battery percentage from the intent's level/scale, or null if they are unusable. */
    fun percent(level: Int, scale: Int): Int? =
        if (level < 0 || scale <= 0) null else (level.toLong() * 100 / scale).toInt()

    fun label(percent: Int?): String = percent?.let { "$it%" } ?: UNKNOWN_LABEL

    /**
     * Charging (or full on the charger) shows a bolt. Plugged in but NOT charging means the
     * system is holding the charge back (battery protection / adaptive charging) — a shield.
     */
    fun chargeState(status: Int, plugged: Int): ChargeState = when {
        status == STATUS_CHARGING || status == STATUS_FULL -> ChargeState.CHARGING
        plugged != 0 && status == STATUS_NOT_CHARGING -> ChargeState.PROTECTED
        else -> ChargeState.NONE
    }
}

/**
 * Turns single clicks into a double tap: [tap] answers true on the second tap within
 * [windowMs] of the first, and then starts over (a third quick tap is a new first tap).
 */
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
