package com.github.reygnn.verso_launcher

import android.content.pm.LauncherApps
import android.os.Handler
import android.os.Looper
import android.os.StrictMode
import android.os.UserManager
import android.provider.Telephony

/**
 * The launcher's data, owned by the process (VersoApp.core): one [LauncherState], the launches
 * ([LaunchHistory]) and the app list, all built from the Application. An activity is a view of it — a recreation (language, font or
 * display size; a rotation does not recreate) shares the same state, so nothing loaded is lost or
 * read again, and none of the state's hand-backs needs a living instance to land.
 *
 * The process also hears every change to the apps ([AppWatch]) and decides when one becomes a
 * refresh ([AppChanges]); a window only says when it is made, in front and behind.
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

    private val launcherApps = app.getSystemService(LauncherApps::class.java)

    val apps = Apps(
        launcherApps = launcherApps,
        userManager = app.getSystemService(UserManager::class.java),
        pm = app.packageManager,
        ownPackage = app.packageName,
        badgedLabels = app.badgedLabels,
        // The badge is localized: part of its cache key, read fresh for each enumeration. The
        // application's resources follow a language change.
        locale = { app.resources.configuration.locales[0].toLanguageTag() },
        defaultSmsPackage = { Telephony.Sms.getDefaultSmsPackage(app) },
    )

    // The app's own folder, for its disposable files; looked up once, as the first state is built.
    private val dir = beforeState { app.filesDir }

    /** The launches, and their files until they are read. It outlives every [state]. */
    val history = LaunchHistory(
        io = app::submitIo,
        recentsFile = RecentsFile(
            read = { RecentsLog.read(dir, LaunchHistory.RECENTS_KEPT, ::logWarning) },
            write = { keys -> RecentsLog.write(dir, keys) },
        ),
        usageFile = UsageFile(
            read = { UsageLog.read(dir, ::logWarning) },
            write = { log, at -> UsageLog.write(dir, log, at) },
        ),
        now = System::currentTimeMillis,
    )

    /** home-cache.txt, read once and written when home changes; it outlives every window. */
    val homeCache = CachedFavorites(
        io = app::submitIo,
        main = app::onMain,
        read = { HomeCache.read(dir, ::logWarning) },
        write = { favorites -> HomeCache.write(dir, favorites) },
    )

    var state: LauncherState = newState()
        private set

    private val mainHandler = Handler(Looper.getMainLooper())
    private val settled = Runnable { changes.settled() }

    private val changes: AppChanges = AppChanges(
        schedule = { delayMs ->
            mainHandler.removeCallbacks(settled)
            mainHandler.postDelayed(settled, delayMs)
        },
        cancel = { mainHandler.removeCallbacks(settled) },
        // The state of the moment: after a "~restart" that is the new one.
        refresh = { state.refresh() },
    )

    init {
        AppWatch.start(
            context = app,
            launcherApps = launcherApps,
            onChange = changes::changed,
            // No window, no tap that waits for the profile.
            onProfileAvailable = { user -> app.instance?.profileAvailable(user) },
        )
    }

    /** A window was made: its first resume loads the list again ([AppChanges.markStale]). */
    fun windowCreated() = changes.markStale()

    /** The window is in front: a list that is stale or incomplete is loaded again. */
    fun windowResumed() = changes.resumed(incomplete = state.needsRefresh)

    /** The window is no longer in front: changes wait for the next resume. */
    fun windowPaused() = changes.paused()

    /**
     * "~restart": a fresh state, which reads the config file and order.txt again and loads the app
     * list anew. The recents and the usage stay as [history] holds them.
     */
    fun reset() {
        state = newState()
        changes.markStale()
    }

    private fun newState(): LauncherState {
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
            history = history,
            writeOrder = { byUsage -> DrawerOrder.write(dir, byUsage) },
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
