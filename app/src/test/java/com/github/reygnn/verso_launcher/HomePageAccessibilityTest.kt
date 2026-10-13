package com.github.reygnn.verso_launcher

import android.app.Activity
import android.provider.AlarmClock
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * The home page's double-tap targets (clock, date, battery) under a screen reader. A plain
 * click must still need a second one within the double-tap window, but TalkBack's activation
 * — a single accessibility click — must run the action directly, or it is unreachable.
 * Robolectric, because it is real View accessibility plumbing.
 */
@RunWith(RobolectricTestRunner::class)
class HomePageAccessibilityTest {

    private val activity: Activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    private val page = HomePage(activity, onLaunch = {}, onEdit = {}, onOpenDrawer = {})

    /** The clock: the first TextClock on the page (the date is the second). */
    private val clock: View get() = page.view.collect<TextClock>().first()

    /** The date: the second TextClock. */
    private val date: View get() = page.view.collect<TextClock>()[1]

    /** The battery: the text view showing the (still unknown) percentage. */
    private val battery: View get() =
        page.view.collect<android.widget.TextView>().first { it.text.toString() == BatteryInfo.UNKNOWN_LABEL }

    @Test fun `one accessibility click on the clock opens the alarms`() {
        assertTrue(clock.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
        assertEquals(AlarmClock.ACTION_SHOW_ALARMS, shadowOf(activity).nextStartedActivity?.action)
    }

    @Test fun `one accessibility click on the date opens the calendar`() {
        assertTrue(date.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
        val started = shadowOf(activity).nextStartedActivity
        assertEquals(android.content.Intent.ACTION_VIEW, started?.action)
        assertTrue(started?.data.toString().startsWith("content://com.android.calendar/time"))
    }

    @Test fun `one accessibility click on the battery opens its usage screen`() {
        assertTrue(battery.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
        assertEquals(android.content.Intent.ACTION_POWER_USAGE_SUMMARY, shadowOf(activity).nextStartedActivity?.action)
    }

    @Test fun `a real double tap on the clock opens the alarms`() {
        // Robolectric's clock stands still, so two clicks land inside the double-tap window.
        clock.performClick()
        clock.performClick()
        assertEquals(AlarmClock.ACTION_SHOW_ALARMS, shadowOf(activity).nextStartedActivity?.action)
    }

    @Test fun `a single plain tap still opens nothing`() {
        clock.performClick()
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun `TalkBack announces what activating the clock does`() {
        val info = AccessibilityNodeInfo()
        clock.onInitializeAccessibilityNodeInfo(info)
        val click = info.actionList.single { it.id == AccessibilityNodeInfo.ACTION_CLICK }
        assertEquals(activity.getString(R.string.a11y_open_alarms), click.label)
    }

    private inline fun <reified T : View> View.collect(): List<T> =
        collectAll(this).filterIsInstance<T>()

    private fun collectAll(v: View): List<View> =
        listOf(v) + ((v as? ViewGroup)?.let { g -> (0 until g.childCount).flatMap { collectAll(g.getChildAt(it)) } } ?: emptyList())
}
