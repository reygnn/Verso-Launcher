package com.github.reygnn.guru

import android.app.Dialog
import android.graphics.Color
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
class GuruScreenTest {

    @get:Rule val host = GuruHostRule()

    private object German : GuruTexts {
        override val hint = "doppeltippen: schließen · halten: teilen"
        override val actionClose = "schließen"
        override val actionShare = "Bericht teilen"
        override fun saved(count: Int) = "$count gespeichert"
    }

    private var shared = 0
    private val lines = listOf(GuruText.SOFTWARE_FAILURE, GuruText.PRESS_LEFT, "Guru Meditation #80000001.4A3F21C0")

    private val controller = lazy { host.activity() }

    private fun show() = GuruScreen.show(controller.value.get(), lines, German, onShare = { shared++ })

    private fun view(dialog: Dialog) = dialog.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)

    @Test
    fun `close shares nothing, and a later hold neither`() {
        val dialog = show()
        val view = view(dialog)

        view.performClick()
        view.performLongClick()

        assertEquals(0, shared)
        assertFalse(dialog.isShowing)
    }

    @Test
    fun `share acts once`() {
        val dialog = show()
        val view = view(dialog)

        assertTrue(view.performLongClick())
        view.performLongClick()
        view.performClick()

        assertEquals(1, shared)
        assertFalse(dialog.isShowing)
    }

    @Test
    fun `TalkBack names both actions in the host's words`() {
        val info = view(show()).createAccessibilityNodeInfo()

        val labels = info.actionList.associate { it.id to it.label?.toString() }
        assertEquals(German.actionClose, labels[AccessibilityNodeInfo.ACTION_CLICK])
        assertEquals(German.actionShare, labels[AccessibilityNodeInfo.ACTION_LONG_CLICK])
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
        assertEquals(0, shared)
    }

    // ---- fitting and contrast --------------------------------------------------------

    private fun sizesWith(last: String): Pair<Float, Float> {
        val dialog = GuruScreen.show(host.activity().get(), lines + last, German, onShare = {})
        host.idleMain()
        return (view(dialog) as GuruView).textSizes
    }

    @Test
    fun `a long host line shrinks only itself, a short one is as big as the rest`() {
        val (quoteShort, lastShort) = sizesWith("ok")
        // Robolectric measures about a pixel a char in a 320 px wide view: too long here means long.
        val (quoteLong, lastLong) = sizesWith("8 Berichte in Download/Guru gespeichert ".repeat(12))

        assertEquals(quoteShort, quoteLong, 0.01f)
        assertEquals(quoteShort, lastShort, 0.01f)
        assertTrue(lastLong < quoteLong)
    }

    @Test
    fun `the hint reads at WCAG contrast on black`() {
        fun channel(c: Int): Double {
            val v = c / 255.0
            return if (v <= 0.04045) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
        }
        val grey = GuruView.HINT_GREY
        val luminance = 0.2126 * channel(Color.red(grey)) + 0.7152 * channel(Color.green(grey)) + 0.0722 * channel(Color.blue(grey))

        assertTrue((luminance + 0.05) / 0.05 >= 4.5)
    }

    @Test
    fun `fitted shrinks to the room, never grows, never to nothing`() {
        assertEquals(16f, GuruView.fitted(16f, room = 300f, widest = 200f), 0.001f)
        assertEquals(8f, GuruView.fitted(16f, room = 100f, widest = 200f), 0.001f)
        assertEquals(GuruView.MIN_TEXT_PX, GuruView.fitted(16f, room = -10f, widest = 200f), 0.001f)
        assertEquals(GuruView.MIN_TEXT_PX, GuruView.fitted(16f, room = 0.01f, widest = 200f), 0.001f)
        assertEquals(GuruView.MIN_TEXT_PX, GuruView.fitted(16f, room = -10f, widest = 0f), 0.001f)
    }

    /** Robolectric reports the dialog's window as gone; a device as visible — set here as there. */
    @Test
    fun `the border blinks only while the alert can be seen`() {
        val view = view(show()) as GuruView
        host.idleMain()
        val looper = shadowOf(Looper.getMainLooper())

        view.dispatchWindowVisibilityChanged(View.VISIBLE)
        looper.idleFor(Duration.ofMillis(GuruView.BLINK_MS))
        assertFalse(view.lit)

        view.dispatchWindowVisibilityChanged(View.INVISIBLE)
        looper.idleFor(Duration.ofMillis(5 * GuruView.BLINK_MS))
        assertTrue(view.lit)

        view.dispatchWindowVisibilityChanged(View.VISIBLE)
        looper.idleFor(Duration.ofMillis(GuruView.BLINK_MS))
        assertFalse(view.lit)
    }
}
