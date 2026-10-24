package com.github.reygnn.guru

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.TransactionTooLargeException
import android.view.ViewGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowDialog

/** A hold on the alert: the files this start saved, offered in the share chooser. */
@RunWith(RobolectricTestRunner::class)
class GuruShareTest {

    @get:Rule val host = GuruHostRule()

    private val a = "0b6c2f8e-1d2a-4c3b-9e8f-00000000000a"
    private val b = "0b6c2f8e-1d2a-4c3b-9e8f-00000000000b"

    private lateinit var media: FakeMediaStore

    @Before
    fun setUp() {
        media = FakeMediaStore.install()
    }

    /** An activity the system refuses to start anything for, as with a chooser it cannot show. */
    class RefusingActivity : Activity() {
        // what the system throws there: a plain RuntimeException around the Binder's refusal
        @Suppress("TooGenericExceptionThrown")
        override fun startActivity(intent: Intent?) {
            throw RuntimeException("Failure from system", TransactionTooLargeException("data parcel size 1234567 bytes"))
        }
    }

    /** A start, its alert held: the chooser started, if any. */
    private fun hold(activity: Activity = host.activity().get()): Intent? {
        Guru.install(host.app, host.io)
        host.store.markExitsSeen(System.currentTimeMillis() - GuruHostRule.MINUTE_MS)
        Guru.showPending(activity) { it() }
        host.runIo()
        ShadowDialog.getLatestDialog().findViewById<ViewGroup>(android.R.id.content).getChildAt(0).performLongClick()
        return shadowOf(activity).nextStartedActivity
    }

    private fun Intent.offered(): Intent = getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)!!

    private fun Intent.clipUris(): List<Uri> = (0 until clipData!!.itemCount).map { clipData!!.getItemAt(it).uri }

    @Test
    fun `hold offers the saved file with read access, in a task of its own`() {
        val report = host.crashed(a)

        val chooser = hold()!!

        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        assertTrue(chooser.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        val send = chooser.offered()
        val file = media.rows.single().uri
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("application/json", send.type)
        assertEquals(file, send.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
        assertEquals(listOf(file), send.clipUris())
        assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(0, send.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        assertEquals(GuruText.subject(report), send.getStringExtra(Intent.EXTRA_SUBJECT))
    }

    @Test
    fun `the chooser itself may read the files - for its preview`() {
        host.crashed(a)

        val chooser = hold()!!

        assertEquals(listOf(media.rows.single().uri), chooser.clipUris())
        assertTrue(chooser.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
    }

    @Test
    fun `several files are offered together, under the newest's subject`() {
        report(id = a, crashedAt = System.currentTimeMillis() - 2 * GuruHostRule.MINUTE_MS,
            trace = "java.lang.IllegalArgumentException: old\n\tat com.example.app.Old.run(Old.kt:1)").also { host.store.write(it) }
        val newer = host.crashed(b)

        val chooser = hold()!!
        val send = chooser.offered()

        val files = media.rows.map { it.uri }
        assertEquals(Intent.ACTION_SEND_MULTIPLE, send.action)
        assertEquals(files, send.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java))
        assertEquals(files, send.clipUris())
        assertEquals(files, chooser.clipUris())
        assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(GuruText.subject(newer), send.getStringExtra(Intent.EXTRA_SUBJECT))
    }

    @Test
    fun `only what was saved is offered`() {
        host.crashed(a, at = System.currentTimeMillis() - 2 * GuruHostRule.MINUTE_MS)
        host.crashed(b)
        media.refuse = setOf(1)

        val send = hold()!!.offered()

        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals(media.rows.single().uri, send.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
    }

    @Test
    fun `a chooser that cannot start costs nothing`() {
        host.crashed(a)
        val refusing = Robolectric.buildActivity(RefusingActivity::class.java).setup().get()

        hold(refusing)

        assertEquals(1, media.rows.size)
        assertTrue(host.store.pending().isEmpty())
    }
}
