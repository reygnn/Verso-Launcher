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
 * The apps on the device, as the launcher sees them — the IO side, no views: the enumeration of
 * every profile (LauncherState's enumerateApps), the "still there?" check of "~cleanup", the
 * profile of an entry, and the default favorites of a fresh install. Runs on the IO thread,
 * except [userOf]. Everything it needs comes in through the constructor; no Activity reference.
 *
 * @param ownPackage        the launcher's own package — it does not list itself
 * @param badgedLabels      the process-wide cache of work-profile badge labels (VersoApp)
 * @param locale            the current language tag (part of the badge cache key)
 * @param defaultSmsPackage the user's default SMS app, for the messenger favorite
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

    /** [cleanupCheck] that never throws: anything unexpected is an incomplete check — the
     *  hand-back must always come (or "~cleanup" would stay "checking" for good). */
    fun safeCleanupCheck(cfg: VersoConfig): CleanupPlan = try {
        cleanupCheck(cfg)
    } catch (e: RuntimeException) {
        Log.w("Verso", "cleanup check failed", e)
        CleanupPlan.Incomplete
    }

    /** The IO half of a cleanup check: enumerate afresh, then ask [Cleanup.plan]. */
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

    /**
     * Whether the app behind a key is still on the device although not listed: disabled, its
     * launcher icon (an activity alias) switched off, uninstalled with its data kept, on storage
     * that is not mounted, or in a profile that exists but is not among those [listed]. In the
     * personal profile the exact activity is looked up, so one renamed by an update counts as
     * gone; another profile only allows a package lookup. Unsure counts as there — a cleanup
     * keeps what it cannot rule out.
     */
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
                // No such activity loaded — but the package may still be there with its code
                // not loaded (unmounted storage, data kept). A missing package throws again.
                val info = pm.getPackageInfo(
                    key.packageName,
                    PackageManager.PackageInfoFlags.of((PackageManager.GET_ACTIVITIES or STILL_THERE_FLAGS).toLong()),
                )
                Cleanup.activityStillThere(key.className, info.activities?.map { it.name })
            }
        } else {
            val user = userManager.getUserForSerialNumber(serial)
            when {
                user == null -> false             // the profile is gone, and its apps with it
                serial !in listed -> true         // there, but not one Verso can list
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

    /**
     * The profile an entry lives in: the personal one for [AppEntry.profile] == null, else
     * the profile with that serial — or null when that profile no longer exists (a work
     * profile removed since the list was loaded).
     */
    fun userOf(entry: AppEntry): UserHandle? {
        val serial = entry.profile ?: return Process.myUserHandle()
        return userManager.getUserForSerialNumber(serial)
    }

    /**
     * LauncherState's enumerateApps, on the IO thread: every launchable app under its own
     * (system) label. No config: the custom names are applied by LauncherState on the main
     * thread, from the live config (LauncherLogic.named).
     *
     * Through LauncherApps, the API Android provides for launchers: every profile the launcher
     * may show — the personal one plus a work profile or cloned apps — not just the current
     * user's PackageManager view. Apps of another profile are keyed with its serial
     * ([AppEntry.keyOf]) and labelled with the system's badge ("Gmail (Work)"), so the work
     * copy is neither the same row nor an identical-looking one.
     */
    fun loadApps(): List<AppEntry>? = enumerate()?.apps

    /**
     * [loadApps]'s body, also telling whether it saw EVERYTHING: a profile or an app skipped
     * after an error makes it incomplete. "~cleanup" needs that — an app it did not see would
     * look uninstalled.
     */
    fun enumerate(): Enumeration? {
        var complete = true
        val emptyProfiles = HashSet<Long>()
        val self = ownPackage
        val personal = Process.myUserHandle()
        // The badge is localized ("(Work)" / "(Arbeit)"): part of its cache key, since the
        // process — and the cache — outlives the activity recreated by a language change.
        val locale = locale()
        // This runs on a background thread, and a HOME app that throws on resume loops
        // forever — so nothing here may let an exception escape. LauncherApps and the label
        // lookups surface plain RuntimeExceptions (a DeadSystemRuntimeException if
        // system_server restarts, a SecurityException for a profile that turns inaccessible
        // mid-scan). If the profile list or the PERSONAL profile cannot be read, return null
        // (NOT an empty list) so the caller keeps the last-known-good list rather than
        // blanking the launcher; another profile failing only drops that profile. A query
        // that SUCCEEDS returns its result even when empty.
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
                // The serial and quiet-mode lookups are binder calls too: inside the try, so a
                // failure drops this profile like any other instead of escaping onto the IO thread.
                if (!isPersonal) {
                    serial = userManager.getSerialNumberForUser(user).takeIf { it >= 0 }
                        ?: run { complete = false; continue }
                    // A work profile that is turned off: its apps stay listed, dimmed (AppStatus).
                    paused = userManager.isQuietModeEnabled(user)
                }
                launcherApps.getActivityList(null, user)
            } catch (e: RuntimeException) {
                if (isPersonal) {
                    Log.w("Verso", "app enumeration failed", e)
                    return null  // failure sentinel: keep the current list, don't wipe it
                }
                Log.w("Verso", "skipping profile $serial", e)
                complete = false
                continue
            }
            // An empty list, no error: a profile with no apps — or one being set up or torn down,
            // or one the system will not show right now. "~cleanup" cannot tell which, so it
            // keeps that profile's entries (Enumeration.emptyProfiles). An empty PERSONAL list is
            // never real: nothing is seen.
            if (activities.isEmpty()) {
                if (isPersonal) complete = false else serial?.let { emptyProfiles += it }
            }
            for (info in activities) {
                try {
                    val component = info.componentName
                    // A component without a class name cannot be launched (and would key as
                    // "pkg/null"): skip it.
                    if (component.className.isNullOrEmpty()) continue
                    if (component.packageName == self) continue  // don't list ourselves
                    // The app's own label — the only one an enumeration knows; custom names are
                    // applied over it later (LauncherLogic.named). In another profile it
                    // carries the system's badge, so the work copy reads apart.
                    // Cleaned like a custom name: no bidi overrides or invisible marks another
                    // app could use to pass for a different one, and a sane length.
                    val raw = LauncherLogic.cleanName(info.label.toString()).ifEmpty { component.packageName }
                    // The badge is a binder call per app (DevicePolicyManager strings for a work
                    // profile): cached per (profile, label) across loads — IO thread only.
                    val systemLabel = if (serial == null) raw else {
                        badgedLabels.getOrPut(VersoApp.BadgeKey(serial, raw, locale)) { pm.getUserBadgedLabel(raw, user).toString() }
                    }
                    entries += AppEntry.of(
                        label = systemLabel,
                        packageName = component.packageName,
                        className = component.className,
                        systemLabel = systemLabel,
                        profile = serial,
                        // Archived (Android 15+): code removed, data kept; LauncherApps still
                        // lists it, and a tap lets the system restore it.
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
     * LauncherState's defaultFavoriteCandidates: phone, messenger (the default SMS app), email,
     * browser, camera — the favorites a fresh install starts with. IO thread, fresh install only.
     *
     * Each slot is resolved by what the app DOES, not by a package list, so it works on any
     * vendor: the user's default handler for the job comes first, then every other handler in
     * the system's own order (covers "no default chosen yet"). A handler package becomes its
     * launcher activity key via getLaunchIntentForPackage — the job's activity (a dial pad, a
     * compose screen) is usually not the one the drawer lists. Never throws: a failing slot is
     * just empty, and LauncherLogic.pickDefaultFavorites skips it.
     */
    fun defaultFavoriteCandidates(): List<List<String>> {
        // The personal-profile key, built where every key is built (AppEntry.keyOf).
        fun launcherKey(pkg: String): String? =
            pm.getLaunchIntentForPackage(pkg)?.component?.let { AppEntry.keyOf(it.packageName, it.className, null) }
        // [preferred] is a lambda, not a value, so it runs INSIDE the try: evaluated as an
        // argument it would run before it, and a throwing lookup (the SMS role on a device
        // without telephony, a vendor-modified one) would escape onto the IO thread — an
        // uncaught exception there takes the process down, on every cold start while fresh.
        fun handlers(intent: Intent, preferred: () -> String? = { null }): List<String> = try {
            // resolveActivity answers the ResolverActivity ("android") when no default is set —
            // that is no app, so it is dropped by the launcherKey lookup below like any other
            // package without a launcher entry.
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
        /** What [stillThere] finds an app by: also disabled and uninstalled-with-data ones. */
        const val STILL_THERE_FLAGS = PackageManager.MATCH_DISABLED_COMPONENTS or
            PackageManager.MATCH_DISABLED_UNTIL_USED_COMPONENTS or PackageManager.MATCH_UNINSTALLED_PACKAGES
    }
}
