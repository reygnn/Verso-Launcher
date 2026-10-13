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

    @Test fun `mid tones flip from a white to a black rim at luminance 0_5`() {
        val darkGray = HomeStyle.tonalOutlineColor(0xFF555555.toInt())   // luminance ~0.09..0.1
        val lightGray = HomeStyle.tonalOutlineColor(0xFFCCCCCC.toInt())  // luminance ~0.6
        assertEquals(0xFFFFFF, darkGray and 0xFFFFFF)
        assertEquals(0x000000, lightGray and 0xFFFFFF)
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

    @Test fun `label falls back to Kolibri's placeholder`() {
        assertEquals("87%", BatteryInfo.label(87))
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
