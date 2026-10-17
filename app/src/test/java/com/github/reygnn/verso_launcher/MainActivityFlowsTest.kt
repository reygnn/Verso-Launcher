package com.github.reygnn.verso_launcher

import android.content.Intent
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.inputmethod.EditorInfo
import android.widget.EditText
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
import org.robolectric.shadows.ShadowSystemClock
import org.robolectric.shadows.ShadowToast
import java.io.File
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A few of MainActivity's flows on the real Activity — only what needs no LauncherApps or
 * MediaStore fakes (Robolectric models both too thinly to test anything but the fake): a
 * second command and an edit refused while one runs, no edit before the first load, a late
 * HOME leaving a fresh swipe alone. The process's IO thread is real; the tests drain it, or
 * hold it with a latch to keep a command running.
 */
@RunWith(RobolectricTestRunner::class)
class MainActivityFlowsTest {

    private val app = RuntimeEnvironment.getApplication() as VersoApp
    private val controller = Robolectric.buildActivity(MainActivity::class.java)
    private val activity: MainActivity get() = controller.get()
    private val hold = CountDownLatch(1)

    @After fun release() = hold.countDown()

    /** Let the IO thread and the main looper run until both are quiet. */
    private fun settle() = repeat(4) {
        app.io.submit {}.get(5, TimeUnit.SECONDS)
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** Hold the IO thread: whatever is queued after this waits — a command stays running. */
    private fun holdIo() = app.io.execute { hold.await(5, TimeUnit.SECONDS) }

    private fun started(): MainActivity = controller.setup().get().also { settle() }

    private val prompt: EditText get() = activity.window.decorView.collect<EditText>().single()
    private val root: SwipeFrame get() = activity.window.decorView.collect<SwipeFrame>().single()

    /** Type [text] into the command line and press Enter (a soft keyboard's GO). */
    private fun enter(text: String) {
        prompt.setText(text)
        prompt.onEditorAction(EditorInfo.IME_ACTION_GO)
    }

    /** A tap in an edit mode, as AppDrawer hands it on (no LauncherApps, so no rows to tap). */
    private fun tap(action: TapAction<AppEntry>) = activity.onDrawerTap(action)

    private val someApp = AppEntry.of("Zulu", "com.zulu", "com.zulu.Main", "Zulu")
    /** The favorites verso.json holds — none while nothing was saved (a fresh install with no
     *  apps to seed). */
    private fun savedFavorites(): Set<String> =
        File(activity.filesDir, "verso.json").takeIf { it.isFile }?.let { ConfigJson.parse(it.readText())?.favorites }.orEmpty()

    @Test fun `a second command while one runs is refused and stays in the prompt`() {
        started()
        holdIo()
        enter("~backup")                       // its IO step waits behind the hold: running
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

        tap(TapAction.ToggleFavorite(someApp))  // the run has ended
        settle()
        assertTrue(someApp.key in savedFavorites())
    }

    @Test fun `no edit before the first load`() {
        holdIo()                                // the first load queues behind the hold
        controller.setup()
        tap(TapAction.ToggleFavorite(someApp))
        assertEquals(activity.getString(R.string.toast_not_loaded), ShadowToast.getTextOfLatestToast())
    }

    /** Pull the home page up far enough that the page drag has started. */
    private fun startSwipeUp() {
        val t = SystemClock.uptimeMillis()
        root.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, 500f, 1500f, 0))
        for (i in 1..10) {
            root.dispatchTouchEvent(MotionEvent.obtain(t, t + i * 16, MotionEvent.ACTION_MOVE, 500f, 1500f - i * 60f, 0))
        }
    }

    private val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)

    @Test fun `a late HOME right after coming back leaves a fresh swipe alone`() {
        started()
        startSwipeUp()
        assertTrue("the drag has started", root.isDragging)
        controller.newIntent(home)
        assertTrue("HOME within the late window keeps the drag", root.isDragging)
    }

    @Test fun `a HOME long after coming back still ends a swipe`() {
        started()
        ShadowSystemClock.advanceBy(Duration.ofMillis(SwipeGesture.LATE_HOME_MS + 500))
        startSwipeUp()
        assertTrue("the drag has started", root.isDragging)
        controller.newIntent(home)
        assertFalse(root.isDragging)
    }
}
