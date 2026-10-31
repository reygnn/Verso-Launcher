package com.github.reygnn.verso_launcher

import android.content.pm.LauncherApps
import android.os.StrictMode
import android.os.UserManager
import android.provider.Telephony

/**
 * The launcher's data, owned by the process (VersoApp.core): one [LauncherState] and the app list,
 * both built from the Application. An activity is a view of it — a recreation (language, font or
 * display size; a rotation does not recreate) shares the same state, so nothing loaded is lost or
 * read again, and none of the state's hand-backs needs a living instance to land.
 *
 * Its hand-backs run on the process's main thread and are never dropped while the state is the
 * current one; what touches a window goes to the live instance, if any (VersoApp.instance), which
 * renders the state anew whenever it is created. [reset] ("~restart") replaces the state, and the
 * old one's late hand-backs are dropped.
 *
 * The config is read here, on main, before each state exists (hard rule 5): the user's file at its
 * fixed place ([ConfigFile]), a few KB on local storage. A state keeps the text it was built with;
 * only [reset] reads the file again. Main thread only.
 */
internal class Core(private val app: VersoApp) {

    val apps = Apps(
        launcherApps = app.getSystemService(LauncherApps::class.java),
        userManager = app.getSystemService(UserManager::class.java),
        pm = app.packageManager,
        ownPackage = app.packageName,
        badgedLabels = app.badgedLabels,
        // The badge is localized: part of its cache key, read fresh for each enumeration. The
        // application's resources follow a language change.
        locale = { app.resources.configuration.locales[0].toLanguageTag() },
        defaultSmsPackage = { Telephony.Sms.getDefaultSmsPackage(app) },
    )

    var state: LauncherState = newState()
        private set

    /**
     * "~restart": a fresh state, which reads the config file and order.txt again and loads the app
     * list anew. The recents and the usage stay as the process holds them.
     */
    fun reset() {
        state = newState()
    }

    private fun newState(): LauncherState {
        val dir = beforeState { app.filesDir }
        // No file there, or none readable: the defaults (hard rule 10). An empty file is a config.
        val read = beforeState { ConfigFile.read(app.getExternalFilesDir(null), ::logWarning) }
        val byUsage = beforeState { DrawerOrder.read(dir, ::logWarning) } ?: false
        lateinit var built: LauncherState
        built = LauncherState(
            io = app::submitIo,
            // Never dropped for a recreation — only once a reset replaced this state.
            main = { task -> app.onMain { if (state === built) task.run() } },
            text = read.text,
            useDefaults = read.status != ConfigStatus.READ,
            fileStatus = read.status,
            byUsage = byUsage,
            enumerateApps = { apps.enumerate() },
            defaultFavoriteCandidates = { apps.defaultFavoriteCandidates() },
            recentsFile = RecentsFile(
                read = { RecentsLog.read(dir, LauncherState.RECENTS_KEPT, ::logWarning) },
                write = { keys -> RecentsLog.write(dir, keys) },
            ),
            usageFile = UsageFile(
                read = { UsageLog.read(dir, ::logWarning) },
                write = { log, at -> UsageLog.write(dir, log, at) },
            ),
            writeOrder = { byUsage -> DrawerOrder.write(dir, byUsage) },
            process = app.process,
            now = System::currentTimeMillis,
            // No window between a recreation's two halves: the next one renders the state anew.
            onRefreshed = { listChanged -> app.instance?.refreshed(built, listChanged) },
        )
        return built
    }

    /**
     * [block], one of hard rule 5's accesses on main, let through the daily driver's StrictMode
     * ([VersoApp]), which then reports only what is not meant to be there.
     *
     * Why: writes too, not only reads: getFilesDir and getExternalFilesDir create their folder
     *   when it is missing.
     */
    private inline fun <T> beforeState(block: () -> T): T {
        val policy = StrictMode.allowThreadDiskWrites()
        try {
            return block()
        } finally {
            StrictMode.setThreadPolicy(policy)
        }
    }
}
