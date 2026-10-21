package com.github.reygnn.verso_launcher

import android.content.ComponentName
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
 * The apps on the device (IO side): enumeration of every profile, "~cleanup"'s "still there?",
 * and the default favorites. Runs on the IO thread, except [userOf].
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

    /** Never throws: the hand-back must always come, or "~cleanup" would stay "checking". */
    fun safeCleanupCheck(cfg: VersoConfig): CleanupPlan = try {
        cleanupCheck(cfg)
    } catch (e: RuntimeException) {
        Log.w("Verso", "cleanup check failed", e)
        CleanupPlan.Incomplete
    }

    fun cleanupCheck(cfg: VersoConfig): CleanupPlan {
        val found = enumerate()
        val listed: Set<Long>
        val ownSerial: Long?
        try {
            listed = launcherApps.profiles.mapTo(HashSet()) { userManager.getSerialNumberForUser(it) }
            ownSerial = userManager.getSerialNumberForUser(Process.myUserHandle()).takeIf { it >= 0 }
        } catch (e: RuntimeException) {
            Log.w("Verso", "cleanup: profiles unreadable", e)
            return CleanupPlan.Incomplete
        }
        return Cleanup.plan(cfg, found, ownSerial) { parts -> stillThere(parts, listed) }
    }

    /** Another profile only allows a package lookup. Unsure counts as there. */
    fun stillThere(key: KeyParts, listed: Set<Long>): Boolean = try {
        val serial = key.profile
        if (serial == null) {
            try {
                pm.getActivityInfo(
                    ComponentName(key.packageName, key.className),
                    PackageManager.ComponentInfoFlags.of(STILL_THERE_FLAGS.toLong()),
                )
                true
            } catch (e: PackageManager.NameNotFoundException) {
                // The package may be there with its code not loaded (unmounted, data kept).
                val info = pm.getPackageInfo(
                    key.packageName,
                    PackageManager.PackageInfoFlags.of((PackageManager.GET_ACTIVITIES or STILL_THERE_FLAGS).toLong()),
                )
                Cleanup.activityStillThere(key.className, info.activities?.map { it.name })
            }
        } else {
            val user = userManager.getUserForSerialNumber(serial)
            when {
                user == null -> false
                serial !in listed -> true         // exists, but not one Verso can list
                else -> {
                    launcherApps.getApplicationInfo(key.packageName, STILL_THERE_FLAGS, user)
                    true
                }
            }
        }
    } catch (e: PackageManager.NameNotFoundException) {
        false
    } catch (e: RuntimeException) {
        Log.w("Verso", "cleanup: cannot check ${key.packageName}", e)
        true
    }

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
            log = { msg, e -> Log.w("Verso", msg, e) },
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

    private companion object {
        const val STILL_THERE_FLAGS = PackageManager.MATCH_DISABLED_COMPONENTS or
            PackageManager.MATCH_DISABLED_UNTIL_USED_COMPONENTS or PackageManager.MATCH_UNINSTALLED_PACKAGES
    }
}

/** One launcher activity as the system reports it; reading it may throw (a binder call). */
internal class RawApp(val packageName: String, val className: String?, val label: String, val archived: Boolean)

/**
 * Every profile's launcher activities, system labels only. [P] is the profile handle (a
 * UserHandle in the app). Null when the profiles or the personal profile cannot be read, or the
 * personal one is missing or empty (never real), so the last list and the home cache stay (hard
 * rule 2); a skipped profile or app makes it incomplete (hard rule 14).
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
    var complete = true
    val emptyProfiles = HashSet<Long>()
    val all = try {
        profiles()
    } catch (e: RuntimeException) {
        log("app enumeration failed: profiles", e)
        return null
    }
    if (personal !in all) {
        log("app enumeration failed", IllegalStateException("no personal profile"))
        return null
    }
    val entries = mutableListOf<AppEntry>()
    for (user in all) {
        val isPersonal = user == personal
        var serial: Long? = null
        var isPaused = false
        val found = try {
            if (!isPersonal) {
                serial = serialOf(user).takeIf { it >= 0 } ?: run { complete = false; continue }
                isPaused = paused(user)
            }
            activities(user)
        } catch (e: RuntimeException) {
            if (isPersonal) {
                log("app enumeration failed", e)
                return null
            }
            log("skipping profile $serial", e)
            complete = false
            continue
        }
        if (found.isEmpty()) {
            if (isPersonal) {
                log("app enumeration failed", IllegalStateException("empty personal list"))
                return null
            }
            serial?.let { emptyProfiles += it }
        }
        for (read in found) {
            try {
                val app = read()
                if (app.className.isNullOrEmpty()) continue
                if (app.packageName == ownPackage) continue
                // Cleaned like a custom name: no bidi tricks to pass for another app.
                val raw = LauncherLogic.cleanName(app.label).ifEmpty { app.packageName }
                val systemLabel = if (serial == null) raw else badge(raw, serial, user)
                entries += AppEntry.of(
                    label = systemLabel,
                    packageName = app.packageName,
                    className = app.className,
                    systemLabel = systemLabel,
                    profile = serial,
                    status = AppStatus.of(profilePaused = isPaused, archived = app.archived),
                )
            } catch (e: RuntimeException) {
                log("skipping app", e)
                complete = false
            }
        }
    }
    return Enumeration(LauncherLogic.sortedForDrawer(entries), complete, emptyProfiles)
}
