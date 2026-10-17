package com.github.reygnn.verso_launcher

import android.app.Activity
import android.provider.AlarmClock
import android.view.View
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

/** TalkBack's activation is a single accessibility click, so it must bypass the double-tap gate. */
@RunWith(RobolectricTestRunner::class)
class HomePageAccessibilityTest {

    private val activity: Activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    private var drawerOpened = 0
    private val page = HomePage(activity, onLaunch = {}, onEdit = {}, onOpenDrawer = { drawerOpened++ })

    private val clock: View get() = page.view.collect<TextClock>().first()

    private val date: View get() = page.view.collect<TextClock>()[1]

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
        assertTrue(started?.data.toString(), Regex("""content://com\.android\.calendar/time/\d+""").matches(started?.data.toString()))
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

    @Test fun `the page offers "open all apps" as an accessibility action, and it works`() {
        val info = AccessibilityNodeInfo()
        page.view.onInitializeAccessibilityNodeInfo(info)
        val action = info.actionList.single { it.id == R.id.action_open_drawer }
        assertEquals(activity.getString(R.string.a11y_open_drawer), action.label)
        assertTrue(page.view.performAccessibilityAction(R.id.action_open_drawer, null))
        assertEquals(1, drawerOpened)
    }

    @Test fun `with no favorites the hint is shown, and tapping it opens the drawer`() {
        page.render(favs = emptyList(), loaded = true)
        val hint = page.view.collect<android.widget.TextView>()
            .single { it.text.toString() == activity.getString(R.string.home_hint) }
        assertTrue(hint.performClick())
        assertEquals(1, drawerOpened)
    }

    @Test fun `a favorite whose app is paused, then resumed, is redrawn both times`() {
        val paused = AppEntry.of("Gmail (Work)", "com.g", "com.g.Main", "Gmail (Work)", profile = 10, status = AppStatus.PAUSED)
        page.render(favs = listOf(paused), loaded = true)
        assertEquals(HomeStyle.UNAVAILABLE_ALPHA, favorite("Gmail (Work)").alpha, 0f)
        assertEquals(activity.getString(R.string.a11y_app_paused, "Gmail (Work)"), favorite("Gmail (Work)").contentDescription)

        // Same key and label: the page must not skip the rebuild.
        page.render(favs = listOf(paused.copy(status = AppStatus.READY)), loaded = true)
        assertEquals(1f, favorite("Gmail (Work)").alpha, 0f)
        assertNull(favorite("Gmail (Work)").contentDescription)
    }

    @Test fun `switching the text colour redraws the favorites in it`() {
        val gmail = AppEntry.of("Gmail", "com.g", "com.g.Main", "Gmail")
        page.render(favs = listOf(gmail), loaded = true)
        assertEquals(HomeStyle.TEXT_COLOR, (favorite("Gmail") as android.widget.TextView).currentTextColor)

        page.applyTextColor(0xFF000000.toInt())

        assertEquals(0xFF000000.toInt(), (favorite("Gmail") as android.widget.TextView).currentTextColor)
    }

    private fun favorite(label: String): View =
        page.view.collect<android.widget.TextView>().single { it.text.toString() == label }

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
}
