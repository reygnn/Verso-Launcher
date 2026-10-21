package com.github.reygnn.verso_launcher

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.inputmethod.EditorInfo
import android.widget.EditText
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
        joinReads()
    }

    private fun settle() = repeat(4) {
        app.io.submit {}.get(5, TimeUnit.SECONDS)
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** The "~restore-saf" reads run on threads of their own. */
    private fun joinReads() =
        Thread.getAllStackTraces().keys.filter { it.name == "verso-restore-read" }.forEach { it.join(5000) }

    /** A "~restore-saf" whose picker answered with [content]. */
    private fun pick(name: String, content: InputStream) {
        val picked = Uri.parse("content://cloud/$name")
        shadowOf(activity.contentResolver).registerInputStream(picked, content)
        enter("~restore-saf")
        val request = shadowOf(activity).nextStartedActivityForResult
        shadowOf(activity).receiveResult(request.intent, Activity.RESULT_OK, Intent().setData(picked))
    }

    /** Whatever is queued after this waits, so a command stays running. */
    private fun holdIo() = app.io.execute { hold.await(5, TimeUnit.SECONDS) }

    private fun started(): MainActivity = controller.setup().get().also { settle() }

    private val prompt: EditText get() = activity.window.decorView.collect<EditText>().single()
    private val root: SwipeFrame get() = activity.window.decorView.collect<SwipeFrame>().single()

    private fun enter(text: String) {
        prompt.setText(text)
        prompt.onEditorAction(EditorInfo.IME_ACTION_GO)
    }

    /** No LauncherApps, so no rows to tap: the tap as AppDrawer hands it on. */
    private fun tap(action: TapAction<AppEntry>) = activity.onDrawerTap(action)

    private val someApp = AppEntry.of("Zulu", "com.zulu", "com.zulu.Main", "Zulu")
    private fun savedFavorites(): Set<String> =
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
        tap(TapAction.ToggleFavorite(someApp))
        assertEquals(activity.getString(R.string.toast_busy), ShadowToast.getTextOfLatestToast())
        hold.countDown()
        settle()
        assertFalse(someApp.key in savedFavorites())

        tap(TapAction.ToggleFavorite(someApp))
        settle()
        assertTrue(someApp.key in savedFavorites())
    }

    @Test fun `no edit before the first load`() {
        holdIo()                                // the first load queues behind the hold
        controller.setup()
        tap(TapAction.ToggleFavorite(someApp))
        assertEquals(activity.getString(R.string.toast_not_loaded), ShadowToast.getTextOfLatestToast())
    }

    @Test fun `no command but ~restart before the first load`() {
        holdIo()
        controller.setup()
        enter("~backup")
        assertEquals(activity.getString(R.string.toast_not_loaded), ShadowToast.getTextOfLatestToast())
    }

    @Test fun `~restart while a picked file is read drops the pick and says so`() {
        started()
        // A provider that hangs, like an offline cloud drive: the read waits for the hold.
        val config = ConfigJson.serialize(VersoConfig(favorites = linkedSetOf(someApp.key))).byteInputStream()
        pick("verso.json", object : InputStream() {
            override fun read(): Int {
                hold.await(5, TimeUnit.SECONDS)
                return config.read()
            }
        })
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
        pick("verso.json", ConfigJson.serialize(VersoConfig(favorites = linkedSetOf(someApp.key))).byteInputStream())
        joinReads()
        settle()
        assertTrue("restored", someApp.key in savedFavorites())
        assertFalse(dialog.isShowing)
    }

    @Test fun `the first load renders both pages`() {
        started()
        val texts = activity.window.decorView.collect<TextView>().map { it.text.toString() }
        assertTrue(activity.getString(R.string.home_hint) in texts)
        assertTrue(activity.getString(R.string.empty_no_apps) in texts)
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
