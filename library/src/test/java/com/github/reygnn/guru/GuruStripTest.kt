package com.github.reygnn.guru

import android.animation.ValueAnimator
import android.app.Activity
import android.app.Dialog
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Insets
import android.graphics.drawable.ColorDrawable
import android.os.Looper
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.GraphicsMode
import java.time.Duration

/** The Amiga strip: black at the top, the app slid down under it, and back on every way out. */
@RunWith(RobolectricTestRunner::class)
class GuruStripTest {

    @get:Rule val host = GuruHostRule()

    private val lines = listOf(GuruText.SOFTWARE_FAILURE, GuruText.PRESS_LEFT, "Guru Meditation #80000001.4A3F21C0", "saved")
    private val controller = lazy { host.activity() }
    private val activity: Activity get() = controller.value.get()
    private val decor get() = activity.window.decorView
    private var shared = 0

    // "Remove animations" sets the system's animator duration scale to 0; a hidden setter here.
    private fun animatorScale(scale: Float) {
        ValueAnimator::class.java.getMethod("setDurationScale", Float::class.javaPrimitiveType).invoke(null, scale)
    }

    @After
    fun animationsBackOn() = animatorScale(1f)

    private fun show(texts: GuruTexts = GuruTexts.English): Dialog = GuruScreen.show(activity, lines, texts, onShare = { shared++ })

    private fun Dialog.guruView() = findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as GuruView

    private fun settle() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(GuruScreen.SLIDE_MS * 2))

    /** Shown, laid out, slid down. */
    private fun shown(): Dialog = show().also { settle() }

    @Test
    fun `the app slides down by the strip while the alert is up`() {
        val view = shown().guruView()

        assertTrue(view.strip > 0)
        assertTrue(view.strip < view.height)
        assertEquals(view.strip, decor.translationY, 0.01f)
    }

    @Test
    fun `the strip begins below the top inset`() {
        val view = shown().guruView()
        val without = view.strip

        view.dispatchApplyWindowInsets(
            WindowInsets.Builder().setInsets(WindowInsets.Type.systemBars(), Insets.of(0, 60, 0, 0)).build(),
        )
        settle()

        assertEquals(without + 60, view.strip, 0.01f)
        assertEquals(view.strip, decor.translationY, 0.01f)
    }

    @Test
    fun `the strip is never taller than the window`() {
        val view = shown().guruView()

        view.layout(0, 0, view.width, 40)
        settle()

        assertEquals(40f, view.strip, 0.01f)
    }

    @Test
    fun `the window below the strip is see-through, not dimmed`() {
        val window = shown().window!!

        assertEquals(0, window.attributes.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        assertEquals(Color.TRANSPARENT, (window.decorView.background as ColorDrawable).color)
    }

    /** Robolectric plays a ViewPropertyAnimator through at once: what shows is that one ran, for SLIDE_MS. */
    @Test
    fun `it glides - the slide is animated`() {
        val dialog = shown()

        assertEquals(GuruScreen.SLIDE_MS, decor.animate().duration)
        assertEquals(dialog.guruView().strip, decor.translationY, 0.01f)
    }

    @Test
    fun `with animations off it jumps`() {
        animatorScale(0f)
        assertTrue(!ValueAnimator.areAnimatorsEnabled())
        val dialog = show()
        host.idleMain()

        assertEquals(dialog.guruView().strip, decor.translationY, 0.01f)
        dialog.guruView().performClick()
        host.idleMain()
        assertEquals(0f, decor.translationY, 0.01f)
        assertTrue(decor.animate().duration != GuruScreen.SLIDE_MS)
    }

    // ---- back to where the app was, on every way out ---------------------------------

    private fun assertBackAfter(close: (Dialog) -> Unit) {
        decor.translationY = 7f
        val dialog = shown()
        assertTrue(decor.translationY > 7f)

        close(dialog)
        settle()

        assertEquals(7f, decor.translationY, 0.01f)
    }

    @Test
    fun `a double-tap puts the app back where it was`() = assertBackAfter { it.guruView().performClick() }

    @Test
    fun `Back puts the app back where it was`() = assertBackAfter { dialog ->
        dialog.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK))
        dialog.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK))
    }

    @Test
    fun `a hold puts the app back where it was, and shares`() {
        assertBackAfter { it.guruView().performLongClick() }
        assertEquals(1, shared)
    }

    @Test
    fun `the activity's end puts it back at once`() {
        decor.translationY = 7f
        shown()

        controller.value.pause().stop().destroy()
        host.idleMain()

        assertEquals(7f, decor.translationY, 0.01f)
    }

    @Test
    fun `an alert that cannot be shown leaves the app where it was`() {
        decor.translationY = 7f
        val broken = object : GuruTexts {
            override val hint: String get() = throw IllegalStateException("string resource gone")
        }

        runCatching { show(broken) }
        settle()

        assertEquals(7f, decor.translationY, 0.01f)
    }

    /** Native graphics: the real drawing, read back from a bitmap. */
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `only the strip is black, below it the app shows through`() {
        val view = shown().guruView()
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)

        view.draw(Canvas(bitmap))

        assertEquals(Color.BLACK, bitmap.getPixel(view.width / 2, (view.strip - 2).toInt()))
        assertEquals(Color.TRANSPARENT, bitmap.getPixel(view.width / 2, (view.strip + 2).toInt()))
        assertEquals(Color.TRANSPARENT, bitmap.getPixel(view.width / 2, view.height - 2))
    }
}
