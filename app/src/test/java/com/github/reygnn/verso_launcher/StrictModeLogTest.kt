package com.github.reygnn.verso_launcher

import android.net.Uri
import android.os.strictmode.ContentUriWithoutPermissionViolation
import android.os.strictmode.Violation
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Which StrictMode violations the daily-driver build logs. The violations are the platform's own
 * (android-all 36): their constructors are hidden, so they are built by reflection.
 */
@RunWith(RobolectricTestRunner::class)
class StrictModeLogTest {

    private fun contentUriViolation(uri: String): Violation =
        ContentUriWithoutPermissionViolation::class.java
            .getConstructor(Uri::class.java, String::class.java)
            .newInstance(Uri.parse(uri), "Intent.getData()")

    @Test fun `the calendar's day is no finding`() {
        assertFalse(StrictModeLog.isFinding(contentUriViolation("content://com.android.calendar/time/1795462175213")))
    }

    @Test fun `the day HomeTargets opens is the one left out`() {
        val uri = StrictModeLog.CALENDAR_TIME + 1795462175213L
        assertFalse(StrictModeLog.isFinding(contentUriViolation(uri)))
    }

    @Test fun `another content URI without a grant is a finding`() {
        assertTrue(StrictModeLog.isFinding(contentUriViolation("content://com.android.calendar/events/7")))
        assertTrue(StrictModeLog.isFinding(contentUriViolation("content://media/external/images/media/1")))
    }

    @Test fun `any other violation is a finding`() {
        val leak = Class.forName("android.os.strictmode.LeakedClosableViolation")
            .getConstructor(String::class.java)
            .newInstance("A resource was acquired and never released") as Violation
        assertTrue(StrictModeLog.isFinding(leak))
    }
}
