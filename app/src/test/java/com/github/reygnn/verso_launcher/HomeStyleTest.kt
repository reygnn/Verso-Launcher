package com.github.reygnn.verso_launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the Kolibri-ported home page numbers in [HomeStyle], [BatteryInfo] and
 * [DoubleTapGate]. The expected values are Kolibri's defaults, so a drift from Kolibri's
 * look shows up here rather than on a device.
 */
class HomeStyleTest {

    // ---- favorites size -------------------------------------------------------

    @Test fun `default favorite size is Kolibri's 34_2sp at density 1`() {
        // min 18, max 60 * 0.75 = 45, scale 0.6 -> 18 + 27 * 0.6 = 34.2
        assertEquals(34.2f, HomeStyle.favoriteTextSizePx(18f, 45f), 0.001f)
    }

    @Test fun `scale is clamped to Kolibri's 0 to 2 slider`() {
        assertEquals(18f, HomeStyle.favoriteTextSizePx(18f, 45f, scale = -1f), 0.001f)
        assertEquals(72f, HomeStyle.favoriteTextSizePx(18f, 45f, scale = 5f), 0.001f)
    }

    @Test fun `a max below the min is lifted to the min`() {
        assertEquals(18f, HomeStyle.favoriteTextSizePx(18f, 10f), 0.001f)
    }

    @Test fun `vertical padding is 0_6 of the text size, truncated`() {
        assertEquals(20, HomeStyle.favoriteVerticalPaddingPx(34.2f))
        assertEquals(0, HomeStyle.favoriteVerticalPaddingPx(34.2f, factor = 0f))
    }

    // ---- outline colour -------------------------------------------------------

    @Test fun `white text gets a 60 percent black outline`() {
        assertEquals(HomeStyle.argb(153, 0, 0, 0), HomeStyle.tonalOutlineColor(0xFFFFFFFF.toInt()))
    }

    @Test fun `black text gets an 80 percent white outline`() {
        assertEquals(HomeStyle.argb(204, 255, 255, 255), HomeStyle.tonalOutlineColor(0xFF000000.toInt()))
    }

    /** An opaque grey whose three channels are [v]. Luminances below are WCAG's for that grey. */
    private fun grey(v: Int) = HomeStyle.argb(255, v, v, v)

    @Test fun `mid tones flip from a white to a black rim at luminance 0_5`() {
        // 0xBB is luminance 0.4969, 0xBC 0.5029 — the neighbouring greys either side of 0.5,
        // so the flip is pinned to 0.5 itself, not just "somewhere between dark and light".
        assertEquals(HomeStyle.argb(102, 255, 255, 255), HomeStyle.tonalOutlineColor(grey(0xBB)))
        assertEquals(HomeStyle.argb(77, 0, 0, 0), HomeStyle.tonalOutlineColor(grey(0xBC)))
    }

    @Test fun `the white rim eases from 75 to 40 percent between luminance 0_1 and 0_5`() {
        // 0x59 is luminance 0.0999 (still the fixed 80 %), 0x5A 0.1022 (interpolation begins).
        assertEquals(HomeStyle.argb(204, 255, 255, 255), HomeStyle.tonalOutlineColor(grey(0x59)))
        assertEquals(HomeStyle.argb(190, 255, 255, 255), HomeStyle.tonalOutlineColor(grey(0x5A)))
        // 0x89 is luminance 0.2502: 37.5 % of the way, 0.75 -> 0.4 gives 0.619 -> alpha 157.
        assertEquals(HomeStyle.argb(157, 255, 255, 255), HomeStyle.tonalOutlineColor(grey(0x89)))
    }

    @Test fun `the black rim strengthens from 30 to 60 percent between luminance 0_5 and 0_9`() {
        // 0xD0 is luminance 0.6308: 32.7 % of the way, 0.3 -> 0.6 gives 0.398 -> alpha 101.
        assertEquals(HomeStyle.argb(101, 0, 0, 0), HomeStyle.tonalOutlineColor(grey(0xD0)))
        // 0xF3 is luminance 0.8963 (last interpolated step), 0xF4 0.9047 (the fixed 60 %).
        assertEquals(HomeStyle.argb(152, 0, 0, 0), HomeStyle.tonalOutlineColor(grey(0xF3)))
        assertEquals(HomeStyle.argb(153, 0, 0, 0), HomeStyle.tonalOutlineColor(grey(0xF4)))
    }

    @Test fun `a paused or archived app is dimmed, a ready one is not`() {
        assertEquals(1f, HomeStyle.rowAlpha(AppStatus.READY), 0f)
        assertEquals(HomeStyle.UNAVAILABLE_ALPHA, HomeStyle.rowAlpha(AppStatus.PAUSED), 0f)
        assertEquals(HomeStyle.UNAVAILABLE_ALPHA, HomeStyle.rowAlpha(AppStatus.ARCHIVED), 0f)
    }

    @Test fun `a paused profile wins over archived - turning it on comes first`() {
        assertEquals(AppStatus.READY, AppStatus.of(profilePaused = false, archived = false))
        assertEquals(AppStatus.PAUSED, AppStatus.of(profilePaused = true, archived = false))
        assertEquals(AppStatus.ARCHIVED, AppStatus.of(profilePaused = false, archived = true))
        assertEquals(AppStatus.PAUSED, AppStatus.of(profilePaused = true, archived = true))
    }

    @Test fun `a dark wallpaper keeps light text with dark rims and light bar icons`() {
        val p = HomeStyle.palette(lightWallpaper = false)
        assertEquals(HomeStyle.TEXT_COLOR, p.homeText)
        assertEquals(HomeStyle.argb(153, 0, 0, 0), p.homeOutline)  // white text: black rim
        assertEquals(0x000000, p.listOutline and 0xFFFFFF)          // light-grey rows: dark rim
        assertEquals(0x000000, p.shadow and 0xFFFFFF)
        assertEquals(false, p.darkBarIcons)
    }

    @Test fun `a light wallpaper flips to dark text with light rims and dark bar icons`() {
        val p = HomeStyle.palette(lightWallpaper = true)
        assertEquals(0xFF000000.toInt(), p.homeText)
        assertEquals(HomeStyle.argb(204, 255, 255, 255), p.homeOutline)  // Unity's black text: 80 % white rim
        assertEquals(0xFFFFFF, p.listOutline and 0xFFFFFF)               // dark-grey rows: light rim
        assertEquals(0xFFFFFF, p.shadow and 0xFFFFFF)
        assertEquals(true, p.darkBarIcons)
    }

    @Test fun `luminance spans 0 to 1`() {
        assertEquals(0.0, HomeStyle.luminance(0xFF000000.toInt()), 1e-9)
        assertEquals(1.0, HomeStyle.luminance(0xFFFFFFFF.toInt()), 1e-9)
    }

    @Test fun `withAlpha replaces only the alpha`() {
        assertEquals(0xB4FFFFFF.toInt(), HomeStyle.withAlpha(0xFFFFFFFF.toInt(), 180))
        assertEquals(0x00123456, HomeStyle.withAlpha(0xFF123456.toInt(), -5))
    }

    // ---- battery --------------------------------------------------------------

    @Test fun `percent from level and scale`() {
        assertEquals(87, BatteryInfo.percent(87, 100))
        assertEquals(50, BatteryInfo.percent(128, 256))
        assertNull(BatteryInfo.percent(-1, 100))
        assertNull(BatteryInfo.percent(50, 0))
    }

    @Test fun `a level above its scale reads as full, never past 100 percent`() {
        assertEquals(100, BatteryInfo.percent(100, 100))
        assertEquals(100, BatteryInfo.percent(103, 100))
    }

    @Test fun `label falls back to Kolibri's placeholder`() {
        assertEquals("87%", BatteryInfo.label(87, java.util.Locale.US))
        assertEquals("87\u00A0%", BatteryInfo.label(87, java.util.Locale.GERMANY))  // German: a space
        assertEquals("---%", BatteryInfo.label(null))
    }

    @Test fun `charging or full is a bolt, plugged but held is a shield`() {
        assertEquals(ChargeState.CHARGING, BatteryInfo.chargeState(BatteryInfo.STATUS_CHARGING, 1))
        assertEquals(ChargeState.CHARGING, BatteryInfo.chargeState(BatteryInfo.STATUS_FULL, 1))
        assertEquals(ChargeState.PROTECTED, BatteryInfo.chargeState(BatteryInfo.STATUS_NOT_CHARGING, 1))
        assertEquals(ChargeState.NONE, BatteryInfo.chargeState(BatteryInfo.STATUS_NOT_CHARGING, 0))
        assertEquals(ChargeState.NONE, BatteryInfo.chargeState(3 /* discharging */, 0))
    }

    // ---- double tap -----------------------------------------------------------

    @Test fun `two taps within the window are a double tap`() {
        val gate = DoubleTapGate(300)
        assertFalse(gate.tap(1_000))
        assertTrue(gate.tap(1_250))
    }

    @Test fun `taps further apart are two single taps`() {
        val gate = DoubleTapGate(300)
        assertFalse(gate.tap(1_000))
        assertFalse(gate.tap(1_400))
        assertTrue(gate.tap(1_500))  // ...but the second one starts a new pair
    }

    @Test fun `a third quick tap starts over instead of firing again`() {
        val gate = DoubleTapGate(300)
        assertFalse(gate.tap(0))
        assertTrue(gate.tap(100))
        assertFalse(gate.tap(200))
    }
}
