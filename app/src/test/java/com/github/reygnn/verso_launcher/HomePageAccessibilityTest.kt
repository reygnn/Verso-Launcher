package com.github.reygnn.verso_launcher

import android.app.Activity
import android.content.Intent
import android.os.BatteryManager
import android.os.Looper
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
    private var recentsOpened = 0
    private val page = HomePage(
        activity, onLaunch = {}, onAppInfo = {}, onOpenDrawer = { drawerOpened++ }, onOpenRecents = { recentsOpened++ },
    )

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

    @Test fun `the page offers 'open all apps' as an accessibility action, and it works`() {
        val info = AccessibilityNodeInfo()
        page.view.onInitializeAccessibilityNodeInfo(info)
        val action = info.actionList.single { it.id == R.id.action_open_drawer }
        assertEquals(activity.getString(R.string.a11y_open_drawer), action.label)
        assertTrue(page.view.performAccessibilityAction(R.id.action_open_drawer, null))
        assertEquals(1, drawerOpened)
    }

    /**
     * The actions live on the page itself: it must be a stop TalkBack can land on, or a touch on an
     * empty spot finds nothing and the actions cannot be reached (seen on the A17). The view's flag
     * is what Verso sets; that TalkBack lands there was checked by ear on the A17 — Robolectric does
     * not carry the flag into the AccessibilityNodeInfo.
     */
    @Test fun `the page is a stop for TalkBack, so its actions can be reached`() {
        assertTrue(page.view.isScreenReaderFocusable)
        assertEquals(activity.getString(R.string.a11y_home), page.view.contentDescription)
    }

    /** The swipe down is invisible to a screen reader too: the recents get an action of their own. */
    @Test fun `the page offers 'open recent apps' as an accessibility action, and it works`() {
        val info = AccessibilityNodeInfo()
        page.view.onInitializeAccessibilityNodeInfo(info)
        val action = info.actionList.single { it.id == R.id.action_open_recents }
        assertEquals(activity.getString(R.string.a11y_open_recents), action.label)
        assertTrue(page.view.performAccessibilityAction(R.id.action_open_recents, null))
        assertEquals(1, recentsOpened)
        assertEquals("not the drawer", 0, drawerOpened)
    }

    private fun batteryLine(status: Int, plugged: Int): android.widget.TextView {
        activity.sendBroadcast(
            Intent(Intent.ACTION_BATTERY_CHANGED)
                .putExtra(BatteryManager.EXTRA_LEVEL, 74)
                .putExtra(BatteryManager.EXTRA_SCALE, 100)
                .putExtra(BatteryManager.EXTRA_STATUS, status)
                .putExtra(BatteryManager.EXTRA_PLUGGED, plugged)
        )
        shadowOf(Looper.getMainLooper()).idle()
        val label = BatteryInfo.label(BatteryInfo.percent(74, 100))
        return page.view.collect<android.widget.TextView>().single { it.text.toString() == label }
    }

    /** One stop, not three: the charge-state icons are none of their own, the battery line reads their state. */
    @Test fun `the battery line reads the charge state, its icons are no stops of their own`() {
        page.startBattery()
        try {
            val label = BatteryInfo.label(BatteryInfo.percent(74, 100))
            val charging = batteryLine(BatteryManager.BATTERY_STATUS_CHARGING, BatteryManager.BATTERY_PLUGGED_AC)
            assertEquals(activity.getString(R.string.a11y_battery_charging, label), charging.contentDescription)
            val held = batteryLine(BatteryManager.BATTERY_STATUS_NOT_CHARGING, BatteryManager.BATTERY_PLUGGED_AC)
            assertEquals(activity.getString(R.string.a11y_battery_protected, label), held.contentDescription)
            val plain = batteryLine(BatteryManager.BATTERY_STATUS_DISCHARGING, 0)
            assertEquals(activity.getString(R.string.a11y_battery, label), plain.contentDescription)
            val icons = page.view.collect<OutlinedImageView>()
            assertEquals("the bolt and the shield", 2, icons.size)
            assertTrue(icons.all { it.importantForAccessibility == View.IMPORTANT_FOR_ACCESSIBILITY_NO })
        } finally {
            page.stopBattery()
        }
    }

    @Test fun `with no favorites the hint is shown, and tapping it opens the drawer`() {
        page.render(favs = emptyList(), loaded = true)
        val hint = page.view.collect<android.widget.TextView>()
            .single { it.text.toString() == activity.getString(R.string.home_hint) }
        assertTrue(hint.performClick())
        assertEquals(1, drawerOpened)
    }

    @Test fun `a favorite whose app is paused, then resumed, is redrawn both times`() {
        val paused = AppEntry("Gmail (Work)", "com.g", "com.g.Main", "Gmail (Work)", profile = 10, status = AppStatus.PAUSED)
        page.render(favs = listOf(paused), loaded = true)
        assertEquals(HomeStyle.UNAVAILABLE_ALPHA, favorite("Gmail (Work)").alpha, 0f)
        // Its name stays the label; the state beside it, and a double-tap that says what it does.
        assertNull(favorite("Gmail (Work)").contentDescription)
        assertEquals(activity.getString(R.string.a11y_state_paused), favorite("Gmail (Work)").stateDescription)
        assertEquals(activity.getString(R.string.a11y_do_turn_on_work), clickLabel(favorite("Gmail (Work)")))

        // Same key and label: the page must not skip the rebuild.
        page.render(favs = listOf(paused.copy(status = AppStatus.READY)), loaded = true)
        assertEquals(1f, favorite("Gmail (Work)").alpha, 0f)
        assertNull(favorite("Gmail (Work)").stateDescription)
        assertNull("a plain click: TalkBack's own words", clickLabel(favorite("Gmail (Work)")))
    }

    private fun clickLabel(view: android.view.View): CharSequence? =
        view.createAccessibilityNodeInfo().actionList
            .firstOrNull { it.id == android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK }?.label

    @Test fun `switching the text colour redraws the favorites in it`() {
        val gmail = AppEntry("Gmail", "com.g", "com.g.Main", "Gmail")
        page.render(favs = listOf(gmail), loaded = true)
        assertEquals(HomeStyle.TEXT_COLOR, (favorite("Gmail") as android.widget.TextView).currentTextColor)

        page.applyPalette(HomeStyle.palette(lightWallpaper = true))

        assertEquals(0xFF000000.toInt(), (favorite("Gmail") as android.widget.TextView).currentTextColor)
    }

    /**
     * A cold start draws the cached favorites, the drawer then renders "not loaded" (which draws
     * nothing), and the wallpaper's answer switches the colour before the config has loaded.
     */
    @Test fun `a colour change before the load redraws the cached favorites in it`() {
        val gmail = AppEntry("Gmail", "com.g", "com.g.Main", "Gmail")
        page.render(favs = listOf(gmail), loaded = true)
        page.render(favs = emptyList(), loaded = false)

        page.applyPalette(HomeStyle.palette(lightWallpaper = true))

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
