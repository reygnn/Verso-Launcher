package com.github.reygnn.verso_launcher

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Looper
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.io.InputStream
import java.lang.ref.WeakReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A "~restore-saf" read that never returns (an offline cloud drive) runs on a thread of its own
 * (hard rule 3). It must not keep the activity that started it alive once that one is destroyed.
 * A class of its own: MainActivityFlowsTest's controller holds its activity for the whole test.
 */
@RunWith(RobolectricTestRunner::class)
class RestoreReadLeakTest {

    private val app = RuntimeEnvironment.getApplication() as VersoApp
    private val hold = CountDownLatch(1)

    @After fun release() {
        hold.countDown()
        Thread.getAllStackTraces().keys.filter { it.name == "verso-restore-read" }.forEach { it.join(5000) }
    }

    private fun settle() = repeat(4) {
        app.io.submit {}.get(5, TimeUnit.SECONDS)
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** A provider that hangs: the read waits for the hold. */
    private fun hanging() = object : InputStream() {
        override fun read(): Int {
            check(hold.await(30, TimeUnit.SECONDS))
            return -1
        }
    }

    /** Starts an activity, picks a file whose read hangs, destroys the activity; only a weak reference stays. */
    private fun destroyedWhileReading(): WeakReference<MainActivity> {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        settle()
        val activity = controller.get()
        val picked = Uri.parse("content://cloud/stuck.json")
        shadowOf(activity.contentResolver).registerInputStream(picked, hanging())
        val prompt = activity.window.decorView.collect<EditText>().single()
        prompt.setText("~restore-saf")
        prompt.onEditorAction(EditorInfo.IME_ACTION_GO)
        val request = shadowOf(activity).nextStartedActivityForResult
        shadowOf(activity).receiveResult(request.intent, Activity.RESULT_OK, Intent().setData(picked))
        controller.pause().stop().destroy()
        settle()
        return WeakReference(activity)
    }

    @Test fun `a hanging ~restore-saf read keeps no destroyed activity alive`() {
        val gone = destroyedWhileReading()
        assertTrue("the read is still hanging", Thread.getAllStackTraces().keys.any { it.name == "verso-restore-read" })
        repeat(20) {
            if (gone.get() == null) return@repeat
            System.gc()
            Thread.sleep(50)
        }
        assertNull("the destroyed activity is still reachable", gone.get())
    }
}
