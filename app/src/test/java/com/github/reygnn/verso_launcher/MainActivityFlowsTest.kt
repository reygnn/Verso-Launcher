package com.github.reygnn.verso_launcher

import android.content.Intent
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ListView
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowToast
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Only flows that need no app listed, so the files here name apps by key; the flows with apps are
 * MainActivityAppsTest's. The process's IO thread is real: the tests drain it, or hold
 * it with a latch to keep the first load waiting. The user's config file is a real file in the
 * application's external files folder, written before the state is built or before a "~restart".
 */
@RunWith(RobolectricTestRunner::class)
class MainActivityFlowsTest {

    private val app = RuntimeEnvironment.getApplication() as VersoApp
    private val controller = Robolectric.buildActivity(MainActivity::class.java)
    private val activity: MainActivity get() = controller.get()
    private val hold = CountDownLatch(1)

    @After fun release() {
        hold.countDown()
        settle()
        if (!activity.isDestroyed) controller.pause().stop().destroy()
    }

    private fun settle() = repeat(4) {
        app.io.submit {}.get(5, TimeUnit.SECONDS)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private val someApp = AppEntry("Zulu", "com.zulu", "com.zulu.Main", "Zulu")
    private val otherApp = AppEntry("Yankee", "com.yankee", "com.yankee.Main", "Yankee")

    /** The user's config file at its fixed place (ConfigFile), as a PC or `adb push` leaves it. */
    private fun configFile(text: String) =
        File(checkNotNull(app.getExternalFilesDir(null)), ConfigFile.NAME).writeText(text)

    private fun configText() = File(checkNotNull(app.getExternalFilesDir(null)), ConfigFile.NAME).readText()

    /** Whatever is queued after this waits: the first load, a cache write. */
    // 30 s: the hold must outlast a slow machine, and @After releases it anyway.
    private fun holdIo() = app.io.execute { hold.await(30, TimeUnit.SECONDS) }

    private fun started(): MainActivity = controller.setup().get().also { settle() }

    private val prompt: EditText get() = activity.window.decorView.collect<EditText>().single()
    private val root: SwipeFrame get() = activity.window.decorView.collect<SwipeFrame>().single()

    private fun enter(text: String) {
        prompt.setText(text)
        prompt.onEditorAction(EditorInfo.IME_ACTION_GO)
    }

    /** No app listed, so no rows to tap: the tap as AppDrawer hands it on. */
    private fun tap(action: DrawerAction<AppEntry>) = activity.onDrawerAction(action)

    private fun favorites() = app.core.state.config.favorites

    // ---- the config file ---------------------------------------------------------------

    /** Hard rule 5: the file is the config from the first frame, read before the state exists. */
    @Test fun `the config file is the config from the first frame`() {
        configFile("fav ${someApp.key}")
        started()
        assertEquals(listOf(someApp.key), favorites())
    }

    /** A return to the launcher reads nothing: "~restart" is the reload. */
    @Test fun `a changed file counts after ~restart, not at the next start`() {
        configFile("fav ${someApp.key}")
        started()
        configFile("fav ${otherApp.key}\nfav ${someApp.key}")
        controller.pause().stop().restart().resume()
        settle()
        assertEquals("not read again at a start", listOf(someApp.key), favorites())
        enter("~restart")
        settle()
        assertEquals(listOf(otherApp.key, someApp.key), favorites())
    }

    /** A line that is no entry is logged, not said: the rest of the file counts. */
    @Test fun `a file with lines that are no entry still counts, and says nothing`() {
        configFile("fav ${someApp.key}\nfavourite Zulu\nhide")
        started()
        assertEquals(listOf(someApp.key), favorites())
        assertEquals(0, ShadowToast.shownToastCount())
    }

    // ---- "%" ------------------------------------------------------------------------------

    @Test fun `percent keeps the order in order_txt, never in the config`() {
        configFile("fav ${someApp.key}\n")
        started()
        tap(DrawerAction.ToggleOrder)
        settle()
        assertTrue(app.core.state.config.byUsage)
        assertEquals("usage", File(app.filesDir, DrawerOrder.FILE).readText().trim())
        assertEquals("fav ${someApp.key}\n", configText())
        enter("~restart")
        settle()
        assertTrue("read again by the fresh state", app.core.state.config.byUsage)
    }

    // ---- ~restart, ~crash, recreation ----------------------------------------------------

    /** With the IO thread held too: a deliberate crash waits for nothing. */
    @Test fun `~crash throws at once, even with the IO thread held`() {
        holdIo()
        controller.setup()
        val thrown = runCatching { enter("~crash") }.exceptionOrNull()
        assertEquals(DailyDriver.ENABLED, thrown is IllegalStateException)
    }

    @Test fun `~restart builds the activity anew`() {
        val before = started()
        enter("~restart")
        settle()
        assertTrue(before.isDestroyed)
    }

    /**
     * The state is the process's: a recreation keeps what was loaded and does not read the files
     * again — only "~restart" gives a fresh state that does.
     */
    @Test fun `a recreation keeps the loaded state, ~restart reads the files again`() {
        started()
        val loaded = app.core.state
        DrawerOrder.write(app.filesDir, byUsage = true)   // there now, as a lost write would have left it
        configFile("fav ${someApp.key}")
        controller.recreate()
        settle()
        assertSame("one state for both instances", loaded, app.core.state)
        assertFalse("not read again", app.core.state.config.byUsage)
        assertEquals("not read again", emptyList<String>(), favorites())
        enter("~restart")
        settle()
        assertNotSame("a fresh state", loaded, app.core.state)
        assertTrue("read again", app.core.state.config.byUsage)
        assertEquals("read again", listOf(someApp.key), favorites())
    }

    /**
     * The state's hand-backs are the process's too: a refresh landing while no window exists still
     * lands (no app is listed here, so its usage.txt read is what shows it).
     */
    @Test fun `a refresh lands while no window exists`() {
        holdIo()                                // the first refresh queues behind the hold
        controller.setup()
        controller.pause().stop().destroy()
        hold.countDown()
        settle()
        assertTrue(app.process.usage.loaded)
    }

    /**
     * After ~restart the process's state is already the fresh one, but the recreation is only queued:
     * this window would check a command against its old, loaded state and run it on the new one.
     */
    @Test fun `the window ~restart replaces takes no command before the recreation lands`() {
        started()
        enter("~restart")                       // the recreation is queued; the looper does not run
        enter("~res")
        assertEquals("not taken, left for the new window", "~res", prompt.text.toString())
    }

    @Test fun `a second ~restart before the recreation lands resets nothing more`() {
        started()
        val loaded = app.core.state
        enter("~restart")
        val fresh = app.core.state
        assertNotSame(loaded, fresh)
        enter("~restart")
        assertSame("reset once", fresh, app.core.state)
    }

    private fun startSwipeUp() {
        val t = SystemClock.uptimeMillis()
        root.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, 500f, 1500f, 0))
        for (i in 1..10) {
            root.dispatchTouchEvent(MotionEvent.obtain(t, t + i * 16, MotionEvent.ACTION_MOVE, 500f, 1500f - i * 60f, 0))
        }
    }

    private val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)

    @Test fun `HOME ends a running swipe`() {
        started()
        startSwipeUp()
        assertTrue("the drag has started", root.isDragging)
        controller.newIntent(home)
        assertFalse(root.isDragging)
    }

    /** A hardware keyboard on home: the first key opens the drawer with its character typed. */
    @Test fun `typing on home opens the drawer with what was typed`() {
        started()
        assertTrue(activity.onKeyDown(KeyEvent.KEYCODE_G, keyDown(KeyEvent.KEYCODE_G)))
        assertEquals("g", prompt.text.toString())
        assertFalse("the drawer takes the next key itself", activity.onKeyDown(KeyEvent.KEYCODE_M, keyDown(KeyEvent.KEYCODE_M)))
    }

    private fun keyDown(code: Int) = KeyEvent(KeyEvent.ACTION_DOWN, code)

    // ---- ~verify -------------------------------------------------------------------------

    private fun shownRows(): List<Any> {
        val list = activity.window.decorView.collect<ListView>().single()
        return (0 until list.adapter.count).map { list.adapter.getItem(it) }
    }

    @Test fun `~verify lists what the read found, the keyboard down`() {
        configFile("fav ${someApp.key}\nfavourite Zulu")
        started()
        enter("~v")
        assertEquals(LauncherLogic.VERIFY, prompt.text.toString())
        assertTrue(ConfigFinding.BadLine(ConfigLine(2, "favourite Zulu")).let(::ReportRow) in shownRows())
    }
}
