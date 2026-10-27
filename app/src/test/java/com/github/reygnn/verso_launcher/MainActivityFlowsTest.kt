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
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Only flows that need no LauncherApps fake (Robolectric models it too thinly). The process's IO
 * thread is real: the tests drain it, or hold it with a latch to keep the first load waiting. A
 * picked document is a stream registered with Robolectric's ContentResolver; one that hangs waits
 * for [hold].
 */
@RunWith(RobolectricTestRunner::class)
class MainActivityFlowsTest {

    private val app = RuntimeEnvironment.getApplication() as VersoApp
    private val controller = Robolectric.buildActivity(MainActivity::class.java)
    private val activity: MainActivity get() = controller.get()
    private val hold = CountDownLatch(1)

    @After fun release() {
        hold.countDown()
        // Else a read's or a command's hand-back lands on the main looper after the test, in the next one.
        joinReads()
        settle()
        if (!activity.isDestroyed) controller.pause().stop().destroy()
    }

    private fun settle() = repeat(4) {
        app.io.submit {}.get(5, TimeUnit.SECONDS)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun reads() = Thread.getAllStackTraces().keys.filter { it.name == "verso-restore-read" || it.name == "verso-backup-write" }

    /** A picked document is read or written on a thread of its own. */
    private fun joinReads(threads: Collection<Thread> = reads()) = threads.forEach {
        it.join(5000)
        check(!it.isAlive) { "a document read or write is still running" }
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

    /** A "~restore" whose picker answered with [content]. */
    private fun pick(name: String, content: InputStream) {
        val picked = Uri.parse("content://cloud/$name")
        shadowOf(activity.contentResolver).registerInputStream(picked, content)
        enter("~restore")
        val request = shadowOf(activity).nextStartedActivityForResult
        shadowOf(activity).receiveResult(request.intent, Activity.RESULT_OK, Intent().setData(picked))
    }

    /** A "~backup" whose picker answered with a new document that [out] takes; returns the picker's request. */
    private fun backUp(out: OutputStream): Intent {
        val target = Uri.parse("content://cloud/backup.json")
        shadowOf(activity.contentResolver).registerOutputStream(target, out)
        enter("~backup")
        val request = shadowOf(activity).nextStartedActivityForResult
        shadowOf(activity).receiveResult(request.intent, Activity.RESULT_OK, Intent().setData(target))
        return request.intent
    }

    /** A provider that hangs while it takes the file: the write waits for the hold. */
    private fun hangingOut(into: OutputStream) = object : OutputStream() {
        override fun write(b: Int) {
            check(hold.await(30, TimeUnit.SECONDS))
            into.write(b)
        }
    }

    /** Whatever is queued after this waits: the first load, a save. */
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

    /** SharedPreferences' apply is on disk once onStop is through (QueuedWork); so is a change here. */
    @Test fun `a change the IO thread has not written yet is on disk once the stop is through`() {
        started()
        holdIo()
        tap(DrawerAction.ToggleFavorite(someApp))
        assertFalse("the save waits behind the hold", someApp.key in savedFavorites())
        controller.pause().stop()
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

    /** Hard rule 5: the config is read before the window exists, so an edit never waits for a load. */
    @Test fun `an edit while the first app list is still loading is saved`() {
        File(app.filesDir, "verso.json").writeText(ConfigJson.serialize(VersoConfig(favorites = listOf(otherApp.key))))
        holdIo()                                // the first app list queues behind the hold
        controller.setup()
        tap(DrawerAction.ToggleFavorite(someApp))
        hold.countDown()
        settle()
        assertEquals("added to the config read at the start", listOf(otherApp.key, someApp.key), savedFavorites())
    }

    /**
     * Before the first app list home shows the cached favorites, whose label is the custom name: a
     * rename dialog there would offer it as the app's own, and an unchanged OK would drop it.
     */
    @Test fun `no rename dialog before the first app list`() {
        File(app.filesDir, "verso.json").writeText(
            ConfigJson.serialize(VersoConfig(favorites = listOf(someApp.key), names = mapOf(someApp.key to "Mine"))),
        )
        holdIo()                                // the first app list queues behind the hold
        controller.setup()
        activity.promptRename(someApp.copy(label = "Mine", systemLabel = "Mine"))
        assertFalse(ShadowDialog.getLatestDialog()?.isShowing == true)
        hold.countDown()
        settle()
        assertEquals("Mine", app.core.state.config.names[someApp.key])
    }

    /** With the IO thread held too: a deliberate crash waits for nothing. */
    @Test fun `~crash throws at once, even with the IO thread held`() {
        holdIo()
        controller.setup()
        val thrown = runCatching { enter("~crash") }.exceptionOrNull()
        assertEquals(DailyDriver.ENABLED, thrown is IllegalStateException)
    }

    @Test fun `~undo with no undo point says so and changes nothing`() {
        started()
        tap(DrawerAction.ToggleFavorite(someApp))
        settle()
        enter("~undo")
        settle()
        assertEquals(activity.getString(R.string.toast_undo_none), ShadowToast.getTextOfLatestToast())
        assertEquals(listOf(someApp.key), savedFavorites())
    }

    /** Hard rule 4 loads it as empty all the same; the toast is what Core wires to the state. */
    @Test fun `an unreadable verso json is said at the start, and the next change writes over it`() {
        File(app.filesDir, "verso.json").writeText("{ torn")
        started()
        assertEquals(activity.getString(R.string.toast_config_unreadable), ShadowToast.getTextOfLatestToast())
        tap(DrawerAction.ToggleFavorite(someApp))
        settle()
        assertEquals(listOf(someApp.key), savedFavorites())
    }

    @Test fun `~restart while a picked file is read drops the pick and says so`() {
        started()
        pick("verso.json", hanging(configWith(someApp)))
        enter("~backup")
        assertEquals("other commands run meanwhile", "", prompt.text.toString())
        settle()
        enter("~restart")
        val dropped = activity.getString(R.string.toast_restore_dropped)
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
        assertEquals(activity.getString(R.string.toast_restore_dropped), ShadowToast.getTextOfLatestToast())
        settle()
        assertFalse(someApp.key in savedFavorites())
    }

    /**
     * While a pick is read or waits, no second swap starts: the adoption behind it would replace what
     * that one brought. Only "~restore" and "~undo" wait — the other commands (and edits) go on.
     */
    @Test fun `a second restore or an undo while a pick is pending is refused and says why`() {
        started()
        pick("old.json", hanging(configWith(someApp)))
        val pending = activity.getString(R.string.toast_restore_pending)
        enter("~restore")
        assertEquals(pending, ShadowToast.getTextOfLatestToast())
        assertEquals("~restore", prompt.text.toString())
        assertNull("no second picker", shadowOf(activity).nextStartedActivityForResult)
        enter("~undo")
        assertEquals(pending, ShadowToast.getTextOfLatestToast())
        assertEquals("~undo", prompt.text.toString())
        enter("~backup")
        assertEquals("other commands go on", "", prompt.text.toString())
        tap(DrawerAction.ToggleFavorite(otherApp))
        settle()
        assertTrue("and edits", otherApp.key in savedFavorites())
    }

    @Test fun `~restart builds the activity anew`() {
        val before = started()
        enter("~restart")
        settle()
        assertTrue(before.isDestroyed)
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

    @Test fun `~backup offers a stamped name and writes the config to the picked document`() {
        started()
        tap(DrawerAction.ToggleFavorite(someApp))
        settle()
        val written = ByteArrayOutputStream()
        val request = backUp(written)
        joinReads()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, request.action)
        assertTrue(request.getStringExtra(Intent.EXTRA_TITLE).orEmpty().matches(Regex("""verso-\d{4}-\d{2}-\d{2}_\d{6}\.json""")))
        assertEquals(activity.getString(R.string.toast_backup_ok), ShadowToast.getTextOfLatestToast())
        // parseForeign, as "~restore" reads it on any phone.
        assertEquals(listOf(someApp.key), ConfigJson.parseForeign(written.toString(Charsets.UTF_8))?.favorites)
    }

    @Test fun `a backup the provider refuses says so`() {
        started()
        backUp(object : OutputStream() {
            override fun write(b: Int) = throw IOException("full")
        })
        joinReads()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(activity.getString(R.string.toast_backup_failed), ShadowToast.getTextOfLatestToast())
    }

    /** Hard rule 7: no undo point, no restore — not even in memory. */
    @Test fun `a restore whose undo point cannot be kept adopts nothing and says so`() {
        started()
        pick("verso.json", configWith(someApp))
        joinReads()
        shadowOf(Looper.getMainLooper()).idle()
        // A directory where the slot's temp file goes: the write fails, as root too (chmod would not).
        File(activity.filesDir, "${UndoSlot.FILE}.new").mkdirs()
        answerRestore()
        settle()
        assertEquals(activity.getString(R.string.toast_restore_undo_failed), ShadowToast.getTextOfLatestToast())
        assertFalse(someApp.key in app.core.state.config.favorites)
        assertFalse(someApp.key in savedFavorites())
    }

    /** The one undo slot: "~undo" takes the restore back, a second "~undo" redoes it. */
    @Test fun `~undo after a restore takes it back, and a second ~undo redoes it`() {
        started()
        tap(DrawerAction.ToggleFavorite(otherApp))
        settle()
        pick("verso.json", configWith(someApp))
        joinReads()
        shadowOf(Looper.getMainLooper()).idle()
        answerRestore()
        settle()
        assertEquals(listOf(someApp.key), savedFavorites())
        enter("~undo")
        settle()
        assertEquals(activity.getString(R.string.toast_undo_ok), ShadowToast.getTextOfLatestToast())
        assertEquals(listOf(otherApp.key), savedFavorites())
        enter("~undo")
        settle()
        assertEquals(listOf(someApp.key), savedFavorites())
    }

    private fun assertRestoredToast() =
        assertEquals(activity.getString(R.string.toast_restore_ok), ShadowToast.getTextOfLatestToast())

    /** The "~restore" confirmation in front, or null. */
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

    /** The write lands after onStop — no toast over another app, but none lost either. */
    @Test fun `a backup finishing after onStop says so at the next start`() {
        started()
        val written = ByteArrayOutputStream()
        backUp(hangingOut(written))
        controller.pause().stop()
        ShadowToast.reset()
        hold.countDown()
        joinReads()
        settle()
        assertTrue("the backup still happened", written.size() > 0)
        assertNull("no toast over the app now in front", ShadowToast.getLatestToast())
        controller.restart()
        assertEquals(activity.getString(R.string.toast_backup_ok), ShadowToast.getTextOfLatestToast())
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
     * A file another app planted in Download can hide or rename apps: the dialog shows what it
     * sets — zeros too — and Cancel adopts nothing, says nothing, and frees ~restore again.
     */
    @Test fun `the restore dialog shows the file's counts, and Cancel adopts nothing`() {
        started()
        val planted = VersoConfig(hidden = setOf(someApp.key, otherApp.key), names = mapOf(someApp.key to "Bank"))
        pick("planted.json", ConfigJson.serialize(planted).byteInputStream())
        joinReads()
        shadowOf(Looper.getMainLooper()).idle()
        val message = shadowOf(checkNotNull(restoreDialog())).message.toString()
        assertTrue(message, activity.getString(R.string.count_favorites, 0) in message)
        assertTrue(message, activity.getString(R.string.count_hidden, 2) in message)
        assertTrue(message, activity.getString(R.string.count_names, 1) in message)
        ShadowToast.reset()
        answerRestore(ok = false)
        settle()
        assertTrue("nothing hidden", savedConfig()?.hidden.orEmpty().isEmpty())
        assertNull("Cancel says nothing", ShadowToast.getLatestToast())
        enter("~restore")
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
        assertEquals(activity.getString(R.string.toast_restore_dropped), ShadowToast.getTextOfLatestToast())
        settle()
        assertFalse(someApp.key in savedFavorites())
    }

    /**
     * The pick is the process's: a read that lands while a recreation replaced the window is asked
     * about by the new one, and adopted into the state both share.
     */
    @Test fun `a pick read across a recreation is asked about by the new instance and adopted`() {
        started()
        pick("verso.json", hanging(configWith(someApp)))
        val before = activity
        before.recreate()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(before.isDestroyed)
        hold.countDown()
        joinReads()
        shadowOf(Looper.getMainLooper()).idle()
        answerRestore()
        settle()
        assertTrue("written", someApp.key in savedFavorites())
        assertTrue("and in memory", someApp.key in app.core.state.config.favorites)
        assertRestoredToast()
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
     * Hard rule 16: a command's hand-back is the process's. The system may destroy the window while
     * the process lives, with no successor yet: the write still ends and its result waits for the
     * next window. (controller.recreate is synchronous; only a destroy without a successor makes the gap.)
     */
    @Test fun `a backup ends and its result waits while no window exists`() {
        started()
        backUp(hangingOut(ByteArrayOutputStream()))
        controller.pause().stop().destroy()
        hold.countDown()
        joinReads()
        settle()
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
        enter("~backup")
        assertEquals("not taken, left for the new window", "~backup", prompt.text.toString())
    }

    /** The new state reads verso.json at once: a save still queued behind the IO thread is written first. */
    @Test fun `~restart right after an edit keeps the edit`() {
        started()
        holdIo()                                // the edit's save waits behind the hold
        tap(DrawerAction.ToggleFavorite(someApp))
        enter("~restart")
        assertTrue("read by the fresh state", someApp.key in app.core.state.config.favorites)
        hold.countDown()
        settle()
        assertEquals(listOf(someApp.key), savedFavorites())
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
