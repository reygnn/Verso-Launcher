package com.github.reygnn.verso_launcher

import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import android.provider.MediaStore
import android.util.Log

/**
 * The apps on the device (IO side): enumeration of every profile and the default favorites. Runs
 * on the IO thread, except [userOf].
 */
internal class Apps(
    private val launcherApps: LauncherApps,
    private val userManager: UserManager,
    private val pm: PackageManager,
    private val ownPackage: String,
    private val badgedLabels: MutableMap<VersoApp.BadgeKey, String>,
    private val locale: () -> String,
    private val defaultSmsPackage: () -> String?,
) {

    /** Null when the entry's profile no longer exists. */
    fun userOf(entry: AppEntry): UserHandle? {
        val serial = entry.profile ?: return Process.myUserHandle()
        return userManager.getUserForSerialNumber(serial)
    }

    /** System labels only; custom names are applied on the main thread (LauncherLogic.named). */
    fun enumerate(): Enumeration? {
        // The badge is localized and the cache outlives a language change: part of the key.
        val locale = locale()
        return enumerate(
            profiles = { launcherApps.profiles },
            personal = Process.myUserHandle(),
            serialOf = { userManager.getSerialNumberForUser(it) },
            paused = { userManager.isQuietModeEnabled(it) },
            activities = { user ->
                launcherApps.getActivityList(null, user).map { info ->
                    { RawApp(info.componentName.packageName, info.componentName.className, info.label.toString(), info.applicationInfo.isArchived) }
                }
            },
            // A binder call per app: cached across loads, IO thread only.
            badge = { raw, serial, user ->
                badgedLabels.getOrPut(VersoApp.BadgeKey(serial, raw, locale)) { pm.getUserBadgedLabel(raw, user).toString() }
            },
            ownPackage = ownPackage,
            log = ::logWarning,
        )
    }

    /**
     * Phone, messenger, email, browser, camera — resolved by intent, not a package list: the
     * default handler first, then the others. The handler's launcher activity is the key, not
     * the job's activity (a dial pad). A failing slot is empty.
     */
    fun defaultFavoriteCandidates(): List<List<String>> {
        fun launcherKey(pkg: String): String? =
            pm.getLaunchIntentForPackage(pkg)?.component?.let { AppEntry.keyOf(it.packageName, it.className, null) }
        // [preferred] is a lambda so it runs inside the try: the SMS role lookup can throw
        // (no telephony), and evaluated as an argument it would escape onto the IO thread.
        @Suppress("TooGenericExceptionCaught")
        fun handlers(intent: Intent, preferred: () -> String? = { null }): List<String> = try {
            // With no default set this is the ResolverActivity ("android"); launcherKey drops it.
            val default = preferred()
                ?: pm.resolveActivity(intent, PackageManager.ResolveInfoFlags.of(
                    PackageManager.MATCH_DEFAULT_ONLY.toLong()))?.activityInfo?.packageName
            val all = pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
                .map { it.activityInfo.packageName }
            (listOfNotNull(default) + all).distinct().mapNotNull(::launcherKey)
        } catch (e: RuntimeException) {
            Log.w("Verso", "resolving default favorite failed: $intent", e)
            emptyList()
        }
        return listOf(
            handlers(Intent(Intent.ACTION_DIAL)),
            handlers(
                Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")),
                preferred = { defaultSmsPackage() },
            ),
            handlers(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))),
            handlers(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://"))
                    .addCategory(Intent.CATEGORY_BROWSABLE),
            ),
            handlers(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)),
        )
    }
}

/** One launcher activity as the system reports it; reading it may throw (a binder call). */
internal class RawApp(val packageName: String, val className: String?, val label: String, val archived: Boolean)

/**
 * Every profile's launcher activities, system labels only. [P] is the profile handle (a
 * UserHandle in the app). Null when the profiles or the personal profile cannot be read, or the
 * personal one is missing, empty or has not one app that could be read (never real), so the last
 * list and the home cache stay (hard rule 2); a skipped profile or app makes it incomplete, and the
 * next resume tries again.
 */
internal fun <P> enumerate(
    profiles: () -> List<P>,
    personal: P,
    serialOf: (P) -> Long,
    paused: (P) -> Boolean,
    activities: (P) -> List<() -> RawApp>,
    badge: (raw: String, serial: Long, profile: P) -> String,
    ownPackage: String,
    log: (String, Exception) -> Unit,
): Enumeration? {
    // hard rule 2: unreadable profiles = no list, the last one stays
    val all = @Suppress("TooGenericExceptionCaught") try {
        profiles()
    } catch (e: RuntimeException) {
        log("app enumeration failed: profiles", e)
        null
    } ?: return null
    if (personal !in all) {
        log("app enumeration failed", IllegalStateException("no personal profile"))
        return null
    }
    val reader = ProfileReader(serialOf, paused, activities, badge, ownPackage, log)
    val entries = mutableListOf<AppEntry>()
    for (user in all) {
        val apps = if (user == personal) reader.personal(user) else reader.other(user)
        entries += apps ?: return null
    }
    // Every personal read threw: an incomplete list without them would replace the last one.
    return if (entries.none { it.profile == null }) {
        log("app enumeration failed", IllegalStateException("no personal app readable"))
        null
    } else {
        Enumeration(LauncherLogic.sortedForDrawer(entries), reader.complete)
    }
}

/**
 * [enumerate]'s reads of one profile and its apps, with what the system answers as fields.
 *
 * Thread: [VersoApp.io], like [enumerate].
 */
private class ProfileReader<P>(
    private val serialOf: (P) -> Long,
    private val paused: (P) -> Boolean,
    private val activities: (P) -> List<() -> RawApp>,
    private val badge: (raw: String, serial: Long, profile: P) -> String,
    private val ownPackage: String,
    private val log: (String, Exception) -> Unit,
) {
    /** False once a profile or an app was skipped: the list is incomplete, and the next resume tries again. */
    var complete = true
        private set

    /** The personal profile's apps, or null: one that cannot be read, or is empty, is never real. */
    fun personal(user: P): List<AppEntry>? {
        // hard rule 2: an unreadable personal profile = no list, the last one stays
        val found = @Suppress("TooGenericExceptionCaught") try {
            activities(user)
        } catch (e: RuntimeException) {
            log("app enumeration failed", e)
            return null
        }
        return if (found.isEmpty()) {
            log("app enumeration failed", IllegalStateException("empty personal list"))
            null
        } else {
            entries(found, user, serial = null, isPaused = false)
        }
    }

    /**
     * Another profile's apps (work, clone), badged. One without a serial, or that cannot be read, is
     * skipped; an empty one is complete (a profile being set up or torn down answers empty).
     */
    fun other(user: P): List<AppEntry> {
        var serial: Long? = null
        // an unreadable profile makes the list incomplete
        val read = @Suppress("TooGenericExceptionCaught") try {
            serial = serialOf(user).takeIf { it >= 0 }
            serial?.let { paused(user) to activities(user) }
        } catch (e: RuntimeException) {
            log("skipping profile $serial", e)
            null
        }
        val known = serial
        if (read == null || known == null) {
            complete = false
            return emptyList()
        }
        return entries(read.second, user, known, isPaused = read.first)
    }

    private fun entries(found: List<() -> RawApp>, user: P, serial: Long?, isPaused: Boolean): List<AppEntry> =
        found.mapNotNull { read -> entry(read, user, serial, isPaused) }

    /** One app, or null: Verso itself, a row without a class, or one that cannot be read. */
    private fun entry(read: () -> RawApp, user: P, serial: Long?, isPaused: Boolean): AppEntry? =
        // an unreadable app makes the list incomplete
        @Suppress("TooGenericExceptionCaught") try {
            val app = read()
            val className = app.className
            if (className.isNullOrEmpty() || app.packageName == ownPackage) {
                null
            } else {
                // Cleaned like a custom name: no bidi tricks to pass for another app.
                val raw = LauncherLogic.cleanName(app.label).ifEmpty { app.packageName }
                val systemLabel = if (serial == null) raw else badge(raw, serial, user)
                AppEntry(
                    label = systemLabel,
                    packageName = app.packageName,
                    className = className,
                    systemLabel = systemLabel,
                    profile = serial,
                    status = AppStatus.of(profilePaused = isPaused, archived = app.archived),
                )
            }
        } catch (e: RuntimeException) {
            log("skipping app", e)
            complete = false
            null
        }
}
