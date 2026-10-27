package com.github.reygnn.verso_launcher

import android.content.pm.LauncherApps
import android.net.Uri
import android.os.UserManager
import android.provider.Telephony

/**
 * The launcher's data, owned by the process (VersoApp.core): one [LauncherState], the link to the
 * user's config file and the app list, all built from the Application. An activity is a view of
 * it — a recreation (language, font or display size; a rotation does not recreate) shares the same
 * state, so nothing loaded is lost or read again, and none of the state's hand-backs needs a living
 * instance to land.
 *
 * Its hand-backs run on the process's main thread and are never dropped while the state is the
 * current one; what touches a window goes to the live instance, if any (VersoApp.instance), which
 * renders the state anew whenever it is created. [reset] ("~restart") replaces the state: a fresh
 * one reads the app's own files again; the old one's late hand-backs are dropped.
 *
 * The config is read here, on main, before the state exists (hard rule 5): the copy of the linked
 * file (config-cache.txt), or verso.json of an earlier version while none is linked — a few KB in
 * the app's own files. The linked document itself is read off main, by Commands, and handed in
 * through [adoptLinked]. Main thread only.
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

    /** The user's config file ("~open", "~new"), or null: then verso.json, or the defaults. */
    var link: Uri? = ConfigLink.readLink(app.filesDir, ::logWarning)?.let(Uri::parse)
        private set

    /** The linked file's text the state was last given, to tell a changed file from the same one. */
    var linkedText: String? = null
        private set

    var state: LauncherState = newState()
        private set

    /** "~restart": a fresh state, which reads the app's own files again. */
    fun reset() {
        state = newState()
    }

    /** "~open" or "~new" took [uri]: it is the config from now on (its text follows, [adoptLinked]). */
    fun relink(uri: Uri) {
        link = uri
        val dir = app.filesDir
        app.submitIo { ConfigLink.writeLink(dir, uri.toString()) }
    }

    /** The linked file read anew: the state takes its config, the copy is kept for the next start. */
    fun adoptLinked(text: String, parsed: ConfigText) {
        linkedText = text
        state.adopt(parsed)
        val dir = app.filesDir
        app.submitIo { ConfigLink.writeCache(dir, text) }
    }

    private fun newState(): LauncherState {
        val dir = app.filesDir
        var initial = ConfigText()
        var useDefaults = false
        var legacyByUsage: Boolean? = null
        if (link != null) {
            // Until the document is read: its last text — the one in memory after "~restart" (the
            // copy on disk may still be on its way), else the copy — or nothing yet (never the
            // defaults: the file is the user's, even an empty one).
            if (linkedText == null) linkedText = ConfigLink.readCache(dir, ::logWarning)
            linkedText?.let { initial = ConfigText.read(it) }
        } else {
            linkedText = null
            when (val legacy = LegacyConfig.read(dir, ::logWarning)) {
                LegacyConfig.Read.Absent -> useDefaults = true
                is LegacyConfig.Read.Found -> {
                    initial = ConfigText.of(legacy.config)
                    legacyByUsage = legacy.config.byUsage
                }
                // Shown as empty, and said; the file stays as it is.
                LegacyConfig.Read.Unreadable -> app.results.report(app.getString(R.string.toast_config_unreadable))
            }
        }
        // "%" as an earlier version kept it in verso.json, carried into order.txt before a file is
        // linked and verso.json is no longer read.
        val byUsage = DrawerOrder.read(dir, ::logWarning) ?: legacyByUsage?.also { kept ->
            app.submitIo { DrawerOrder.write(dir, kept) }
        } ?: false
        lateinit var built: LauncherState
        built = LauncherState(
            io = app::submitIo,
            // Never dropped for a recreation — only once a reset replaced this state.
            main = { task -> app.onMain { if (state === built) task.run() } },
            initial = initial,
            useDefaults = useDefaults,
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
}
