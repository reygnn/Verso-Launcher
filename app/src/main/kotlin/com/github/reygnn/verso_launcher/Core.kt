package com.github.reygnn.verso_launcher

import android.content.pm.LauncherApps
import android.os.UserManager
import android.provider.Telephony

/**
 * The launcher's data, owned by the process (VersoApp.core): one [LauncherState] and the stores
 * and app list it works through, all built from the Application. An activity is a view of it —
 * a recreation (language, font or display size; a rotation does not recreate) shares the same
 * state, so nothing loaded is lost
 * or read again, and none of the state's hand-backs needs a living instance to land.
 *
 * Its hand-backs run on the process's main thread and are never dropped while the state is the
 * current one; what touches a window goes to the live instance, if any (VersoApp.instance), which
 * renders the state anew whenever it is created. [reset] ("~restart") replaces the state: a fresh
 * one reads verso.json again (and recents.txt / usage.txt if they were never read); the old one's
 * late hand-backs are dropped. Only "~restart" resets, and it runs alone (the gate), so no run's
 * hand-back can be among the dropped ones.
 */
internal class Core(private val app: VersoApp) {

    val store = ConfigStore(dir = app.filesDir, log = ::logWarning)

    /** The application's resolver: a MediaStore call that hangs holds no activity. */
    val backupStore = BackupStore(
        resolver = app.contentResolver,
        subDir = BackupFiles.DIR,
        log = ::logWarning,
        owner = app.packageName,
    )

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

    /** "~restart": a fresh state, which reads the files again on its first refresh. */
    fun reset() {
        state = newState()
    }

    private fun newState(): LauncherState {
        lateinit var built: LauncherState
        built = LauncherState(
            io = app::submitIo,
            // Never dropped for a recreation — only once a reset replaced this state.
            main = { task -> app.onMain { if (state === built) task.run() } },
            configFile = ConfigFile(
                load = { store.load() },
                isFresh = { store.isFresh() },
                serialize = ConfigJson::serialize,
                write = { payload -> store.write(payload) },
            ),
            enumerateApps = { apps.enumerate() },
            defaultFavoriteCandidates = { apps.defaultFavoriteCandidates() },
            recentsFile = RecentsFile(
                read = { RecentsLog.read(app.filesDir, LauncherState.RECENTS_KEPT, ::logWarning) },
                write = { keys -> RecentsLog.write(app.filesDir, keys) },
            ),
            usageFile = UsageFile(
                read = { UsageLog.read(app.filesDir, ::logWarning) },
                write = { log, at -> UsageLog.write(app.filesDir, log, at) },
            ),
            process = app.process,
            idle = { app.gate.idle() },
            now = System::currentTimeMillis,
            // A result: said now if a window is in front, else queued for the next start.
            onSaveFailed = { app.results.report(app.getString(R.string.toast_save_failed)) },
            // No window between a recreation's two halves: the next one renders the state anew.
            // A pick read before the first load waited for it: to be asked about, or to be adopted.
            onRefreshed = { configReplaced, listChanged ->
                app.instance?.refreshed(built, configReplaced, listChanged)
                app.commands.offerPendingPick()
                app.commands.adoptPendingPick()
            },
        )
        return built
    }
}
