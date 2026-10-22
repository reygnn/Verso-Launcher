package com.github.reygnn.verso_launcher

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextClock
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
    private val page = HomePage(activity, onLaunch = {}, onEdit = {}, onOpenDrawer = {})

    private fun tapClock(refusal: RuntimeException) {
        activity.refusal = refusal
        // One accessibility click acts at once (no double-tap gate).
        page.view.collect<TextClock>().first().performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null)
    }

    private fun assertNoAppSaid() =
        assertEquals(activity.getString(R.string.toast_no_app), ShadowToast.getTextOfLatestToast())

    @Test fun `no app for the intent says so`() {
        tapClock(ActivityNotFoundException("no alarms app"))
        assertNoAppSaid()
    }

    @Test fun `an app that denies the start says so`() {
        tapClock(SecurityException("not exported"))
        assertNoAppSaid()
    }

    @Test fun `any other refusal says so too`() {
        tapClock(IllegalStateException("system_server gone"))
        assertNoAppSaid()
    }
}
