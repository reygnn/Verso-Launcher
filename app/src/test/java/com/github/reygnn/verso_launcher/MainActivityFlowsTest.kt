package com.github.reygnn.verso_launcher

import android.app.Activity
import android.app.AlertDialog
import android.content.DialogInterface
import android.content.Intent
import android.net.Uri
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
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

    /** A provider that hangs, slow to hand the file over: the read waits for the hold. */
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

    private val thirdApp = AppEntry("Xray", "com.xray", "com.xray.Main", "Xray")

    private fun favorites(vararg entries: AppEntry) {
        entries.forEach { tap(DrawerAction.ToggleFavorite(it)) }
        settle()
    }

    @Test fun `!! moves the picked favorite to the one tapped next, and lets go of it`() {
        started()
        favorites(someApp, otherApp, thirdApp)
        tap(DrawerAction.ReorderPick(thirdApp))
        tap(DrawerAction.ReorderPick(someApp))
        settle()
        assertEquals(listOf(thirdApp.key, someApp.key, otherApp.key), savedFavorites())

        tap(DrawerAction.ReorderPick(otherApp))
        settle()
        assertEquals("the next tap picks up, it moves nothing", listOf(thirdApp.key, someApp.key, otherApp.key), savedFavorites())
    }

    @Test fun `!! tapping the held favorite again lets go of it`() {
        started()
        favorites(someApp, otherApp)
        tap(DrawerAction.ReorderPick(otherApp))
        tap(DrawerAction.ReorderPick(otherApp))
        tap(DrawerAction.ReorderPick(someApp))
        settle()
        assertEquals(listOf(someApp.key, otherApp.key), savedFavorites())
    }

    @Test fun `no edit before the first load`() {
        holdIo()                                // the first load queues behind the hold
        controller.setup()
        tap(DrawerAction.ToggleFavorite(someApp))
        assertEquals(activity.getString(R.string.toast_not_loaded), ShadowToast.getTextOfLatestToast())
    }

    /** A cached favorite can be held before the load: the dialog would open on the empty default. */
    @Test fun `no rename dialog before the first load`() {
        holdIo()
        controller.setup()
        activity.promptRename(someApp)
        assertFalse(ShadowDialog.getLatestDialog()?.isShowing == true)
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
     * While a pick is read or waits, no second restore starts: the automatic adoption behind it would
     * replace what that restore brought. Only those two wait — the other commands (and edits) go on.
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
        shadowOf(Looper.getMainLooper()).idle() // the read lands: the restore dialog asks
        answerRestore()
        settle()
        assertTrue("restored", someApp.key in savedFavorites())
        assertFalse(dialog.isShowing)
    }

    @Test fun `~backup says where it wrote`() {
        started()
        enter("~backup")
        settle()
        val prefix = activity.getString(R.string.toast_backup_ok, "")
        val shown = ShadowToast.getTextOfLatestToast().orEmpty()
        assertTrue("expected \"$prefix…\", got \"$shown\"", shown.startsWith(prefix))
        assertTrue(shown, BackupFiles.isPublished(BackupFiles.BACKUP_BASE, shown.removePrefix(prefix)))
    }

    /** Named as a backup, the undo point would be the newest one, and the next ~restore would take it. */
    @Test fun `a restore's undo point is never named as a backup`() {
        val media = Robolectric.setupContentProvider(GuruWiringTest.Downloads::class.java, MediaStore.AUTHORITY)
        started()
        pick("verso.json", configWith(someApp))
        joinReads()
        shadowOf(Looper.getMainLooper()).idle()
        answerRestore()
        settle()
        assertTrue("restored", someApp.key in savedFavorites())
        val names = media.names
        assertTrue(names.toString(), names.any { BackupFiles.isPublished(BackupFiles.PRE_RESTORE_BASE, it) })
        assertFalse(names.toString(), names.any { BackupFiles.isPublished(BackupFiles.BACKUP_BASE, it) })
    }

    private fun assertRestoredToast() {
        val prefix = activity.getString(R.string.toast_restore_ok, "")
        val shown = ShadowToast.getTextOfLatestToast()
        assertTrue("expected \"$prefix…\", got \"$shown\"", shown?.startsWith(prefix) == true)
    }

    /** The "~restore-saf" confirmation in front, or null. */
    private fun restoreDialog(): AlertDialog? = (ShadowDialog.getLatestDialog() as? AlertDialog)
        ?.takeIf { it.isShowing && shadowOf(it).title == activity.getString(R.string.restore_title) }

    /** Answers the restore dialog (it must be up) and lets the answer's hand-backs run. */
    private fun answerRestore(ok: Boolean = true) {
        val dialog = checkNotNull(restoreDialog()) { "no restore dialog in front" }
        dialog.getButton(if (ok) DialogInterface.BUTTON_POSITIVE else DialogInterface.BUTTON_NEGATIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun savedConfig(): VersoConfig? =
        File(activity.filesDir, "verso.json").takeIf { it.isFile }?.let { ConfigJson.parse(it.readText()) }

    /** B2: the hand-back lands after onStop — no toast over another app, but none lost either. */
    @Test fun `a restore finishing after onStop says so at the next start`() {
        started()
        holdIo()                                // the adoption's IO step waits behind the hold
        pick("verso.json", configWith(someApp))
        joinReads()
        shadowOf(Looper.getMainLooper()).idle()
        answerRestore()
        controller.pause().stop()
        ShadowToast.reset()
        hold.countDown()
        settle()
        assertTrue("the restore still happened", someApp.key in savedFavorites())
        assertNull("no toast over the app now in front", ShadowToast.getLatestToast())
        controller.restart()
        assertRestoredToast()
        controller.resume()
    }

    /** A file read while away is not adopted unseen: the next start asks about it. */
    @Test fun `a pick read while away is asked about at the next start`() {
        started()
        controller.pause().stop()
        pick("verso.json", configWith(someApp))
        joinReads()
        settle()
        assertNull("no dialog over another app", restoreDialog())
        assertFalse("nothing adopted unasked", someApp.key in savedFavorites())
        controller.restart()
        answerRestore()
        settle()
        assertTrue(someApp.key in savedFavorites())
        assertRestoredToast()
        controller.resume()
    }

    /**
     * A file another app planted in Download/Verso can hide or rename apps: the dialog shows what it
     * sets — zeros too — and Cancel adopts nothing, says nothing, and frees ~restore-saf again.
     */
    @Test fun `the restore dialog shows the file's counts, and Cancel adopts nothing`() {
        started()
        val planted = VersoConfig(hidden = setOf(someApp.key, otherApp.key), names = mapOf(someApp.key to "Bank"))
        pick("planted.json", ConfigJson.serialize(planted).byteInputStream())
        joinReads()
        shadowOf(Looper.getMainLooper()).idle()
        val message = shadowOf(checkNotNull(restoreDialog())).message.toString()
        assertTrue(message, activity.getString(R.string.cleanup_favorites, 0) in message)
        assertTrue(message, activity.getString(R.string.cleanup_hidden, 2) in message)
        assertTrue(message, activity.getString(R.string.cleanup_names, 1) in message)
        ShadowToast.reset()
        answerRestore(ok = false)
        settle()
        assertTrue("nothing hidden", savedConfig()?.hidden.orEmpty().isEmpty())
        assertNull("Cancel says nothing", ShadowToast.getLatestToast())
        enter("~restore-saf")
        assertTrue("the pick is over: a new one may start", shadowOf(activity).nextStartedActivityForResult != null)
    }

    /** A question the user turned away from is void, like every dialog left behind (turnAway). */
    @Test fun `a restore dialog left unanswered ends the pick and says so`() {
        started()
        pick("verso.json", configWith(someApp))
        joinReads()
        shadowOf(Looper.getMainLooper()).idle()
        checkNotNull(restoreDialog())
        controller.newIntent(home)
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(restoreDialog())
        assertEquals(activity.getString(R.string.toast_restore_saf_dropped), ShadowToast.getTextOfLatestToast())
        settle()
        assertFalse(someApp.key in savedFavorites())
    }

    /**
     * B1: a recreation in the middle of a restore. The step's hand-back is the process's, so the
     * restore lands in the shared state too — not only on disk, or the next edit would write the
     * old config back over it — and the new instance says so.
     */
    @Test fun `a restore across a recreation lands in memory and is reported by the new instance`() {
        started()
        holdIo()                                // the adoption's IO step waits behind the hold
        pick("verso.json", configWith(someApp))
        joinReads()
        shadowOf(Looper.getMainLooper()).idle() // the read lands: the restore dialog asks
        answerRestore()                         // the OK starts the adoption
        val before = activity
        before.recreate()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(before.isDestroyed)
        ShadowToast.reset()
        hold.countDown()
        settle()
        assertTrue("the restore was written", someApp.key in savedFavorites())
        assertTrue("and adopted: memory matches the file", someApp.key in app.core.state.config.favorites)
        assertRestoredToast()
    }

    /** The gate is the process's: a recreation finds the old run going until its IO step is through. */
    @Test fun `a run outlives a recreation and refuses the new instance until its step ends`() {
        started()
        holdIo()
        enter("~backup")                        // its IO step waits behind the hold
        controller.recreate()                   // the step's hand-back goes to the process
        shadowOf(Looper.getMainLooper()).idle()
        ShadowToast.reset()
        enter("~backup")
        assertEquals(activity.getString(R.string.toast_busy), ShadowToast.getTextOfLatestToast())
        assertEquals("~backup", prompt.text.toString())
        hold.countDown()
        settle()                                // the step and its hand-back, which ends the run
        enter("~backup")
        assertEquals("accepted once the old run ended", "", prompt.text.toString())
    }

    /**
     * The state is the process's: a recreation keeps what was loaded and does not read verso.json
     * again — only "~restart", the repair by hand, gives a fresh state that does.
     */
    @Test fun `a recreation keeps the loaded config, ~restart reads it again`() {
        started()
        val loaded = app.core.state
        // Changed behind the launcher's back, as a hand edit of verso.json would.
        File(activity.filesDir, "verso.json").writeText(ConfigJson.serialize(VersoConfig(favorites = listOf(someApp.key))))
        controller.recreate()
        settle()
        assertSame("one state for both instances", loaded, app.core.state)
        assertFalse("not read again", someApp.key in app.core.state.config.favorites)
        enter("~restart")
        settle()
        assertNotSame("a fresh state", loaded, app.core.state)
        assertTrue("read again", someApp.key in app.core.state.config.favorites)
    }


    /** A pick confirmed while a command runs waits for it, then is adopted. */
    @Test fun `a pick confirmed while a command runs is adopted once it ends`() {
        started()
        pick("verso.json", hanging(configWith(someApp)))
        // A latch of its own: the read must land while the backup's step still waits.
        app.io.execute { ioHold.await(30, TimeUnit.SECONDS) }
        enter("~backup")                        // runs while the pick is read
        hold.countDown()
        joinReads()
        shadowOf(Looper.getMainLooper()).idle() // the read lands: asked about, gate or not
        answerRestore()
        assertFalse("not adopted while the backup runs", someApp.key in savedFavorites())
        ShadowToast.reset()
        ioHold.countDown()
        settle()                                // the backup ends, the adoption runs
        assertTrue("adopted after it", someApp.key in savedFavorites())
        assertRestoredToast()
    }

    /**
     * A picker's answer can reach an instance whose first load has not landed (the process was
     * killed while the picker was open): the pick waits for the load instead of being refused, and
     * is asked about only then — a load landing under an open dialog would void it.
     */
    @Test fun `a pick read before the first load is asked about once it lands`() {
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
        assertNull("not asked before the load", restoreDialog())
        hold.countDown()
        settle()
        answerRestore()
        settle()
        assertTrue("adopted once loaded and confirmed", otherApp.key in savedFavorites())
        assertRestoredToast()
    }

    /**
     * The pick is the process's: a recreation while a confirmed pick waits for a run hands it to the
     * new instance, which adopts it when the run ends.
     */
    @Test fun `a confirmed pick survives a recreation and is adopted`() {
        started()
        pick("verso.json", hanging(configWith(someApp)))
        app.io.execute { ioHold.await(30, TimeUnit.SECONDS) }
        enter("~backup")
        hold.countDown()
        joinReads()
        shadowOf(Looper.getMainLooper()).idle()
        answerRestore()                         // confirmed, waiting for the backup
        controller.recreate()                   // the backup's hand-back goes to the process
        shadowOf(Looper.getMainLooper()).idle()
        ShadowToast.reset()
        ioHold.countDown()
        settle()                                // the step, its hand-back ending the run, the new load
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

    /**
     * Hard rule 16: a run's hand-back is the process's. The system may destroy the window while the
     * process lives, with no successor yet: the run still ends and its result waits for the next
     * window. (controller.recreate is synchronous; only a destroy without a successor makes the gap.)
     */
    @Test fun `a run ends and its result waits while no window exists`() {
        started()
        holdIo()
        enter("~backup")
        controller.pause().stop().destroy()
        hold.countDown()
        settle()
        assertFalse("the run ended without a window", app.gate.busy)
        assertEquals("its result waits", 1, app.process.messages.size)
        ShadowToast.reset()
        val next = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            assertTrue("shown by the next window", app.process.messages.isEmpty())
            assertEquals(1, ShadowToast.shownToastCount())
        } finally {
            next.pause().stop().destroy()
        }
    }

    /**
     * The state's hand-backs are the process's too: a load landing while no window exists still
     * loads. A confirmed ~cleanup reports back the same way — dropped, it would hold the gate for good.
     */
    @Test fun `the state loads while no window exists`() {
        holdIo()                                // the first load queues behind the hold
        controller.setup()
        controller.pause().stop().destroy()
        hold.countDown()
        settle()
        assertTrue(app.core.state.configLoaded)
    }

    /**
     * After ~restart the process's state is already the fresh one, but the recreation is only queued:
     * this window would check a command against its old, loaded state and run it on the new one.
     */
    @Test fun `the window ~restart replaces takes no command before the recreation lands`() {
        started()
        enter("~restart")                       // the recreation is queued; the looper does not run
        enter("~backup")
        assertEquals("not taken, left for the new window", "~backup", prompt.text.toString())
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

    private val cleanupAnswers get() = listOf(
        R.string.toast_cleanup_not_run, R.string.toast_cleanup_incomplete, R.string.toast_cleanup_none,
    ).map(activity::getString)

    /** The control for the two below: unanswered by a page turn, the check says what it found. */
    @Test fun `a cleanup check answers the page that asked`() {
        started()
        holdIo()
        enter("~cleanup")
        ShadowToast.reset()
        hold.countDown()
        settle()
        val shown = ShadowToast.getTextOfLatestToast()
        assertTrue("expected one of $cleanupAnswers, got \"$shown\"", shown in cleanupAnswers)
    }

    @Test fun `a cleanup check answers no one once HOME turned the page`() {
        started()
        holdIo()
        enter("~cleanup")
        controller.newIntent(home)
        ShadowToast.reset()
        hold.countDown()
        settle()
        assertNull(ShadowToast.getLatestToast())
    }

    @Test fun `a cleanup check answers no window made since`() {
        started()
        holdIo()
        enter("~cleanup")
        controller.recreate()
        shadowOf(Looper.getMainLooper()).idle()
        ShadowToast.reset()
        hold.countDown()
        settle()
        assertNull(ShadowToast.getLatestToast())
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
