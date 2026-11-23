package com.github.reygnn.verso_launcher

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import android.util.Log
import android.widget.Toast

/**
 * What a double-tap on the clock, the date and the battery opens: the alarms, the calendar at
 * today, the battery usage. Another app answers each of them and may refuse (hard rule 2): then a
 * toast says so, and nothing crashes.
 */
internal class HomeTargets(private val activity: Activity) {

    fun openAlarms() = startGuarded(Intent(AlarmClock.ACTION_SHOW_ALARMS))

    fun openCalendar() {
        val day = Uri.parse(StrictModeLog.CALENDAR_TIME + System.currentTimeMillis())
        startGuarded(Intent(Intent.ACTION_VIEW).setData(day))
    }

    fun openBatteryUsage() = startGuarded(Intent(Intent.ACTION_POWER_USAGE_SUMMARY))

    private fun startGuarded(intent: Intent) {
        // any app may refuse the intent (none there, denied, anything else): say so, never crash
        @Suppress("TooGenericExceptionCaught") try {
            activity.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: RuntimeException) {
            Log.w("Verso", "start failed: $intent", e)
            noApp()
        }
    }

    private fun noApp() =
        Toast.makeText(activity, activity.getString(R.string.toast_no_app), Toast.LENGTH_SHORT).show()
}
