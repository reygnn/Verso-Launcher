package com.github.reygnn.verso_launcher

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextClock
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowToast

/** Hard rule 2: an app that refuses the clock's, the date's or the battery's intent is a toast, never a crash. */
@RunWith(RobolectricTestRunner::class)
class HomePageStartGuardTest {

    /** Throws [refusal] from every startActivity, as a missing or locked-down app does. */
    class RefusingActivity : Activity() {
        var refusal: RuntimeException? = null
        override fun startActivity(intent: Intent?) {
            refusal?.let { throw it }
            super.startActivity(intent)
        }
    }

    private val activity = Robolectric.buildActivity(RefusingActivity::class.java).setup().get()
    private val page = HomePage(activity, onLaunch = {}, onEdit = {}, onOpenDrawer = {}, onOpenRecents = {})

    private val clock: View get() = page.view.collect<TextClock>().first()
    private val date: View get() = page.view.collect<TextClock>()[1]
    private val battery: View get() = page.view.collect<TextView>().first { it.text.toString() == BatteryInfo.UNKNOWN_LABEL }

    /** One accessibility click acts at once (no double-tap gate). */
    private fun tap(target: View, refusal: RuntimeException) {
        activity.refusal = refusal
        target.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null)
    }

    private fun assertNoAppSaid(toasts: Int = 1) {
        assertEquals(toasts, ShadowToast.shownToastCount())
        assertEquals(activity.getString(R.string.toast_no_app), ShadowToast.getTextOfLatestToast())
    }

    // Each target goes through the guard: a handler starting its intent unguarded would crash here.

    @Test fun `the clock's refused alarms say so`() {
        tap(clock, SecurityException("not exported"))
        assertNoAppSaid()
    }

    @Test fun `the date's refused calendar says so`() {
        tap(date, SecurityException("not exported"))
        assertNoAppSaid()
    }

    @Test fun `the battery's refused usage screen says so`() {
        tap(battery, SecurityException("not exported"))
        assertNoAppSaid()
    }

    @Test fun `no app, a denial and any other failure each say so`() {
        tap(clock, ActivityNotFoundException("no alarms app"))
        tap(clock, SecurityException("not exported"))
        tap(clock, IllegalStateException("system_server gone"))
        assertNoAppSaid(toasts = 3)
    }
}
