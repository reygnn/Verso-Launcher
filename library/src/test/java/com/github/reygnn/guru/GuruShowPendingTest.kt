package com.github.reygnn.guru

import android.app.Dialog
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
class GuruShowPendingTest {

    @get:Rule val host = GuruHostRule()

    private val a = "0b6c2f8e-1d2a-4c3b-9e8f-00000000000a"
    private val b = "0b6c2f8e-1d2a-4c3b-9e8f-00000000000b"

    private fun installed() {
        Guru.install(host.app, GuruSink.Share(), host.io)
        host.store.markExitsSeen(System.currentTimeMillis() - GuruHostRule.MINUTE_MS)
    }

    private fun Dialog.guruView(): View = (findViewById<ViewGroup>(android.R.id.content)).getChildAt(0)

    private fun pressBack(dialog: Dialog) {
        host.idleMain()
        dialog.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK))
        dialog.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK))
        host.idleMain()
    }

    @Test
    fun `nothing pending shows nothing`() {
        installed()
        Guru.showPending(host.activity().get()) { it() }
        host.runIo()

        assertNull(ShadowDialog.getLatestDialog())
    }

    @Test
    fun `the alert comes after the exit look, on the host's main`() {
        host.crashed(a)
        installed()
        val onUi = mutableListOf<() -> Unit>()
        Guru.showPending(host.activity().get()) { onUi += it }

        assertEquals(2, host.queuedIo())
        host.runIo()
        assertNull(ShadowDialog.getLatestDialog())

        onUi.single()()
        assertTrue(ShadowDialog.getLatestDialog().isShowing)
    }

    @Test
    fun `the alert shows the newest report's number and how many more`() {
        host.crashed(a, at = System.currentTimeMillis() - 2 * GuruHostRule.MINUTE_MS)
        val newest = host.crashed(b)
        installed()
        Guru.showPending(host.activity().get()) { it() }
        host.runIo()

        val said = ShadowDialog.getLatestDialog().guruView().contentDescription.toString()
        assertTrue(said, said.contains("Guru Meditation ${newest.number}"))
        assertTrue(said, said.contains("+1 more"))
    }

    @Test
    fun `a tap removes the reports, after the io step`() {
        host.crashed(a)
        installed()
        Guru.showPending(host.activity().get()) { it() }
        host.runIo()
        val dialog = ShadowDialog.getLatestDialog()

        dialog.guruView().performClick()

        assertFalse(dialog.isShowing)
        assertEquals(listOf(a), host.store.pending().map { it.id })
        host.runIo()
        assertTrue(host.store.pending().isEmpty())
    }

    @Test
    fun `a tap removes only the reports on screen`() {
        host.crashed(a)
        installed()
        Guru.showPending(host.activity().get()) { it() }
        host.runIo()
        host.crashed(b)

        ShadowDialog.getLatestDialog().guruView().performClick()
        host.runIo()

        assertEquals(listOf(b), host.store.pending().map { it.id })
    }

    @Test
    fun `Back leaves everything for the next start`() {
        host.crashed(a)
        installed()
        Guru.showPending(host.activity().get()) { it() }
        host.runIo()
        val dialog = ShadowDialog.getLatestDialog()

        pressBack(dialog)

        assertFalse(dialog.isShowing)
        assertEquals(0, host.queuedIo())
        assertEquals(listOf(a), host.store.pending().map { it.id })
    }

    @Test
    fun `a destroyed activity gets no alert`() {
        host.crashed(a)
        installed()
        val controller = host.activity()
        val onUi = mutableListOf<() -> Unit>()
        Guru.showPending(controller.get()) { onUi += it }
        host.runIo()

        controller.pause().stop().destroy()
        onUi.single()()

        assertNull(ShadowDialog.getLatestDialog())
        assertEquals(listOf(a), host.store.pending().map { it.id })
    }
}
