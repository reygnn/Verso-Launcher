package com.github.reygnn.verso_launcher

import android.content.pm.LauncherApps
import android.os.UserManager
import android.provider.Telephony
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File

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
 * late hand-backs are dropped. Only "~restart" resets; no command has a hand-back through the
 * state (a "~backup" write and a "~restore" read hand back to the process), so none is dropped.
 *
 * verso.json is read here, on main, before the state exists (hard rule 5): a few KB in the app's
 * own files, read once per process and once per "~restart" — so the state is never "not loaded".
 */
internal class Core(private val app: VersoApp) {

    val store = ConfigStore(dir = app.filesDir, log = ::logWarning, syncDir = ::fsyncDir)

    /** The config the last "~restore" or "~undo" replaced, beside verso.json. */
    val undoSlot = UndoSlot(dir = app.filesDir, log = ::logWarning, syncDir = ::fsyncDir)

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
     * "~restart": a fresh state, which reads verso.json again. The old state's save still queued is
     * written first, or the new one would read the file before it and the next edit write over it.
     */
    fun reset() {
        state.flushSave()
        state = newState()
    }

    private fun newState(): LauncherState {
        val freshInstall = store.isFresh()
        val read = store.load()
        // hard rule 4: loaded as empty all the same, said before an edit would write over it.
        if (read == null) app.results.report(app.getString(R.string.toast_config_unreadable))
        lateinit var built: LauncherState
        built = LauncherState(
            io = app::submitIo,
            // Never dropped for a recreation — only once a reset replaced this state.
            main = { task -> app.onMain { if (state === built) task.run() } },
            initial = read ?: VersoConfig(),
            freshInstall = freshInstall,
            configFile = ConfigFile(
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
            now = System::currentTimeMillis,
            // A result: said now if a window is in front, else queued for the next start.
            onSaveFailed = { app.results.report(app.getString(R.string.toast_save_failed)) },
            // No window between a recreation's two halves: the next one renders the state anew.
            onRefreshed = { configReplaced, listChanged -> app.instance?.refreshed(built, configReplaced, listChanged) },
        )
        return built
    }
}

/**
 * The directory entry of a rename on disk too (verso.json, the undo slot). Best effort: the file
 * itself is already synced, and a failure here only means a power cut right after the save may
 * bring back the file before it. Os, not FileChannel: Android's refuses to open a directory.
 */
private fun fsyncDir(dir: File) {
    // hard rule 2: part of verso.json's write, which must not throw
    try {
        val fd = Os.open(dir.path, OsConstants.O_RDONLY, 0)
        try {
            Os.fsync(fd)
        } finally {
            Os.close(fd)
        }
    } catch (e: ErrnoException) {
        logWarning("fsync of ${dir.name} failed", e)
    }
}
