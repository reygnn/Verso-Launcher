package com.github.reygnn.verso_launcher

import android.content.Context

import java.text.NumberFormat
import java.util.Locale

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

    // Text sizes on the home page are in dp, NOT sp: the system font scale does not apply here
    // (display size still does). The page is a fixed composition — a 60sp clock at the largest
    // font scale wrapped its AM/PM onto a second line and squeezed the info row. The drawer list
    // keeps sp: it scrolls and ellipsizes, so a large font never breaks it.
    /** The clock. */
    const val CLOCK_DP = 60f
    /** Date and battery. Also the SMALLEST a favorite can be. */
    const val INFO_DP = 18f

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

    /**
     * The colours for the wallpaper behind — Unity Kolibri's "smart contrast": on a wallpaper
     * that supports dark text (Android's WallpaperColors.HINT_SUPPORTS_DARK_TEXT) the text turns
     * black with a white rim, otherwise it stays white with a black rim; the rims come from
     * [tonalOutlineColor], which flips by itself. The drawer's greys, the prompt's bar and the
     * system bars' icons follow the same switch. The drawer's rows are the favorites' colour:
     * both lie on the wallpaper with the same outline; only the hint and the × are dimmed.
     *
     * The prompt sits on a bar of its own ([Palette.promptBar]: translucent white behind dark text,
     * black behind light text) instead of an outline — an EditText cannot take the stroked second
     * pass (cursor and selection would be drawn twice) — and the bar is local: the palette is
     * picked for the whole wallpaper, whose bottom may still be light under light text.
     */
    fun palette(lightWallpaper: Boolean): Palette =
        if (lightWallpaper) {
            Palette(homeText = 0xFF000000.toInt(), listText = 0xFF000000.toInt(), listDim = 0xFF575757.toInt(),
                promptBar = 0xD0FFFFFF.toInt(), darkBarIcons = true)
        } else {
            Palette(homeText = TEXT_COLOR, listText = TEXT_COLOR, listDim = 0xFF999999.toInt(),
                promptBar = 0xD0000000.toInt(), darkBarIcons = false)
        }

    /** Alpha of a row whose app cannot start right away (paused or archived, see AppStatus):
     *  still readable, clearly set apart. */
    const val UNAVAILABLE_ALPHA = 0.45f

    /** The alpha a row of an app in [status] is drawn at. */
    fun rowAlpha(status: AppStatus): Float = if (status == AppStatus.READY) 1f else UNAVAILABLE_ALPHA

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

    /** Battery percentage from the intent's level/scale, or null if they are unusable. Capped
     *  at 100: a level above its scale is a misreport, not a battery past full. */
    fun percent(level: Int, scale: Int): Int? =
        if (level < 0 || scale <= 0) null else (level.toLong() * 100 / scale).toInt().coerceAtMost(100)

    /** "87%", "87 %", "٨٧٪" — the percentage the way [locale] writes it. */
    fun label(percent: Int?, locale: Locale = Locale.getDefault()): String =
        percent?.let { NumberFormat.getPercentInstance(locale).format(it / 100.0) } ?: UNKNOWN_LABEL

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

/**
 * The colour set for one wallpaper brightness (see [HomeStyle.palette]): home text, the drawer's
 * text and dimmed hint, the bar behind the prompt, and whether the system bars need dark icons.
 */
internal data class Palette(
    val homeText: Int,
    val listText: Int,
    val listDim: Int,
    val promptBar: Int,
    val darkBarIcons: Boolean,
) {
    val listOutline: Int get() = HomeStyle.tonalOutlineColor(listText)
    /** The prompt's selection: the text colour, translucent, so the selected text stays legible. */
    val selection: Int get() = (listText and 0xFFFFFF) or 0x66000000
}

/** [value] dp in pixels at the current display density — the one dp helper every view builder
 *  uses (a display-size change recreates the activity, so the density read here is current). */
internal fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
