package com.github.reygnn.verso_launcher

import android.content.ComponentName
import android.content.Context
import android.content.pm.LauncherApps
import android.os.SystemClock
import android.os.UserHandle
import android.os.UserManager
import android.util.Log

/**
 * Starting an app, or its "App info", in the entry's own profile (hard rule 8). Any app or the
 * system may refuse: a HOME app says so and never crashes (hard rule 2). A paused profile's first
 * tap only turns it back on; [profileAvailable] then says the app is ready, and the user decides
 * whether to start it. Main thread only.
 */
internal class AppLauncher(
    private val context: Context,
    private val system: ProfileApps,
    private val userOf: (AppEntry) -> UserHandle?,
    private val say: (text: String, long: Boolean) -> Unit,
    private val launched: (AppEntry) -> Unit,
    private val now: () -> Long = SystemClock::uptimeMillis,
) {

    /** The five system calls, so a test can refuse any of them. */
    interface ProfileApps {
        fun isPaused(user: UserHandle): Boolean

        /** Throws SecurityException unless Verso is the default home app. */
        fun unpause(user: UserHandle)
        fun isEnabled(component: ComponentName, user: UserHandle): Boolean
        fun start(component: ComponentName, user: UserHandle)
        fun startDetails(component: ComponentName, user: UserHandle)
    }

    class System(private val launcherApps: LauncherApps, private val userManager: UserManager) : ProfileApps {
        override fun isPaused(user: UserHandle) = userManager.isQuietModeEnabled(user)
        override fun unpause(user: UserHandle) {
            userManager.requestQuietModeEnabled(false, user)
        }
        override fun isEnabled(component: ComponentName, user: UserHandle) = launcherApps.isActivityEnabled(component, user)
        override fun start(component: ComponentName, user: UserHandle) = launcherApps.startMainActivity(component, user, null, null)
        override fun startDetails(component: ComponentName, user: UserHandle) =
            launcherApps.startAppDetailsActivity(component, user, null, null)
    }

    /** The profile a tap turned back on, and that app's label, for the "ready" toast. */
    private var waitingFor: Pair<UserHandle, String>? = null
    // Only an answer within PROFILE_WAIT_MS gets the toast: work apps turned on hours later must
    // not announce an app nobody just asked for. Not cleared in onStop — the work challenge covers us.
    private var waitingSince = 0L

    fun launch(entry: AppEntry) {
        val user = userOf(entry)
        val component = ComponentName(entry.packageName, entry.className)
        // any app may refuse the launch: say so, never crash
        @Suppress("TooGenericExceptionCaught") try {
            when {
                // The entry's profile is gone (a work profile removed since the list loaded).
                user == null -> notFound(entry)
                system.isPaused(user) -> unpause(user, entry)
                // Component gone since the list loaded: starting it would throw a
                // SecurityException and misreport it as "denied".
                !system.isEnabled(component, user) -> {
                    Log.w("Verso", "launch unavailable: ${entry.key}")
                    notFound(entry)
                }
                else -> {
                    system.start(component, user)
                    // Only a launch that actually started counts as recent.
                    launched(entry)
                }
            }
        } catch (e: SecurityException) {
            Log.w("Verso", "launch denied: ${entry.key}", e)
            say(context.getString(R.string.toast_denied, entry.label), false)
        } catch (e: RuntimeException) {
            // Dead system_server or similar — don't crash HOME.
            Log.w("Verso", "launch failed: ${entry.key}", e)
            notFound(entry)
        }
    }

    private fun unpause(user: UserHandle, entry: AppEntry) {
        waitingFor = user to entry.label
        waitingSince = now()
        try {
            system.unpause(user)
        } catch (e: SecurityException) {
            waitingFor = null
            Log.w("Verso", "quiet mode denied: ${entry.key}", e)
            say(context.getString(R.string.toast_not_default_home), true)
        }
    }

    /** ACTION_PROFILE_AVAILABLE for [user]: the app a tap was waiting for is ready, if it was asked for lately. */
    fun profileAvailable(user: UserHandle?) {
        val waiting = waitingFor ?: return
        if (user != waiting.first) return
        waitingFor = null
        if (now() - waitingSince > PROFILE_WAIT_MS) return
        say(context.getString(R.string.toast_app_ready, waiting.second), true)
    }

    fun openAppInfo(entry: AppEntry) {
        val user = userOf(entry) ?: return notFound(entry)
        // the settings app may refuse: say so, never crash
        @Suppress("TooGenericExceptionCaught") try {
            system.startDetails(ComponentName(entry.packageName, entry.className), user)
        } catch (e: SecurityException) {
            Log.w("Verso", "app info denied: ${entry.key}", e)
            say(context.getString(R.string.toast_denied, entry.label), false)
        } catch (e: RuntimeException) {
            Log.w("Verso", "app info failed: ${entry.key}", e)
            notFound(entry)
        }
    }

    private fun notFound(entry: AppEntry) = say(context.getString(R.string.toast_not_found, entry.label), false)

    companion object {
        const val PROFILE_WAIT_MS = 2 * 60 * 1000L
    }
}
