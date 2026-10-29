package com.github.reygnn.verso_launcher

import android.content.ComponentName
import android.os.UserHandle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** Hard rule 2: whatever the system answers, a launch or "App info" is a start or a word, never a crash. */
@RunWith(RobolectricTestRunner::class)
class AppLauncherTest {

    private val context = RuntimeEnvironment.getApplication()
    private val zulu = AppEntry("Zulu", "com.zulu", "com.zulu.Main", "Zulu")
    // UserHandle.of is a system API; a uid of profile 10 gives the same handle.
    private val work = profile(10)

    private fun profile(id: Int) = UserHandle.getUserHandleForUid(id * PER_USER_RANGE)

    /** The system as a test wants it: each call recorded, each answer or refusal set. */
    private class FakeSystem : AppLauncher.ProfileApps {
        var paused = false
        var enabled = true
        var unpauseRefusal: RuntimeException? = null
        var startRefusal: RuntimeException? = null
        val calls = mutableListOf<String>()

        override fun isPaused(user: UserHandle) = paused
        override fun unpause(user: UserHandle) {
            calls += "unpause"
            unpauseRefusal?.let { throw it }
        }
        override fun isEnabled(component: ComponentName, user: UserHandle) = enabled
        override fun start(component: ComponentName, user: UserHandle) {
            startRefusal?.let { throw it }
            calls += "start ${component.className}"
        }
        override fun startDetails(component: ComponentName, user: UserHandle) {
            startRefusal?.let { throw it }
            calls += "details ${component.className}"
        }
    }

    private val system = FakeSystem()
    private var user: UserHandle? = work
    private var clock = 0L
    private val said = mutableListOf<Pair<String, Boolean>>()
    private val launched = mutableListOf<AppEntry>()

    private val launcher = AppLauncher(
        context = context,
        system = system,
        userOf = { user },
        say = { text, long -> said += text to long },
        launched = { launched += it },
        now = { clock },
    )

    private fun word(id: Int) = context.getString(id, zulu.label)

    @Test fun `an enabled app starts in its profile and counts as launched`() {
        launcher.launch(zulu)
        assertEquals(listOf("start com.zulu.Main"), system.calls)
        assertEquals(listOf(zulu), launched)
        assertEquals(emptyList<Pair<String, Boolean>>(), said)
    }

    @Test fun `a gone profile or a gone component is not found, nothing starts`() {
        user = null
        launcher.launch(zulu)
        user = work
        system.enabled = false
        launcher.launch(zulu)
        assertEquals(emptyList<String>(), system.calls)
        assertEquals(emptyList<AppEntry>(), launched)
        assertEquals(listOf(word(R.string.toast_not_found) to false, word(R.string.toast_not_found) to false), said)
    }

    @Test fun `a refused start is denied, a failing system not found, neither counts`() {
        system.startRefusal = SecurityException("no")
        launcher.launch(zulu)
        system.startRefusal = IllegalStateException("system_server died")
        launcher.launch(zulu)
        assertEquals(emptyList<AppEntry>(), launched)
        assertEquals(listOf(word(R.string.toast_denied) to false, word(R.string.toast_not_found) to false), said)
    }

    @Test fun `a paused profile is only turned on, and its app said ready once it is`() {
        system.paused = true
        launcher.launch(zulu)
        assertEquals("nothing starts", listOf("unpause"), system.calls)
        assertEquals(emptyList<AppEntry>(), launched)

        launcher.profileAvailable(profile(11))
        assertEquals("another profile says nothing", emptyList<Pair<String, Boolean>>(), said)
        clock = AppLauncher.PROFILE_WAIT_MS
        launcher.profileAvailable(work)
        launcher.profileAvailable(work)
        assertEquals("once", listOf(word(R.string.toast_app_ready) to true), said)
    }

    /** Work apps turned on hours later must not announce an app nobody just asked for. */
    @Test fun `a profile back later than the wait says nothing`() {
        system.paused = true
        launcher.launch(zulu)
        clock = AppLauncher.PROFILE_WAIT_MS + 1
        launcher.profileAvailable(work)
        assertEquals(emptyList<Pair<String, Boolean>>(), said)
    }

    /** Only the default home app may turn a profile on. */
    @Test fun `a refused unpause asks to be the default home, and waits for nothing`() {
        system.paused = true
        system.unpauseRefusal = SecurityException("not the default home")
        launcher.launch(zulu)
        launcher.profileAvailable(work)
        assertEquals(listOf(context.getString(R.string.toast_not_default_home) to true), said)
    }

    /** A dying system is no unpause: nothing waits for the profile. */
    @Test fun `a failing unpause is not found, and waits for nothing`() {
        system.paused = true
        system.unpauseRefusal = IllegalStateException("system_server died")
        launcher.launch(zulu)
        launcher.profileAvailable(work)
        assertEquals(listOf(word(R.string.toast_not_found) to false), said)
    }

    @Test fun `app info opens in the profile, a refusal is a word`() {
        launcher.openAppInfo(zulu)
        assertEquals(listOf("details com.zulu.Main"), system.calls)
        system.startRefusal = SecurityException("no")
        launcher.openAppInfo(zulu)
        user = null
        launcher.openAppInfo(zulu)
        assertEquals(listOf(word(R.string.toast_denied) to false, word(R.string.toast_not_found) to false), said)
        assertNull(launched.firstOrNull())
    }

    private companion object {
        const val PER_USER_RANGE = 100_000
    }
}
