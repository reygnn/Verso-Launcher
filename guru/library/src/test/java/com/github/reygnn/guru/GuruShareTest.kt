package com.github.reygnn.guru

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.IntentSender
import android.service.chooser.ChooserResult
import android.view.ViewGroup
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.fakes.RoboIntentSender
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowToast

@RunWith(RobolectricTestRunner::class)
class GuruShareTest {

    @get:Rule val host = GuruHostRule()

    private val a = "0b6c2f8e-1d2a-4c3b-9e8f-00000000000a"
    private val b = "0b6c2f8e-1d2a-4c3b-9e8f-00000000000b"

    private lateinit var activity: Activity

    /** The alert for what is pending now, held: the chooser Guru started. */
    private fun hold(sink: GuruSink.Share = GuruSink.Share()): Intent {
        Guru.install(host.app, sink, host.io)
        host.store.markExitsSeen(System.currentTimeMillis() - GuruHostRule.MINUTE_MS)
        activity = host.activity().get()
        Guru.showPending(activity) { it() }
        host.runIo()
        val view = ShadowDialog.getLatestDialog().findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        view.performLongClick()
        return shadowOf(activity).nextStartedActivity
    }

    // Android 15+ files createChooser's sender under the chooser-result key.
    private fun Intent.callback(): IntentSender =
        getParcelableExtra(Intent.EXTRA_CHOOSER_RESULT_INTENT_SENDER, IntentSender::class.java)!!

    /**
     * The chooser answering, as Android 15+ does: the system fills [type] in as a ChooserResult;
     * null answers without one. ChooserResult's constructor is hidden, hence the reflection.
     */
    private fun answer(chooser: Intent, type: Int?) {
        val fillIn = Intent()
        if (type != null) {
            val result = ChooserResult::class.java
                .getConstructor(Int::class.java, ComponentName::class.java, Boolean::class.java)
                .newInstance(type, ComponentName("com.example.mail", "com.example.mail.Compose"), false)
            fillIn.putExtra(Intent.EXTRA_CHOOSER_RESULT, result)
        }
        (chooser.callback() as RoboIntentSender).pendingIntent.send(host.app, 0, fillIn)
        host.idleMain()
    }

    private fun pick(chooser: Intent) = answer(chooser, ChooserResult.CHOOSER_RESULT_SELECTED_COMPONENT)

    /** A chooser answer that is no app picked: everything stays, nothing queued. */
    private fun assertKeptAfter(type: Int?) {
        host.crashed(a)
        val chooser = hold()

        answer(chooser, type)

        assertEquals(0, host.queuedIo())
        host.runIo()
        assertEquals(listOf(a), host.store.pending().map { it.id })
    }

    @Test
    fun `hold starts the chooser with the reports as text`() {
        val report = host.crashed(a)

        val chooser = hold(GuruSink.Share(mailTo = "crash@example.org"))

        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        val send = chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)!!
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("text/plain", send.type)
        assertEquals(GuruText.subject(report), send.getStringExtra(Intent.EXTRA_SUBJECT))
        assertTrue(send.getStringExtra(Intent.EXTRA_TEXT)!!.contains("\"REPORT_ID\":\"$a\""))
        assertArrayEquals(arrayOf("crash@example.org"), send.getStringArrayExtra(Intent.EXTRA_EMAIL))
        assertNotNull(chooser.callback())
    }

    @Test
    fun `a picked app removes exactly the reports handed over`() {
        host.crashed(a)
        val chooser = hold()
        host.crashed(b)

        pick(chooser)
        host.runIo()

        assertEquals(listOf(b), host.store.pending().map { it.id })
    }

    @Test
    fun `the removal waits for the host's io`() {
        host.crashed(a)
        val chooser = hold()

        pick(chooser)

        assertEquals(1, host.queuedIo())
        assertEquals(listOf(a), host.store.pending().map { it.id })
    }

    @Test
    fun `Copy answers but keeps everything`() = assertKeptAfter(ChooserResult.CHOOSER_RESULT_COPY)

    @Test
    fun `Edit answers but keeps everything`() = assertKeptAfter(ChooserResult.CHOOSER_RESULT_EDIT)

    @Test
    fun `an unknown result keeps everything`() = assertKeptAfter(ChooserResult.CHOOSER_RESULT_UNKNOWN)

    @Test
    fun `an answer without a result keeps everything`() = assertKeptAfter(null)

    @Test
    fun `the callback takes the system's fill-in`() {
        host.crashed(a)
        val chooser = hold()

        assertFalse((chooser.callback() as RoboIntentSender).pendingIntent.isImmutable)
    }

    @Test
    fun `no answer from the chooser keeps everything`() {
        host.crashed(a)
        hold()

        host.idleMain()
        host.runIo()

        assertEquals(listOf(a), host.store.pending().map { it.id })
    }

    @Test
    fun `no app to share with keeps the reports and says so`() {
        host.crashed(a)
        Guru.install(host.app, GuruSink.Share(), host.io)
        host.store.markExitsSeen(System.currentTimeMillis() - GuruHostRule.MINUTE_MS)
        activity = host.activity().get()
        shadowOf(host.app).checkActivities(true)
        Guru.showPending(activity) { it() }
        host.runIo()

        ShadowDialog.getLatestDialog().findViewById<ViewGroup>(android.R.id.content).getChildAt(0).performLongClick()
        host.idleMain()
        host.runIo()

        assertEquals(listOf(a), host.store.pending().map { it.id })
        assertEquals(GuruTexts.English.sent(0, 1), ShadowToast.getTextOfLatestToast())
    }
}
