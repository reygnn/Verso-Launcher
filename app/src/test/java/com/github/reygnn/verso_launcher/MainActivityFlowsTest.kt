package com.github.reygnn.verso_launcher

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
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
import java.io.InputStream
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Only flows that need no LauncherApps fake (Robolectric models it too thinly: it lists no apps, so
 * the files here name apps by key). The process's IO thread is real: the tests drain it, or hold
 * it with a latch to keep the first load waiting. The user's config file is a document registered
 * with Robolectric's ContentResolver; one that hangs waits for [hold].
 */
@RunWith(RobolectricTestRunner::class)
class MainActivityFlowsTest {

    private val app = RuntimeEnvironment.getApplication() as VersoApp
    private val controller = Robolectric.buildActivity(MainActivity::class.java)
    private val activity: MainActivity get() = controller.get()
    private val hold = CountDownLatch(1)

    @After fun release() {
        hold.countDown()
        // Else a read's hand-back lands on the main looper after the test, in the next one.
        joinDocuments()
        settle()
        if (!activity.isDestroyed) controller.pause().stop().destroy()
    }

    private fun settle() = repeat(4) {
        app.io.submit {}.get(5, TimeUnit.SECONDS)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun documentThreads() =
        Thread.getAllStackTraces().keys.filter { it.name == "verso-config-read" }

    /**
     * The config file is read on a thread of its own: joined, then its hand-back run —
     * again while a hand-back starts another (a read asked for meanwhile). [except]: reads left
     * hanging on purpose, for what lands while they still hang.
     */
    private fun joinDocuments(except: Collection<Thread> = emptyList()) {
        repeat(4) {
            val running = documentThreads() - except.toSet()
            running.forEach {
                it.join(5000)
                check(!it.isAlive) { "a config file read is still running" }
            }
            shadowOf(Looper.getMainLooper()).idle()
            if (running.isEmpty()) return
        }
    }

    private val someApp = AppEntry("Zulu", "com.zulu", "com.zulu.Main", "Zulu")
    private val otherApp = AppEntry("Yankee", "com.yankee", "com.yankee.Main", "Yankee")

    private val fileUri = Uri.parse("content://cloud/verso.txt")
    /** What the user's file holds now: read anew by every read, as a provider would. */
    private var fileText = ""

    /** The user's config file, readable at [fileUri]. */
    private fun provided(text: String) {
        fileText = text
        shadowOf(app.contentResolver).registerInputStreamSupplier(fileUri) { fileText.byteInputStream() }
    }

    /** A file linked by an earlier start: the link and the copy of its last text, as Verso left them. */
    private fun linkedBefore(cached: String?) {
        File(app.filesDir, ConfigLink.LINK).writeText("$fileUri\n")
        if (cached != null) File(app.filesDir, ConfigLink.CACHE).writeText(cached)
    }

    /** A provider that hangs, slow to hand the file over: the read waits for the hold. */
    private fun hanging(content: InputStream) = object : InputStream() {
        override fun read(): Int {
            check(hold.await(30, TimeUnit.SECONDS))
            return content.read()
        }
    }

    /** A picker the command started, answered with [uri]; returns its request. */
    private fun answer(uri: Uri): Intent {
        val request = checkNotNull(shadowOf(activity).nextStartedActivityForResult) { "no picker started" }
        shadowOf(activity).receiveResult(request.intent, Activity.RESULT_OK, Intent().setData(uri))
        return request.intent
    }

    /** Whatever is queued after this waits: the first load, a cache write. */
    // 30 s, like a hanging read: the hold must outlast a slow machine, and @After releases it anyway.
    private fun holdIo() = app.io.execute { hold.await(30, TimeUnit.SECONDS) }

    private fun started(): MainActivity = controller.setup().get().also { settle() }

    private val prompt: EditText get() = activity.window.decorView.collect<EditText>().single()
    private val root: SwipeFrame get() = activity.window.decorView.collect<SwipeFrame>().single()

    private fun enter(text: String) {
        prompt.setText(text)
        prompt.onEditorAction(EditorInfo.IME_ACTION_GO)
    }

    /** No LauncherApps, so no rows to tap: the tap as AppDrawer hands it on. */
    private fun tap(action: DrawerAction<AppEntry>) = activity.onDrawerAction(action)

    private fun favorites() = app.core.state.config.favorites

    private fun toast(id: Int, vararg args: Any) = activity.getString(id, *args)

    // ---- ~open --------------------------------------------------------------------

    @Test fun `~open links a picked config file, reads it and keeps it for the next start`() {
        provided("fav ${someApp.key}")
        started()
        enter("~open")
        val request = answer(fileUri)
        joinDocuments()
        settle()
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, request.action)
        assertTrue("a grant that lasts", request.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0)
        assertEquals(fileUri, app.core.link)
        assertEquals(listOf(someApp.key), favorites())
        assertEquals(toast(R.string.toast_linked), ShadowToast.getTextOfLatestToast())
        assertEquals(fileUri.toString(), File(app.filesDir, ConfigLink.LINK).readText().trim())
        assertEquals(fileText, File(app.filesDir, ConfigLink.CACHE).readText())
    }

    @Test fun `a picked file that is no config is not linked`() {
        provided("Dear diary,\ntoday I picked the wrong file.")
        started()
        enter("~open")
        answer(fileUri)
        joinDocuments()
        settle()
        assertNull(app.core.link)
        assertEquals(toast(R.string.toast_not_a_config), ShadowToast.getTextOfLatestToast())
        assertFalse(File(app.filesDir, ConfigLink.LINK).exists())
    }

    /** The user's last choice stands: a slow provider's answer to an earlier pick changes nothing. */
    @Test fun `a pick landing after a newer one is dropped`() {
        val slow = Uri.parse("content://cloud/slow.txt")
        shadowOf(app.contentResolver).registerInputStream(slow, hanging("fav ${otherApp.key}".byteInputStream()))
        provided("fav ${someApp.key}")
        started()
        enter("~open")
        answer(slow)
        enter("~open")
        answer(fileUri)
        hold.countDown()
        joinDocuments()
        settle()
        assertEquals(fileUri, app.core.link)
        assertEquals(listOf(someApp.key), favorites())
    }

    @Test fun `the lines a file has that are no entry are named, the rest counts`() {
        provided("fav ${someApp.key}\nfavourite Zulu\nhide")
        started()
        enter("~open")
        answer(fileUri)
        joinDocuments()
        assertEquals(listOf(someApp.key), favorites())
        assertEquals(toast(R.string.toast_config_lines_skipped, "2, 3"), ShadowToast.getTextOfLatestToast())
    }

    // ---- the linked file read again ---------------------------------------------------

    /** Back from the editor: onStart reads the file, and a changed text is the config at once. */
    @Test fun `the linked file is read again at every start, a change shows`() {
        provided("fav ${someApp.key}")
        linkedBefore(cached = fileText)
        started()
        assertEquals(listOf(someApp.key), favorites())
        controller.pause().stop()
        fileText = "fav ${otherApp.key}\nfav ${someApp.key}"
        controller.restart().resume()
        joinDocuments()
        settle()
        assertEquals(listOf(otherApp.key, someApp.key), favorites())
        assertEquals(fileText, File(app.filesDir, ConfigLink.CACHE).readText())
    }

    /** Hard rule 5: the copy is the config from the first frame; the read only confirms it. */
    @Test fun `a start shows the copy of the linked file before the file is read`() {
        linkedBefore(cached = "fav ${someApp.key}")
        shadowOf(app.contentResolver).registerInputStream(fileUri, hanging("fav ${otherApp.key}".byteInputStream()))
        started()
        assertEquals("the copy, while the read hangs", listOf(someApp.key), favorites())
        hold.countDown()
        joinDocuments()
        assertEquals(listOf(otherApp.key), favorites())
    }

    @Test fun `a linked file that cannot be read keeps the last text read, and says so once`() {
        linkedBefore(cached = "fav ${someApp.key}")    // no document registered: every read fails
        started()
        joinDocuments()
        settle()
        assertEquals(listOf(someApp.key), favorites())
        assertEquals(toast(R.string.toast_config_file_unreadable), ShadowToast.getTextOfLatestToast())
        ShadowToast.reset()
        controller.pause().stop().restart().resume()
        joinDocuments()
        settle()
        assertEquals("said once, not at every start", 0, ShadowToast.shownToastCount())
        assertEquals(listOf(someApp.key), favorites())
    }

    /** A provider that hands each read [text] only once [hold] is released. */
    private fun hangingFile(text: String) =
        shadowOf(app.contentResolver).registerInputStreamSupplier(fileUri) { hanging(text.byteInputStream()) }

    private fun waitFor(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    @Test fun `a read of the linked file that hangs is said once, and its text still counts when it comes`() {
        linkedBefore(cached = "fav ${someApp.key}")
        hangingFile("fav ${otherApp.key}")
        // The read starts somewhere inside started(), and Robolectric's clock moves during setup.
        val before = SystemClock.uptimeMillis()
        started()
        val after = SystemClock.uptimeMillis()
        ShadowToast.reset()
        waitFor(before + READ_STUCK_MS - 1 - after)
        assertEquals("not yet", 0, ShadowToast.shownToastCount())
        waitFor(after - before + 1)
        assertEquals(toast(R.string.toast_config_file_stuck), ShadowToast.getTextOfLatestToast())
        assertEquals("the copy stays", listOf(someApp.key), favorites())
        ShadowToast.reset()
        controller.pause().stop().restart().resume()
        assertEquals("said once, not at every start", 0, ShadowToast.shownToastCount())
        assertEquals("never a second read beside it", 1, documentThreads().size)
        hold.countDown()
        joinDocuments()
        settle()
        assertEquals(listOf(otherApp.key), favorites())
        assertEquals(0, ShadowToast.shownToastCount())
    }

    /** Queued while away, the word could outlive the read it is about: the next start decides. */
    @Test fun `a read that hangs while the launcher is away is said at the next start`() {
        linkedBefore(cached = "fav ${someApp.key}")
        hangingFile("fav ${someApp.key}")
        started()
        controller.pause().stop()
        waitFor(READ_STUCK_MS)
        assertTrue("nothing queued while away", app.process.messages.isEmpty())
        ShadowToast.reset()
        controller.restart().resume()
        assertEquals(toast(R.string.toast_config_file_stuck), ShadowToast.getTextOfLatestToast())
    }

    /**
     * A read that finds the text known when it began parses nothing. If the config took another
     * text meanwhile, the file is read again, so no writer of [Core.linkedText] has to know about
     * that shortcut.
     *
     * Why: today only "~open" changes that text while a read runs, and it drops the read. So the
     *   test changes it directly, the way a future writer would.
     */
    @Test fun `a read that finds the old text reads again when the config changed meanwhile`() {
        linkedBefore(cached = "fav ${someApp.key}")
        hangingFile("fav ${someApp.key}")
        started()
        app.core.adoptLinked("fav ${otherApp.key}", ConfigText.parse("fav ${otherApp.key}"))
        assertEquals(listOf(otherApp.key), favorites())
        hold.countDown()
        joinDocuments()
        settle()
        assertEquals("the file's text, read again", listOf(someApp.key), favorites())
    }

    @Test fun `a read that answered in time says nothing when its time is up`() {
        provided("fav ${someApp.key}")
        linkedBefore(cached = fileText)
        started()
        joinDocuments()
        settle()
        ShadowToast.reset()
        waitFor(READ_STUCK_MS)
        assertEquals(0, ShadowToast.shownToastCount())
    }

    @Test fun `a read is timed from its own start, not an earlier read's`() {
        provided("fav ${someApp.key}")
        linkedBefore(cached = fileText)
        started()
        joinDocuments()
        controller.pause().stop()
        waitFor(READ_STUCK_MS / 2)
        hangingFile("fav ${someApp.key}")
        controller.restart().resume()
        ShadowToast.reset()
        waitFor(READ_STUCK_MS / 2)
        assertEquals("the first read's time is up, not the second's", 0, ShadowToast.shownToastCount())
        waitFor(READ_STUCK_MS / 2)
        assertEquals(toast(R.string.toast_config_file_stuck), ShadowToast.getTextOfLatestToast())
    }

    /**
     * The user's own step past a read that hangs: "~open" of the same file reads it afresh, the
     * next start reads it again beside the hung read, and the hung read's late text — an older
     * file — changes nothing.
     */
    @Test fun `~open of a file whose read hangs reads it afresh, and the late text changes nothing`() {
        linkedBefore(cached = "fav ${someApp.key}")
        fileText = "fav ${otherApp.key}"
        val opened = AtomicInteger()
        shadowOf(app.contentResolver).registerInputStreamSupplier(fileUri) {
            if (opened.getAndIncrement() == 0) hanging("fav ${someApp.key}".byteInputStream()) else fileText.byteInputStream()
        }
        started()
        val hung = documentThreads()
        enter("~open")
        answer(fileUri)
        joinDocuments(except = hung)
        assertEquals(fileUri, app.core.link)
        assertEquals("the ~open read", listOf(otherApp.key), favorites())
        fileText = "fav ${otherApp.key}\nfav ${someApp.key}"
        controller.pause().stop().restart().resume()
        joinDocuments(except = hung)
        assertEquals("a read afresh at the next start", listOf(otherApp.key, someApp.key), favorites())
        hold.countDown()
        joinDocuments()
        settle()
        assertEquals("the hung read's late text changes nothing", listOf(otherApp.key, someApp.key), favorites())
    }

    /**
     * Hard rule 15, the user's last choice stands: a file picked with "~open" counts even when the
     * old file, read again while the pick was slow to come, brought a text from a later read.
     */
    @Test fun `a file picked with ~open counts even after a later read of the old one`() {
        provided("fav ${someApp.key}")
        linkedBefore(cached = fileText)
        val picked = Uri.parse("content://cloud/picked.txt")
        shadowOf(app.contentResolver).registerInputStream(picked, hanging("fav ${otherApp.key}".byteInputStream()))
        started()
        joinDocuments()
        enter("~open")
        answer(picked)
        val slow = documentThreads()
        fileText = "fav ${someApp.key}\nfav ${otherApp.key}"
        controller.pause().stop().restart().resume()
        joinDocuments(except = slow)
        assertEquals("the old file, read again", listOf(someApp.key, otherApp.key), favorites())
        hold.countDown()
        joinDocuments()
        settle()
        assertEquals(picked, app.core.link)
        assertEquals(listOf(otherApp.key), favorites())
    }

    // ---- ~edit --------------------------------------------------------------------------

    @Test fun `~edit with no file linked says how to get one`() {
        started()
        enter("~edit")
        assertEquals(toast(R.string.toast_no_config_file), ShadowToast.getTextOfLatestToast())
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun `~edit hands the linked file to an editor, with the grant to write it`() {
        linkedBefore(cached = "")
        started()
        enter("~edit")
        val editor = checkNotNull(shadowOf(activity).nextStartedActivity)
        assertEquals(Intent.ACTION_EDIT, editor.action)
        assertEquals(fileUri, editor.data)
        assertTrue(editor.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0)
        assertTrue("not in the home task", editor.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertEquals("", prompt.text.toString())
    }

    // ---- "%" ------------------------------------------------------------------------------

    @Test fun `percent keeps the order in order_txt, never in the config`() {
        linkedBefore("fav ${someApp.key}\n")
        started()
        tap(DrawerAction.ToggleOrder)
        settle()
        assertTrue(app.core.state.config.byUsage)
        assertEquals("usage", File(app.filesDir, DrawerOrder.FILE).readText().trim())
        assertEquals("fav ${someApp.key}\n", File(app.filesDir, ConfigLink.CACHE).readText())
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
     * The state is the process's: a recreation keeps what was loaded and does not read the app's
     * files again — only "~restart", the repair by hand, gives a fresh state that does.
     */
    @Test fun `a recreation keeps the loaded state, ~restart reads the app's files again`() {
        started()
        val loaded = app.core.state
        DrawerOrder.write(app.filesDir, byUsage = true)   // there now, as a lost write would have left it
        controller.recreate()
        settle()
        assertSame("one state for both instances", loaded, app.core.state)
        assertFalse("not read again", app.core.state.config.byUsage)
        enter("~restart")
        settle()
        assertNotSame("a fresh state", loaded, app.core.state)
        assertTrue("read again", app.core.state.config.byUsage)
    }

    /** The read is the process's: one that lands after a recreation goes into the state both share. */
    @Test fun `a read landing across a recreation is the shared state's`() {
        shadowOf(app.contentResolver).registerInputStream(fileUri, hanging("fav ${someApp.key}".byteInputStream()))
        started()
        enter("~open")
        answer(fileUri)
        val before = activity
        before.recreate()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(before.isDestroyed)
        hold.countDown()
        joinDocuments()
        settle()
        assertEquals(fileUri, app.core.link)
        assertEquals(listOf(someApp.key), favorites())
    }

    @Test fun `every queued result is shown at the next start, oldest first, once`() {
        started()
        controller.pause().stop()
        app.process.messages.add("first")
        app.process.messages.add("second")
        ShadowToast.reset()
        controller.restart()
        assertEquals(2, ShadowToast.shownToastCount())
        assertTrue(ShadowToast.showedToast("first"))
        assertEquals("second", ShadowToast.getTextOfLatestToast())
        assertTrue(app.process.messages.isEmpty())
        controller.resume()
    }

    /**
     * The state's hand-backs are the process's too: a refresh landing while no window exists still
     * lands (Robolectric lists no apps, so its usage.txt read is what shows it).
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
        enter("~edit")
        assertEquals("not taken, left for the new window", "~edit", prompt.text.toString())
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
}
