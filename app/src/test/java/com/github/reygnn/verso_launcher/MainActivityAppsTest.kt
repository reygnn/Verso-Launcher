package com.github.reygnn.verso_launcher

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.os.Looper
import android.os.Process
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.GraphicsMode
import org.robolectric.Shadows.shadowOf
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import java.io.File
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The window with apps listed: Robolectric's LauncherApps lists what a test adds
 * (`ShadowLauncherApps.addActivity`), so the first list draws home, writes the home cache and
 * answers package changes the way MainActivity collects them. MainActivityFlowsTest has the flows
 * that need no app at all. The process's IO thread is real, as there.
 *
 * Why native graphics: LauncherActivityInfo.getLabel keeps a label only if a Paint finds glyphs
 *   for it, and without native graphics Robolectric finds none, so every app would be listed
 *   under its package.
 */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
class MainActivityAppsTest {

    private val app = RuntimeEnvironment.getApplication() as VersoApp
    private val launcherApps = shadowOf(app.getSystemService(LauncherApps::class.java))
    private val controller = Robolectric.buildActivity(MainActivity::class.java)
    private val activity: MainActivity get() = controller.get()
    private val hold = CountDownLatch(1)

    private val zulu = AppEntry("Zulu", "com.zulu", "com.zulu.Main", "Zulu")
    private val yankee = AppEntry("Yankee", "com.yankee", "com.yankee.Main", "Yankee")

    @After fun release() {
        hold.countDown()
        settle()
        if (!activity.isDestroyed) controller.pause().stop().destroy()
    }

    private fun settle() = repeat(4) {
        app.io.submit {}.get(5, TimeUnit.SECONDS)
        shadowOf(Looper.getMainLooper()).idle()
    }

    /**
     * [entry] as the system lists it in the personal profile. LauncherActivityInfo's constructor is
     * hidden, so it is called by reflection: the one place this test leaves public API.
     */
    private fun install(entry: AppEntry) {
        val info = ActivityInfo().apply {
            packageName = entry.packageName
            name = entry.className
            nonLocalizedLabel = entry.label
            applicationInfo = ApplicationInfo().apply { packageName = entry.packageName }
        }
        val internalClass = Class.forName("android.content.pm.LauncherActivityInfoInternal")
        val incremental = Class.forName("android.content.pm.IncrementalStatesInfo")
        val internal = ReflectionHelpers.callConstructor(
            internalClass,
            ClassParameter.from(ActivityInfo::class.java, info),
            ClassParameter(incremental, ReflectionHelpers.callConstructor(incremental)),
            ClassParameter.from(android.os.UserHandle::class.java, Process.myUserHandle()),
            ClassParameter.from(Boolean::class.javaPrimitiveType, false),
        )
        val activityInfo = ReflectionHelpers.callConstructor(
            LauncherActivityInfo::class.java,
            ClassParameter.from(Context::class.java, app),
            ClassParameter(internalClass, internal),
        )
        launcherApps.addActivity(Process.myUserHandle(), activityInfo)
    }

    private fun configFile(text: String) =
        File(checkNotNull(app.getExternalFilesDir(null)), ConfigFile.NAME).writeText(text)

    private val homeCache get() = File(app.filesDir, HomeCache.FILE)

    private fun started(): MainActivity = controller.setup().get().also { settle() }

    /** The favorites as home shows them. */
    private fun home(): List<String> =
        activity.window.decorView.collect<ScrollView>().single().collect<TextView>().map { it.text.toString() }

    /** The drawer's app rows, in list order. */
    private fun drawer(): List<String> {
        val list = activity.window.decorView.collect<ListView>().single()
        return (0 until list.adapter.count).mapNotNull { (list.adapter.getItem(it) as? AppRow<*>)?.entry as? AppEntry }
            .map { it.label }
    }

    // ---- the first list ----------------------------------------------------------------

    @Test fun `the first list draws home and keeps it in the home cache`() {
        install(zulu)
        install(yankee)
        configFile("fav Zulu")                  // by label: resolved only once the list is in
        started()
        assertEquals(listOf("Zulu"), home())
        assertEquals(listOf("Yankee", "Zulu"), drawer())
        assertEquals("${zulu.key}\tZulu\n", homeCache.readText())
    }

    /** The cache is written only when the favorites change, not at every refresh. */
    @Test fun `a refresh with the same favorites writes no cache`() {
        install(zulu)
        configFile("fav Zulu")
        started()
        assertTrue(homeCache.delete())
        install(yankee)                         // the list changes, the favorites do not
        launcherApps.notifyPackageAdded(yankee.packageName)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(CHANGE_SETTLE_MS))
        settle()
        assertEquals(listOf("Yankee", "Zulu"), drawer())
        assertFalse(homeCache.exists())
    }

    /** A cold start draws the cached favorites while the first enumeration still runs. */
    @Test fun `the home cache shows until the first list is in`() {
        install(zulu)
        configFile("fav Zulu")
        homeCache.writeText("${yankee.key}\tYankee\n")
        val cacheRead = CountDownLatch(1)
        controller.create()                     // queues the cache read, then the wallpaper's question
        app.io.execute { cacheRead.countDown() }
        app.io.execute { hold.await(30, TimeUnit.SECONDS) }
        controller.start().resume()             // the first refresh waits behind the hold
        assertTrue(cacheRead.await(5, TimeUnit.SECONDS))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("the cache, before any list", listOf("Yankee"), home())
        assertFalse(app.core.state.appsLoaded)
        hold.countDown()
        settle()
        assertEquals(listOf("Zulu"), home())
        assertEquals("${zulu.key}\tZulu\n", homeCache.readText())
    }

    // ---- package changes ---------------------------------------------------------------

    /** In front, a burst of changes is one refresh, CHANGE_SETTLE_MS after the last of them. */
    @Test fun `changes in front are collected until they settle`() {
        install(zulu)
        started()
        install(yankee)
        launcherApps.notifyPackageAdded(yankee.packageName)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(CHANGE_SETTLE_MS - 200))
        launcherApps.notifyPackageAdded(yankee.packageName)   // a second change starts the wait anew
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(CHANGE_SETTLE_MS - 100))
        settle()
        assertEquals("still settling", listOf("Zulu"), drawer())
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
        settle()
        assertEquals(listOf("Yankee", "Zulu"), drawer())
    }

    @Test fun `a change behind another app waits for the next resume`() {
        install(zulu)
        started()
        controller.pause().stop()
        install(yankee)
        launcherApps.notifyPackageAdded(yankee.packageName)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(CHANGE_SETTLE_MS * 2))
        settle()
        assertEquals("no refresh behind another app", listOf("Zulu"), drawer())
        controller.restart().resume()
        settle()
        assertEquals(listOf("Yankee", "Zulu"), drawer())
    }

    /**
     * A pause drops the refresh still settling, and the resume collects it at once. Why so short:
     *   left waiting, it would run behind the window and only mark the list dirty, which the
     *   resume collects all the same; a resume before it runs is what tells the two apart.
     */
    @Test fun `a change still settling at a pause is picked up by the resume`() {
        install(zulu)
        started()
        install(yankee)
        launcherApps.notifyPackageAdded(yankee.packageName)
        shadowOf(Looper.getMainLooper()).idle() // the callback is delivered on main; the wait begins
        controller.pause()                      // a dialog on top, gone again before the wait ends
        controller.resume()
        settle()                                // no time passes: only the resume refreshes
        assertEquals(listOf("Yankee", "Zulu"), drawer())
    }

    private companion object {
        /** MainActivity's CHANGE_SETTLE_MS, which is private there. */
        const val CHANGE_SETTLE_MS = 500L
    }
}
