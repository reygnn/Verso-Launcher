package com.github.reygnn.verso_launcher

import android.app.Activity
import android.app.AlertDialog
import android.content.DialogInterface
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowToast
import java.io.File
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Only flows that need no LauncherApps or MediaStore fakes (Robolectric models both too thinly).
 * The process's IO thread is real: the tests drain it, or hold it with a latch to keep a command running.
 * A restore's undo point goes to Robolectric's stub ContentResolver, which takes the write: the
 * restore tests lean on that thin model, not on a fake.
 */
@RunWith(RobolectricTestRunner::class)
class MainActivityFlowsTest {

    private val app = RuntimeEnvironment.getApplication() as VersoApp
    private val controller = Robolectric.buildActivity(MainActivity::class.java)
    private val activity: MainActivity get() = controller.get()
    private val hold = CountDownLatch(1)
    // A second hold for the IO thread alone, where a test must release a read before the IO step.
    private val ioHold = CountDownLatch(1)

    @After fun release() {
        hold.countDown()
        ioHold.countDown()
        // Else a read's or a command's hand-back lands on the main looper after the test, in the next one.
        joinReads()
        settle()
        if (!activity.isDestroyed) controller.pause().stop().destroy()
    }

    private fun settle() = repeat(4) {
        app.io.submit {}.get(5, TimeUnit.SECONDS)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun reads() = Thread.getAllStackTraces().keys.filter { it.name == "verso-restore-read" }

    /** The "~restore-saf" reads run on threads of their own. */
    private fun joinReads(threads: Collection<Thread> = reads()) = threads.forEach {
        it.join(5000)
        check(!it.isAlive) { "a ~restore-saf read is still running" }
    }

    private fun configWith(entry: AppEntry) =
        ConfigJson.serialize(VersoConfig(favorites = listOf(entry.key))).byteInputStream()

    /** A provider that hangs, like an offline cloud drive: the read waits for the hold. */
    private fun hanging(content: InputStream) = object : InputStream() {
        override fun read(): Int {
            check(hold.await(30, TimeUnit.SECONDS))
            return content.read()
        }
    }

    /** A "~restore-saf" whose picker answered with [content]. */
    private fun pick(name: String, content: InputStream) {
        val picked = Uri.parse("content://cloud/$name")
        shadowOf(activity.contentResolver).registerInputStream(picked, content)
        enter("~restore-saf")
        val request = shadowOf(activity).nextStartedActivityForResult
        shadowOf(activity).receiveResult(request.intent, Activity.RESULT_OK, Intent().setData(picked))
    }

    /** Whatever is queued after this waits, so a command stays running. */
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

    private val someApp = AppEntry("Zulu", "com.zulu", "com.zulu.Main", "Zulu")
    private val otherApp = AppEntry("Yankee", "com.yankee", "com.yankee.Main", "Yankee")
    private fun savedFavorites(): List<String> =
        File(activity.filesDir, "verso.json").takeIf { it.isFile }?.let { ConfigJson.parse(it.readText())?.favorites }.orEmpty()

    @Test fun `a second command while one runs is refused and stays in the prompt`() {
        started()
        holdIo()
        enter("~backup")                       // its IO step waits behind the hold
        assertEquals("the command clears the prompt", "", prompt.text.toString())
        enter("~restart")
        assertEquals(activity.getString(R.string.toast_busy), ShadowToast.getTextOfLatestToast())
        assertEquals("~restart", prompt.text.toString())
    }

    @Test fun `an edit while a command runs is refused, the same edit afterwards is saved`() {
        started()
        holdIo()
        enter("~backup")
        tap(DrawerAction.ToggleFavorite(someApp))
        assertEquals(activity.getString(R.string.toast_busy), ShadowToast.getTextOfLatestToast())
        hold.countDown()
        settle()
        assertFalse(someApp.key in savedFavorites())

        tap(DrawerAction.ToggleFavorite(someApp))
        settle()
        assertTrue(someApp.key in savedFavorites())
    }

    @Test fun `no edit before the first load`() {
        holdIo()                                // the first load queues behind the hold
        controller.setup()
        tap(DrawerAction.ToggleFavorite(someApp))
        assertEquals(activity.getString(R.string.toast_not_loaded), ShadowToast.getTextOfLatestToast())
    }

    /** Before the load and while a command runs: a deliberate crash waits for nothing. */
    @Test fun `~crash throws at once, even before the first load`() {
        holdIo()
        controller.setup()
        val thrown = runCatching { enter("~crash") }.exceptionOrNull()
        assertEquals(DailyDriver.ENABLED, thrown is IllegalStateException)
    }

    @Test fun `no command but ~restart before the first load`() {
        holdIo()
        controller.setup()
        enter("~backup")
        assertEquals(activity.getString(R.string.toast_not_loaded), ShadowToast.getTextOfLatestToast())
    }

    @Test fun `~restart while a picked file is read drops the pick and says so`() {
        started()
        pick("verso.json", hanging(configWith(someApp)))
        enter("~backup")
        assertEquals("other commands run meanwhile", "", prompt.text.toString())
        settle()
        enter("~restart")
        val dropped = activity.getString(R.string.toast_restore_saf_dropped)
        assertEquals(dropped, ShadowToast.getTextOfLatestToast())
        assertEquals("", prompt.text.toString())
        hold.countDown()
        joinReads()
        settle()
        assertEquals("the late read is not adopted", dropped, ShadowToast.getTextOfLatestToast())
        assertFalse(someApp.key in savedFavorites())
    }

    @Test fun `~restart drops a read whose hand-back is already queued`() {
        started()
        pick("verso.json", configWith(someApp))
        joinReads()                             // read, its hand-back waits on the main looper
        enter("~restart")
        assertEquals(activity.getString(R.string.toast_restore_saf_dropped), ShadowToast.getTextOfLatestToast())
        settle()
        assertFalse(someApp.key in savedFavorites())
    }

    /**
     * While a pick is read or waits, no second restore starts: an automatic adoption behind it would
     * sweep its undo point. Only those two wait — the other commands (and edits) go on.
     */
    @Test fun `a second restore while a pick is pending is refused and says why`() {
        started()
        pick("old.json", hanging(configWith(someApp)))
        val pending = activity.getString(R.string.toast_restore_pending)
        enter("~restore-saf")
        assertEquals(pending, ShadowToast.getTextOfLatestToast())
        assertEquals("~restore-saf", prompt.text.toString())
        assertNull("no second picker", shadowOf(activity).nextStartedActivityForResult)
        enter("~restore")
        assertEquals(pending, ShadowToast.getTextOfLatestToast())
        assertEquals("~restore", prompt.text.toString())
        enter("~backup")
        assertEquals("other commands go on", "", prompt.text.toString())
    }

    @Test fun `~restart builds the activity anew`() {
        val before = started()
        enter("~restart")
        settle()
        assertTrue(before.isDestroyed)
    }

    @Test fun `the rename dialog's OK says why it is refused while a command runs`() {
        started()
        activity.promptRename(someApp)
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        holdIo()
        enter("~backup")
        ShadowToast.reset()
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(activity.getString(R.string.toast_busy), ShadowToast.getTextOfLatestToast())
    }

    @Test fun `~restart drops nothing once the picked file was read`() {
        started()
        pick("verso.json", "not json".byteInputStream())
        joinReads()
        settle()
        val failed = activity.getString(R.string.toast_restore_failed)
        assertEquals(failed, ShadowToast.getTextOfLatestToast())
        enter("~restart")
        assertEquals(failed, ShadowToast.getTextOfLatestToast())
        assertEquals("", prompt.text.toString())
    }

    @Test fun `a restore closes an open rename dialog`() {
        started()
        activity.promptRename(someApp)
        val dialog = ShadowDialog.getLatestDialog()
        assertTrue(dialog.isShowing)
        pick("verso.json", configWith(someApp))
        joinReads()
        settle()
        assertTrue("restored", someApp.key in savedFavorites())
        assertFalse(dialog.isShowing)
    }

    private fun assertRestoredToast() {
        val prefix = activity.getString(R.string.toast_restore_ok, "")
        val shown = ShadowToast.getTextOfLatestToast()
        assertTrue("expected \"$prefix…\", got \"$shown\"", shown?.startsWith(prefix) == true)
    }

    /** B2: the hand-back lands after onStop — no toast over another app, but none lost either. */
    @Test fun `a restore finishing after onStop says so at the next start`() {
        started()
        controller.pause().stop()
        ShadowToast.reset()
        pick("verso.json", configWith(someApp))
        joinReads()
        settle()
        assertTrue("the restore still happened", someApp.key in savedFavorites())
        assertNull("no toast over the app now in front", ShadowToast.getLatestToast())
        controller.restart()
        assertRestoredToast()
        controller.resume()
    }

    /** B1: the new instance's onStart comes before the step ends; its first load queues behind it. */
    @Test fun `a restore whose hand-back a recreation dropped is reported by the new instance`() {
        started()
        holdIo()                                // the adoption's IO step waits behind the hold
        pick("verso.json", configWith(someApp))
        joinReads()
        shadowOf(Looper.getMainLooper()).idle() // the read's hand-back starts the adoption
        val before = activity
        before.recreate()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(before.isDestroyed)
        ShadowToast.reset()
        hold.countDown()
        settle()
        assertTrue("the restore was written", someApp.key in savedFavorites())
        assertRestoredToast()
    }

    /** The gate is the process's: a recreation finds the old run going until its IO step is through. */
    @Test fun `a run outlives a recreation and refuses the new instance until its step ends`() {
        started()
        holdIo()
        enter("~backup")                        // its IO step waits behind the hold
        controller.recreate()                   // the old instance's hand-back will be dropped
        shadowOf(Looper.getMainLooper()).idle()
        ShadowToast.reset()
        enter("~backup")
        assertEquals(activity.getString(R.string.toast_busy), ShadowToast.getTextOfLatestToast())
        assertEquals("~backup", prompt.text.toString())
        hold.countDown()
        settle()                                // the step, then the old instance's abandon behind it
        enter("~backup")
        assertEquals("accepted once the old run is given up", "", prompt.text.toString())
    }

    /** A pick read while a command runs waits for it, then is adopted — even while away. */
    @Test fun `a pick read while a command runs is adopted once it ends`() {
        started()
        pick("verso.json", hanging(configWith(someApp)))
        // A latch of its own: the read must land while the backup's step still waits.
        app.io.execute { ioHold.await(30, TimeUnit.SECONDS) }
        enter("~backup")                        // runs while the pick is read
        controller.pause().stop()
        ShadowToast.reset()
        hold.countDown()
        joinReads()
        shadowOf(Looper.getMainLooper()).idle() // the read lands: it waits for the backup
        assertFalse("not adopted while the backup runs", someApp.key in savedFavorites())
        assertNull("no toast over another app", ShadowToast.getLatestToast())
        ioHold.countDown()
        settle()                                // the backup ends, the adoption runs
        assertTrue("adopted after it", someApp.key in savedFavorites())
        assertEquals("both results queued", 2, app.process.messages.size)
        controller.restart()
        assertEquals(2, ShadowToast.shownToastCount())
        assertRestoredToast()
        controller.resume()
    }

    /**
     * A picker's answer can reach an instance whose first load has not landed (the process was
     * killed while the picker was open): the pick waits for the load instead of being refused.
     */
    @Test fun `a pick read before the first load is adopted once it lands`() {
        holdIo()                                // the first load queues behind the hold
        controller.setup()
        val picked = Uri.parse("content://cloud/verso.json")
        shadowOf(activity.contentResolver).registerInputStream(picked, configWith(otherApp))
        // Answered without the command, which is refused before the load: a picker opened earlier.
        val request = Intent(Intent.ACTION_OPEN_DOCUMENT)
        activity.startActivityForResult(request, REQ_RESTORE)
        shadowOf(activity).receiveResult(request, Activity.RESULT_OK, Intent().setData(picked))
        joinReads()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse("not before the load", otherApp.key in savedFavorites())
        hold.countDown()
        settle()
        assertTrue("adopted once loaded", otherApp.key in savedFavorites())
        assertRestoredToast()
    }

    /**
     * The pick is the process's: a recreation while it waits hands it to the new instance, which
     * adopts it at its first load. (The live-instance hand-over on a run's end, VersoApp.instance and
     * onIdle, is the path of "a pick read while a command runs".)
     */
    @Test fun `a waiting pick survives a recreation and the new instance adopts it`() {
        started()
        pick("verso.json", hanging(configWith(someApp)))
        app.io.execute { ioHold.await(30, TimeUnit.SECONDS) }
        enter("~backup")
        hold.countDown()
        joinReads()
        shadowOf(Looper.getMainLooper()).idle() // read, waiting for the backup
        controller.recreate()                   // the backup's hand-back will be dropped
        shadowOf(Looper.getMainLooper()).idle()
        ShadowToast.reset()
        ioHold.countDown()
        settle()                                // the step, the old run given up, the new load
        assertTrue("adopted by the new instance", someApp.key in savedFavorites())
        assertRestoredToast()                   // after the backup's result, which the new load shows
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
