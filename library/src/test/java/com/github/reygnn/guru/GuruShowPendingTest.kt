package com.github.reygnn.guru

import android.app.Dialog
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowDialog

/** The alert: only what this start saved, once; it decides nothing. */
@RunWith(RobolectricTestRunner::class)
class GuruShowPendingTest {

    @get:Rule val host = GuruHostRule()

    private val a = "0b6c2f8e-1d2a-4c3b-9e8f-00000000000a"
    private val b = "0b6c2f8e-1d2a-4c3b-9e8f-00000000000b"

    private lateinit var media: FakeMediaStore

    @Before
    fun setUp() {
        media = FakeMediaStore.install()
    }

    /** install, the exit history marked; its io steps still queued. */
    private fun installed() {
        Guru.install(host.app, host.io)
        host.store.markExitsSeen(System.currentTimeMillis() - GuruHostRule.MINUTE_MS)
    }

    private fun Dialog.guruView(): View = (findViewById<ViewGroup>(android.R.id.content)).getChildAt(0)

    private fun shown(): Dialog {
        installed()
        Guru.showPending(host.activity().get()) { it() }
        host.runIo()
        return ShadowDialog.getLatestDialog()
    }

    @Test
    fun `nothing saved shows nothing`() {
        installed()
        Guru.showPending(host.activity().get()) { it() }
        host.runIo()

        assertNull(ShadowDialog.getLatestDialog())
    }

    @Test
    fun `nothing saved queues nothing for the host's main`() {
        installed()
        val onUi = mutableListOf<() -> Unit>()
        Guru.showPending(host.activity().get()) { onUi += it }

        host.runIo()

        assertTrue(onUi.isEmpty())
    }

    @Test
    fun `a report not saved queues nothing either`() {
        host.crashed(a)
        media.refuse = setOf(1)
        installed()
        val onUi = mutableListOf<() -> Unit>()
        Guru.showPending(host.activity().get()) { onUi += it }

        host.runIo()

        assertTrue(onUi.isEmpty())
    }

    @Test
    fun `a report not saved is not shown - it waits for the next start`() {
        host.crashed(a)
        media.refuse = setOf(1)
        installed()
        Guru.showPending(host.activity().get()) { it() }

        host.runIo()

        assertNull(ShadowDialog.getLatestDialog())
        assertEquals(listOf(a), host.store.pending().map { it.id })
    }

    @Test
    fun `the alert comes after the save, on the host's main`() {
        host.crashed(a)
        installed()
        val onUi = mutableListOf<() -> Unit>()
        Guru.showPending(host.activity().get()) { onUi += it }

        assertEquals(3, host.queuedIo())
        host.runIo()
        assertEquals(1, media.rows.size)
        assertNull(ShadowDialog.getLatestDialog())

        onUi.single()()
        assertTrue(ShadowDialog.getLatestDialog().isShowing)
    }

    @Test
    fun `the alert shows the newest report's number and how many were saved`() {
        val older = report(id = a, crashedAt = System.currentTimeMillis() - 2 * GuruHostRule.MINUTE_MS,
            trace = "java.lang.IllegalArgumentException: old\n\tat com.example.app.Old.run(Old.kt:1)").also { host.store.write(it) }
        val newest = host.crashed(b)

        val said = shown().guruView().contentDescription.toString()

        assertTrue(older.number != newest.number)
        assertTrue(said, said.contains("Guru Meditation ${newest.number}"))
        assertFalse(said, said.contains(older.number))
        assertTrue(said, said.contains(GuruTexts.English.saved(2)))
    }

    @Test
    fun `the alert comes once - a second call shows nothing`() {
        host.crashed(a)
        shown().dismiss()

        Guru.showPending(host.activity().get()) { it() }
        host.runIo()

        assertFalse(ShadowDialog.getLatestDialog().isShowing)
        assertEquals(1, ShadowDialog.getShownDialogs().size)
    }

    @Test
    fun `a double-tap closes it, and that is all`() {
        host.crashed(a)
        val dialog = shown()

        dialog.guruView().performClick()
        host.idleMain()
        host.runIo()

        assertFalse(dialog.isShowing)
        assertNull(host.store.alertUp())
        assertEquals(1, media.rows.size)
        assertNull(shadowOf(host.activity().get()).nextStartedActivity)
    }

    @Test
    fun `Back closes it`() {
        host.crashed(a)
        val dialog = shown()

        host.idleMain()
        dialog.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK))
        dialog.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK))
        host.idleMain()
        host.runIo()

        assertFalse(dialog.isShowing)
        assertNull(host.store.alertUp())
        assertEquals(1, media.rows.size)
    }

    @Test
    fun `a destroyed activity gets no alert, the file is saved anyway`() {
        host.crashed(a)
        installed()
        val controller = host.activity()
        val onUi = mutableListOf<() -> Unit>()
        Guru.showPending(controller.get()) { onUi += it }
        host.runIo()

        controller.pause().stop().destroy()
        onUi.single()()

        assertNull(ShadowDialog.getLatestDialog())
        assertEquals(1, media.rows.size)
        assertTrue(host.store.pending().isEmpty())
    }

    @Test
    fun `a finishing activity gets no alert`() {
        host.crashed(a)
        installed()
        val activity = host.activity().get()
        val onUi = mutableListOf<() -> Unit>()
        Guru.showPending(activity) { onUi += it }
        host.runIo()

        activity.finish()
        onUi.single()()
        host.runIo()

        assertNull(ShadowDialog.getLatestDialog())
        assertNull(host.store.alertUp())
    }

    @Test
    fun `a start without any alert still saves`() {
        host.crashed(a)

        installed()
        host.runIo()

        assertEquals(1, media.rows.size)
        assertTrue(host.store.pending().isEmpty())
    }

    // ---- an alert that cannot be shown -------------------------------------------

    @Test
    fun `host texts that throw cost the alert, not the host`() {
        host.crashed(a)
        val broken = object : GuruTexts {
            override fun saved(count: Int): String = throw IllegalStateException("string resource gone")
        }
        Guru.install(host.app, host.io, broken)
        host.store.markExitsSeen(System.currentTimeMillis() - GuruHostRule.MINUTE_MS)
        val activity = host.activity().get()
        Guru.showPending(activity) { activity.mainExecutor.execute(it) }

        host.runIo()
        host.idleMain()
        host.runIo()

        assertNull(ShadowDialog.getLatestDialog())
        assertEquals(1, media.rows.size)
        assertNull(host.store.alertUp())
    }

    @Test
    fun `the alert's own crash leaves the next alert out`() {
        host.store.write(report(id = a, thread = "main",
            trace = "java.lang.IllegalStateException: draw\n\tat com.github.reygnn.guru.GuruView.onDraw(GuruScreen.kt:1)"))
        installed()
        Guru.showPending(host.activity().get()) { it() }

        host.runIo()

        assertNull(ShadowDialog.getLatestDialog())
        assertEquals(1, media.rows.size)
    }

    @Test
    fun `a crash below Guru's package, or off main, still gets its alert`() {
        host.store.write(report(id = a, thread = "main",
            trace = "java.lang.IllegalStateException: boom\n\tat com.github.reygnn.guru.sample.MainActivity.onCreate(MainActivity.kt:1)"))
        host.store.write(report(id = b, crashedAt = System.currentTimeMillis(), thread = "io",
            trace = "java.lang.IllegalStateException: io\n\tat com.github.reygnn.guru.Guru.save(Guru.kt:1)"))

        assertTrue(shown().isShowing)
    }

    // ---- a process that died with its alert up (Verso's audit 2026-10-24, B.10) ----

    /** The next "start": a new install over the same store, as after a process death. */
    private fun nextStart(): Dialog? {
        installed()
        Guru.showPending(host.activity().get()) { it() }
        host.runIo()
        return ShadowDialog.getLatestDialog()
    }

    @Test
    fun `the alert is marked while it is up`() {
        host.crashed(a)
        shown()

        assertTrue(host.store.alertUp() != null)
    }

    /** A native crash or an ANR caused by the alert: diedInGuru cannot see it. */
    @Test
    fun `a process that died with its alert up gets none at the next start, the file is saved`() {
        host.crashed(a)
        val first = shown()
        host.crashed(b, at = System.currentTimeMillis())

        assertTrue(nextStart() === first)
        assertEquals(1, ShadowDialog.getShownDialogs().size)
        assertEquals(2, media.rows.size)
        assertNull(host.store.alertUp())
    }

    @Test
    fun `the start after that shows its alert again`() {
        host.crashed(a)
        shown()
        host.crashed(b, at = System.currentTimeMillis())
        nextStart()
        host.crashed("0b6c2f8e-1d2a-4c3b-9e8f-00000000000c", at = System.currentTimeMillis())

        assertTrue(nextStart()!!.isShowing)
        assertEquals(2, ShadowDialog.getShownDialogs().size)
    }

    @Test
    fun `a mark left with nothing new is cleared, and the next report gets its alert`() {
        host.crashed(a)
        shown()
        nextStart()
        assertNull(host.store.alertUp())
        host.crashed(b, at = System.currentTimeMillis())

        assertTrue(nextStart()!!.isShowing)
        assertEquals(2, ShadowDialog.getShownDialogs().size)
    }

    @Test
    fun `a closed alert leaves the next start's alert in`() {
        host.crashed(a)
        shown().guruView().performClick()
        host.idleMain()
        host.runIo()
        host.crashed(b, at = System.currentTimeMillis())

        assertTrue(nextStart()!!.isShowing)
        assertEquals(2, ShadowDialog.getShownDialogs().size)
    }

    /** The old alert's dismissal comes after the new one is up: it must not clear the new mark. */
    @Test
    fun `an alert lost to a recreate leaves the new alert's mark`() {
        host.crashed(a)
        installed()
        val controller = host.activity()
        Guru.showPending(controller.get()) { it() }
        host.runIo()
        controller.pause().stop().destroy()
        Guru.showPending(host.activity().get()) { it() }
        host.runIo()

        host.idleMain()
        host.runIo()

        assertTrue(ShadowDialog.getLatestDialog().isShowing)
        assertTrue(host.store.alertUp() != null)
    }

    // ---- an alert lost before it was answered ------------------------------------

    @Test
    fun `an activity gone before the alert passes it to the next call`() {
        host.crashed(a)
        installed()
        val controller = host.activity()
        val onUi = mutableListOf<() -> Unit>()
        Guru.showPending(controller.get()) { onUi += it }
        host.runIo()
        controller.pause().stop().destroy()
        onUi.single()()

        Guru.showPending(host.activity().get()) { it() }
        host.runIo()

        assertTrue(ShadowDialog.getLatestDialog().isShowing)
    }

    @Test
    fun `an alert lost to a recreate comes back`() {
        host.crashed(a)
        installed()
        val controller = host.activity()
        Guru.showPending(controller.get()) { it() }
        host.runIo()
        val first = ShadowDialog.getLatestDialog()

        controller.pause().stop().destroy()
        Guru.showPending(host.activity().get()) { it() }
        host.runIo()

        assertFalse(first.isShowing)
        val again = ShadowDialog.getLatestDialog()
        assertTrue(again !== first && again.isShowing)
    }

    @Test
    fun `an answered alert does not come back`() {
        host.crashed(a)
        installed()
        val controller = host.activity()
        Guru.showPending(controller.get()) { it() }
        host.runIo()
        val first = ShadowDialog.getLatestDialog()
        first.guruView().performClick()

        controller.pause().stop().destroy()
        Guru.showPending(host.activity().get()) { it() }
        host.runIo()

        assertTrue(ShadowDialog.getLatestDialog() === first)
    }

    // ---- the activity at io time, and which report decides ------------------------

    /** As Verso's onUi does: a block for a destroyed activity is dropped, never run. */
    @Test
    fun `an activity recreated before its io turn leaves the alert to the new one`() {
        host.crashed(a)
        installed()
        val old = host.activity()
        Guru.showPending(old.get()) { block -> if (!old.get().isDestroyed) block() }
        old.pause().stop().destroy()
        val recreated = host.activity().get()
        Guru.showPending(recreated) { it() }

        host.runIo()

        assertTrue(ShadowDialog.getLatestDialog().isShowing)
    }

    @Test
    fun `only the newest report decides whether Guru died - newest Guru's own`() {
        host.store.write(report(id = a, crashedAt = System.currentTimeMillis() - 2 * GuruHostRule.MINUTE_MS))
        host.store.write(report(id = b, crashedAt = System.currentTimeMillis(), thread = "main",
            trace = "java.lang.IllegalStateException: draw\n\tat com.github.reygnn.guru.GuruView.onDraw(GuruScreen.kt:1)"))
        installed()
        Guru.showPending(host.activity().get()) { it() }

        host.runIo()

        assertNull(ShadowDialog.getLatestDialog())
        assertEquals(2, media.rows.size)
    }

    @Test
    fun `only the newest report decides whether Guru died - an older one Guru's own`() {
        host.store.write(report(id = a, crashedAt = System.currentTimeMillis() - 2 * GuruHostRule.MINUTE_MS, thread = "main",
            trace = "java.lang.IllegalStateException: draw\n\tat com.github.reygnn.guru.GuruView.onDraw(GuruScreen.kt:1)"))
        host.store.write(report(id = b, crashedAt = System.currentTimeMillis()))

        assertTrue(shown().isShowing)
    }

    @Test
    fun `an activity finishing before its io turn leaves the alert to the next call`() {
        host.crashed(a)
        installed()
        val finishing = host.activity().get()
        Guru.showPending(finishing) { block -> if (!finishing.isFinishing) block() }
        finishing.finish()
        Guru.showPending(host.activity().get()) { it() }

        host.runIo()

        assertTrue(ShadowDialog.getLatestDialog().isShowing)
    }
}
