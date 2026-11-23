package com.github.reygnn.verso_launcher

import android.os.strictmode.ContentUriWithoutPermissionViolation
import android.os.strictmode.Violation
import android.provider.CalendarContract
import android.util.Log

/**
 * What the daily-driver build's StrictMode writes to logcat ([VersoApp.onCreate]): every
 * violation, tagged "StrictMode" as StrictMode's own log does, but for the known false alarm.
 */
internal object StrictModeLog {

    /** The day the calendar opens at ([HomeTargets.openCalendar]): this, then the time in ms. */
    val CALENDAR_TIME: String = "${CalendarContract.CONTENT_URI}/time/"

    /**
     * Whether [violation] is a finding. The calendar's day is not: StrictMode flags every
     * content URI an intent carries without a grant flag, and that URI is the one Android
     * documents for "open the calendar at this time".
     *
     * Why: a grant flag is no fix. Verso holds no read permission on the calendar provider, so the
     *   grant could make the start fail. The calendar app reads only the time from the path.
     */
    fun isFinding(violation: Violation): Boolean =
        !(violation is ContentUriWithoutPermissionViolation && violation.message.orEmpty().startsWith(CALENDAR_TIME))

    fun log(violation: Violation) {
        if (isFinding(violation)) Log.d("StrictMode", "StrictMode policy violation", violation)
    }
}
