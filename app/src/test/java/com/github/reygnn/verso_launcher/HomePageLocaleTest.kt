package com.github.reygnn.verso_launcher

import android.app.Activity
import android.content.Intent
import android.os.BatteryManager
import android.os.Looper
import android.widget.TextClock
import android.widget.TextView
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The home page in a language that writes its own digits (Arabic, Egypt: ٠١٢…). Robolectric
 * runs every other test in en-US, so without this nothing ever checks that the clock, the date
 * and the battery come out in the device's numerals rather than hard-wired ASCII ones.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "ar-rEG")
class HomePageLocaleTest {

    private val activity: Activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    private val page = HomePage(activity, onLaunch = {}, onEdit = {}, onOpenDrawer = {})

    private fun String.hasArabicDigits() = any { it in '٠'..'٩' }
    private fun String.hasAsciiDigits() = any { it in '0'..'9' }

    private fun shown() {
        activity.setContentView(page.view)
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun `clock and date use the language's own digits`() {
        shown()
        for (clock in page.view.collect<TextClock>()) {
            val text = clock.text.toString()
            assertTrue("'$text'", text.hasArabicDigits() && !text.hasAsciiDigits())
        }
    }

    @Test fun `the battery percentage uses the language's own digits`() {
        shown()
        page.startBattery()
        activity.sendBroadcast(
            Intent(Intent.ACTION_BATTERY_CHANGED)
                .putExtra(BatteryManager.EXTRA_LEVEL, 79)
                .putExtra(BatteryManager.EXTRA_SCALE, 100)
        )
        shadowOf(Looper.getMainLooper()).idle()
        val label = page.view.collect<TextView>().map { it.text.toString() }.first { it.hasArabicDigits() || '%' in it || '٪' in it }
        assertTrue("'$label'", label.hasArabicDigits() && !label.hasAsciiDigits())
    }
}
