package com.github.reygnn.guru

import android.app.Dialog
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.time.Duration

/**
 * Real MotionEvents on the alert, the clock advanced by hand. The rule under test: only a
 * double-tap closes and only a still hold shares — a single tap, a swipe or a finger laid on a
 * dimmed screen to wake it does nothing.
 */
@RunWith(RobolectricTestRunner::class)
class GuruTouchTest {

    @get:Rule val host = GuruHostRule()

    private var shared = 0
    private val closed get() = if (dialog.isShowing) 0 else 1
    private lateinit var dialog: Dialog
    private lateinit var view: View
    private var downTime = 0L
    private var startX = 0f
    private var startY = 0f
    private var swipe = 0f

    private val slop get() = ViewConfiguration.get(view.context).scaledTouchSlop.toFloat()
    private val doubleTapMs = ViewConfiguration.getDoubleTapTimeout().toLong()
    private val longPressMs = ViewConfiguration.getLongPressTimeout().toLong()

    @Before
    fun setUp() {
        val activity = host.activity().get()
        dialog = GuruScreen.show(activity, listOf(GuruText.SOFTWARE_FAILURE), GuruTexts.English, onShare = { shared++ })
        view = dialog.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        // Attached and laid out first. A swipe has to stay inside the view, as it does on a device
        // where the alert fills the screen: View's own click handling drops a touch that leaves the
        // view, so a swipe off its edge would pass even without the gesture rules.
        host.idleMain()
        swipe = view.width / 2f
        startX = view.width / 4f
        startY = view.height / 2f
        check(swipe > 2 * slop) { "view too small to swipe inside it: ${view.width}" }
    }

    private fun after(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    private fun event(action: Int, x: Float, y: Float) {
        val e = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
        view.dispatchTouchEvent(e)
        e.recycle()
    }

    private fun down(x: Float = startX, y: Float = startY) {
        downTime = SystemClock.uptimeMillis()
        event(MotionEvent.ACTION_DOWN, x, y)
    }

    private fun move(x: Float, y: Float = startY) = event(MotionEvent.ACTION_MOVE, x, y)

    private fun up(x: Float = startX, y: Float = startY) = event(MotionEvent.ACTION_UP, x, y)

    private fun tap() {
        down()
        after(TAP_MS)
        up()
    }

    /** Nothing happened, and nothing is still pending on the clock. */
    private fun assertUntouched() {
        after(doubleTapMs + longPressMs)
        assertEquals(0, closed)
        assertEquals(0, shared)
        assertTrue(dialog.isShowing)
    }

    @Test
    fun `a single tap does nothing`() {
        tap()

        assertUntouched()
    }

    @Test
    fun `a double-tap closes`() {
        tap()
        after(BETWEEN_TAPS_MS)
        tap()

        assertEquals(1, closed)
        assertEquals(0, shared)
        assertFalse(dialog.isShowing)
    }

    @Test
    fun `two taps too far apart in time do nothing`() {
        tap()
        after(doubleTapMs + BETWEEN_TAPS_MS)
        tap()

        assertUntouched()
    }

    @Test
    fun `a swipe does nothing`() {
        down()
        for (step in 1..SWIPE_STEPS) {
            after(TAP_MS / SWIPE_STEPS)
            move(startX + step * swipe / SWIPE_STEPS)
        }
        up(startX + swipe)

        assertUntouched()
    }

    @Test
    fun `a finger that moves past the slop and then rests does not share`() {
        down()
        after(TAP_MS)
        move(startX + 2 * slop)
        after(longPressMs * 2)
        up(startX + 2 * slop)

        assertUntouched()
    }

    @Test
    fun `a second tap that slides does not close`() {
        tap()
        after(BETWEEN_TAPS_MS)
        down()
        move(startX + 2 * slop)
        up(startX + 2 * slop)

        assertUntouched()
    }

    @Test
    fun `a still hold shares`() {
        down()
        after(longPressMs + TAP_MS)

        assertEquals(1, shared)
        assertFalse(dialog.isShowing)
        up()
    }

    @Test
    fun `a hold that wobbles within the slop still shares`() {
        down()
        after(TAP_MS)
        move(startX + slop / 2)
        after(longPressMs)

        assertEquals(1, shared)
        up(startX + slop / 2)
    }

    @Test
    fun `TalkBack's click closes`() {
        assertTrue(view.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))

        assertEquals(1, closed)
        assertEquals(0, shared)
    }

    @Test
    fun `TalkBack's long click shares`() {
        assertTrue(view.performAccessibilityAction(AccessibilityNodeInfo.ACTION_LONG_CLICK, null))

        assertEquals(1, shared)
    }

    private companion object {
        const val TAP_MS = 60L
        const val BETWEEN_TAPS_MS = 120L
        const val SWIPE_STEPS = 10
    }
}
