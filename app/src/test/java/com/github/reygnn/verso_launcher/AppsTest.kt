package com.github.reygnn.verso_launcher

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.LauncherApps
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import android.provider.MediaStore
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.GraphicsMode

/**
 * [Apps.defaultFavoriteCandidates] against Robolectric's package manager: the slots, in the order
 * phone, messenger, email, browser, camera, each its handlers' launcher activities.
 */
@RunWith(RobolectricTestRunner::class)
class AppsTest {

    private val app: Application = RuntimeEnvironment.getApplication()
    private val packages = shadowOf(app.packageManager)

    private fun apps(defaultSms: () -> String? = { null }) = Apps(
        launcherApps = app.getSystemService(LauncherApps::class.java),
        userManager = app.getSystemService(UserManager::class.java),
        pm = app.packageManager,
        ownPackage = app.packageName,
        badgedLabels = HashMap(),
        locale = { "en" },
        defaultSmsPackage = defaultSms,
    )

    /** An app with a launcher activity and, if [handles] is given, a second activity for that job. */
    private fun install(pkg: String, handles: IntentFilter? = null, launchable: Boolean = true): String {
        val main = ComponentName(pkg, "$pkg.Main")
        if (launchable) {
            packages.addActivityIfNotPresent(main)
            packages.addIntentFilterForActivity(main, IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) })
        }
        if (handles != null) {
            val job = ComponentName(pkg, "$pkg.Job")
            packages.addActivityIfNotPresent(job)
            packages.addIntentFilterForActivity(job, handles.apply { addCategory(Intent.CATEGORY_DEFAULT) })
        }
        return AppEntry.keyOf(main.packageName, main.className, null)
    }

    private fun filter(action: String, scheme: String? = null) = IntentFilter(action).apply { scheme?.let(::addDataScheme) }

    @Test fun `a handler's launcher activity is the key, not the activity that does the job`() {
        val dialer = install("com.dialer", handles = filter(Intent.ACTION_DIAL))
        assertEquals(listOf(dialer), apps().defaultFavoriteCandidates()[PHONE])
    }

    @Test fun `a handler without a launcher activity is no candidate`() {
        install("com.headless", handles = filter(Intent.ACTION_DIAL), launchable = false)
        assertEquals(emptyList<String>(), apps().defaultFavoriteCandidates()[PHONE])
    }

    /** The default is last both by name and by install order, so neither order can put it first. */
    @Test fun `the default SMS app comes first among the messengers`() {
        val other = install("com.a.messenger", handles = filter(Intent.ACTION_SENDTO, "smsto"))
        val chosen = install("com.z.sms", handles = filter(Intent.ACTION_SENDTO, "smsto"))
        assertEquals(listOf(chosen, other), apps { "com.z.sms" }.defaultFavoriteCandidates()[MESSENGER])
    }

    /** Without telephony the SMS role lookup throws: that slot is empty, the others are still filled. */
    @Test fun `a lookup that throws empties its slot alone`() {
        install("com.messenger", handles = filter(Intent.ACTION_SENDTO, "smsto"))
        val camera = install("com.camera", handles = filter(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))
        val slots = apps { throw UnsupportedOperationException("no telephony") }.defaultFavoriteCandidates()
        assertEquals(emptyList<String>(), slots[MESSENGER])
        assertEquals(listOf(camera), slots[CAMERA])
    }

    private companion object {
        const val PHONE = 0
        const val MESSENGER = 1
        const val CAMERA = 4
    }

    // ---- the badge's cache ----------------------------------------------------------------

    private val workUserId = 10

    // A user's id is its uid over 100000: the public way to the handle of another user.
    private val work = UserHandle.getUserHandleForUid(workUserId * 100_000)
    private val workSerial = 42L

    /** A work profile that lists [label]; the personal one is never empty, or there is no list at all. */
    private fun workProfileWith(label: String) {
        val users = shadowOf(app.getSystemService(UserManager::class.java))
        // UserInfo.FLAG_MANAGED_PROFILE; the class is hidden.
        users.addProfile(0, workUserId, "work", 0x20)
        users.setSerialNumberForUser(work, workSerial)
        val launcherApps = shadowOf(app.getSystemService(LauncherApps::class.java))
        val entry = AppEntry(label, "com.work", "com.work.Main", label)
        launcherApps.addActivity(work, launcherActivityOf(app, entry, work))
        val own = Process.myUserHandle()
        launcherApps.addActivity(own, launcherActivityOf(app, AppEntry("Own", "com.own", "com.own.Main", "Own"), own))
    }

    private fun apps(cache: MutableMap<VersoApp.BadgeKey, String>, locale: () -> String) = Apps(
        launcherApps = app.getSystemService(LauncherApps::class.java),
        userManager = app.getSystemService(UserManager::class.java),
        pm = app.packageManager,
        ownPackage = app.packageName,
        badgedLabels = cache,
        locale = locale,
        defaultSmsPackage = { null },
    )

    private fun workLabels(apps: Apps) =
        checkNotNull(apps.enumerate()).apps.filter { it.profile == workSerial }.map { it.label }

    /** The badge is translated and the cache outlives a language change: "Gmail (Work)" must not stay. */
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test fun `a language change takes the badge of the new language, not the cached one`() {
        workProfileWith("Gmail")
        val cache = hashMapOf(
            VersoApp.BadgeKey(workSerial, "Gmail", "en") to "Gmail (Work)",
            VersoApp.BadgeKey(workSerial, "Gmail", "de") to "Gmail (Arbeit)",
        )
        var language = "en"
        val apps = apps(cache) { language }

        assertEquals(listOf("Gmail (Work)"), workLabels(apps))
        language = "de"
        assertEquals(listOf("Gmail (Arbeit)"), workLabels(apps))
        language = "en"
        assertEquals(listOf("Gmail (Work)"), workLabels(apps))
    }

    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test fun `a badge is asked for once per language and kept under it`() {
        workProfileWith("Gmail")
        val cache = HashMap<VersoApp.BadgeKey, String>()
        var language = "en"
        val apps = apps(cache) { language }

        workLabels(apps)
        workLabels(apps)
        assertEquals(setOf(VersoApp.BadgeKey(workSerial, "Gmail", "en")), cache.keys)
        language = "de"
        workLabels(apps)
        assertEquals(
            setOf(VersoApp.BadgeKey(workSerial, "Gmail", "en"), VersoApp.BadgeKey(workSerial, "Gmail", "de")),
            cache.keys,
        )
    }
}
