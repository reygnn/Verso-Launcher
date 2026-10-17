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
    fun loadApps(): List<AppEntry>? = enumerate()?.apps

    fun enumerate(): Enumeration? {
        var complete = true
        val emptyProfiles = HashSet<Long>()
        val self = ownPackage
        val personal = Process.myUserHandle()
        // The badge is localized and the cache outlives a language change: part of the key.
        val locale = locale()
        // Hard rule 2. Binder calls throw plain RuntimeExceptions (DeadSystem, Security). If the
        // profiles or the personal profile cannot be read: null, not empty, so the last list stays.
        val profiles = try {
            launcherApps.profiles
        } catch (e: RuntimeException) {
            Log.w("Verso", "app enumeration failed: profiles", e)
            return null
        }
        val entries = mutableListOf<AppEntry>()
        for (user in profiles) {
            val isPersonal = user == personal
            var serial: Long? = null
            var paused = false
            val activities = try {
                // Binder calls too, so inside the try.
                if (!isPersonal) {
                    serial = userManager.getSerialNumberForUser(user).takeIf { it >= 0 }
                        ?: run { complete = false; continue }
                    paused = userManager.isQuietModeEnabled(user)
                }
                launcherApps.getActivityList(null, user)
            } catch (e: RuntimeException) {
                if (isPersonal) {
                    Log.w("Verso", "app enumeration failed", e)
                    return null
                }
                Log.w("Verso", "skipping profile $serial", e)
                complete = false
                continue
            }
            // An empty personal list is never real.
            if (activities.isEmpty()) {
                if (isPersonal) complete = false else serial?.let { emptyProfiles += it }
            }
            for (info in activities) {
                try {
                    val component = info.componentName
                    if (component.className.isNullOrEmpty()) continue
                    if (component.packageName == self) continue
                    // Cleaned like a custom name: no bidi tricks to pass for another app.
                    val raw = LauncherLogic.cleanName(info.label.toString()).ifEmpty { component.packageName }
                    // A binder call per app: cached across loads, IO thread only.
                    val systemLabel = if (serial == null) raw else {
                        badgedLabels.getOrPut(VersoApp.BadgeKey(serial, raw, locale)) { pm.getUserBadgedLabel(raw, user).toString() }
                    }
                    entries += AppEntry.of(
                        label = systemLabel,
                        packageName = component.packageName,
                        className = component.className,
                        systemLabel = systemLabel,
                        profile = serial,
                        status = AppStatus.of(profilePaused = paused, archived = info.applicationInfo.isArchived),
                    )
                } catch (e: RuntimeException) {
                    Log.w("Verso", "skipping app", e)
                    complete = false
                }
            }
        }
        return Enumeration(LauncherLogic.sortedForDrawer(entries), complete, emptyProfiles)
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
