package com.github.reygnn.verso_launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.LauncherApps
import android.os.Handler
import android.os.Looper
import android.os.UserHandle

/**
 * The system side of [AppChanges]: every way Android says that the apps changed. Registered once
 * for the process, on the Application, and never unregistered: the list is the process's (hard
 * rule 16), so its changes are heard whether a window exists or not.
 */
internal object AppWatch {

    /**
     * Reports to [onChange] each package and profile change, and to [onProfileAvailable] the profile
     * that was turned back on. Both run on the main thread.
     *
     * Why: nothing here is caught (hard rule 2). registerCallback throws only if the system server
     *   is gone, and the whole system restarts with it. registerReceiver throws at 1000 receivers
     *   in a process, which takes a leak in this one; the crash ends it, and the new process
     *   registers anew. A catch would keep the leak and a launcher that never hears of an app again.
     */
    fun start(
        context: Context,
        launcherApps: LauncherApps,
        onChange: () -> Unit,
        onProfileAvailable: (UserHandle?) -> Unit,
    ) {
        launcherApps.registerCallback(
            object : LauncherApps.Callback() {
                override fun onPackageAdded(packageName: String, user: UserHandle) = onChange()
                override fun onPackageRemoved(packageName: String, user: UserHandle) = onChange()
                override fun onPackageChanged(packageName: String, user: UserHandle) = onChange()
                override fun onPackagesAvailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) =
                    onChange()
                override fun onPackagesUnavailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) =
                    onChange()
                override fun onPackagesSuspended(packageNames: Array<out String>, user: UserHandle) = onChange()
                override fun onPackagesUnsuspended(packageNames: Array<out String>, user: UserHandle) = onChange()
            },
            Handler(Looper.getMainLooper()),
        )
        context.registerReceiver(
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    onChange()
                    if (intent.action != Intent.ACTION_PROFILE_AVAILABLE) return
                    onProfileAvailable(intent.getParcelableExtra(Intent.EXTRA_USER, UserHandle::class.java))
                }
            },
            IntentFilter().apply {
                addAction(Intent.ACTION_PROFILE_ADDED)
                addAction(Intent.ACTION_PROFILE_REMOVED)
                addAction(Intent.ACTION_PROFILE_AVAILABLE)
                addAction(Intent.ACTION_PROFILE_UNAVAILABLE)
            },
            Context.RECEIVER_NOT_EXPORTED,
        )
    }
}
