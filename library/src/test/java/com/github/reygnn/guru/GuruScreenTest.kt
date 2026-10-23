package com.github.reygnn.guru

import android.app.Dialog
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class GuruScreenTest {

    @get:Rule val host = GuruHostRule()

    private object German : GuruTexts {
        override val hint = "doppeltippen: weiter · halten: senden · zurück: später"
        override val actionContinue = "weiter, Bericht verwerfen"
        override val actionSend = "Bericht senden"
        override fun more(count: Int) = "+$count weitere"
        override fun sent(sent: Int, total: Int) = "$sent von $total gesendet"
    }

    private var continued = 0
    private var sent = 0
    private val lines = listOf(GuruText.SOFTWARE_FAILURE, GuruText.PRESS_LEFT, "Guru Meditation #80000001.4A3F21C0")

    private val controller = lazy { host.activity() }

    private fun show() = GuruScreen.show(controller.value.get(), lines, German, onContinue = { continued++ }, onSend = { sent++ })

    private fun view(dialog: Dialog) = dialog.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)

    @Test
    fun `continue acts once`() {
        val dialog = show()
        val view = view(dialog)

        view.performClick()
        view.performClick()
        view.performLongClick()

        assertEquals(1, continued)
        assertEquals(0, sent)
        assertFalse(dialog.isShowing)
    }

    @Test
    fun `send acts once`() {
        val dialog = show()
        val view = view(dialog)

        view.performLongClick()
        view.performLongClick()
        view.performClick()

        assertEquals(0, continued)
        assertEquals(1, sent)
        assertFalse(dialog.isShowing)
    }

    @Test
    fun `TalkBack names both actions in the host's words`() {
        val info = view(show()).createAccessibilityNodeInfo()

        val labels = info.actionList.associate { it.id to it.label?.toString() }
        assertEquals(German.actionContinue, labels[AccessibilityNodeInfo.ACTION_CLICK])
        assertEquals(German.actionSend, labels[AccessibilityNodeInfo.ACTION_LONG_CLICK])
    }

    @Test
    fun `TalkBack reads the lines and the host's hint`() {
        val said = view(show()).contentDescription.toString()

        assertEquals(
            "Software Failure. Press left mouse button to continue. Guru Meditation #80000001.4A3F21C0. " + German.hint,
            said,
        )
    }

    @Test
    fun `the alert closes with its activity`() {
        val dialog = show()
        assertTrue(dialog.isShowing)

        controller.value.pause().stop().destroy()

        assertFalse(dialog.isShowing)
        assertEquals(0, continued)
        assertEquals(0, sent)
    }
}
