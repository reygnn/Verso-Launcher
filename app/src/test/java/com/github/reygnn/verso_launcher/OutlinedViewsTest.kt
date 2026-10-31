package com.github.reygnn.verso_launcher

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.view.View
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * The outlines as drawn: Robolectric's native graphics renders each view into a bitmap, and the
 * pixels say which pass drew what. Black glyphs, a white outline, a transparent page, so a pixel
 * is fill, outline or a mixture of the two.
 */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
class OutlinedViewsTest {

    private val activity: Activity = Robolectric.buildActivity(Activity::class.java).setup().get()

    private fun <T : TextView> T.glyph(): T = apply {
        text = "W"
        textSize = TEXT_PX
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Color.BLACK)
    }

    /** [view] laid out at its own size and drawn into a bitmap. */
    private fun render(view: View): Pixels {
        val unspecified = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        view.measure(unspecified, unspecified)
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        return Pixels(bitmap)
    }

    @Test fun `the outline rims the glyphs and the fill covers its inner half`() {
        val plain = render(OutlinedTextView(activity).glyph())
        val outlined = render(OutlinedTextView(activity).glyph().apply { setOutline(OUTLINE_PX, Color.WHITE) })
        assertEquals(0, plain.outline)
        assertTrue("an outline is drawn: $outlined against $plain", outlined.outline > plain.fill / 2)
        // Stroked first and filled on top, the glyph keeps its whole fill; the other way round the
        // stroke's inner half would eat into it.
        assertTrue("$outlined against $plain", outlined.solid >= plain.solid * (1 - FILL_LOSS))
    }

    @Test fun `no width or a transparent colour draws no outline`() {
        assertEquals(0, render(OutlinedTextView(activity).glyph().apply { setOutline(0f, Color.WHITE) }).outline)
        assertEquals(0, render(OutlinedTextView(activity).glyph().apply { setOutline(OUTLINE_PX, Color.TRANSPARENT) }).outline)
    }

    /** The stroke pass swaps the colour through setTextColor, which invalidates: ignored mid-draw. */
    @Test fun `a drawn outline leaves the view clean, in its own colours`() {
        val view = OutlinedTextView(activity).glyph().apply { setOutline(OUTLINE_PX, Color.WHITE) }
        render(view)
        assertFalse("the colour swap asked for another frame", view.isDirty)
        assertEquals(Color.BLACK, view.currentTextColor)
        assertEquals(Paint.Style.FILL, view.paint.style)
    }

    /**
     * What makes a dimmed row fade stroke and fill as one. Only the property: it acts through the
     * RenderNode of hardware rendering, and a bitmap canvas draws in software, where a view at
     * partial alpha always goes through a layer. Drawn both ways, the pixels are the same.
     */
    @Test fun `an outlined row asks for one layer when dimmed, a plain one does not`() {
        assertTrue(OutlinedTextView(activity).glyph().apply { setOutline(OUTLINE_PX, Color.WHITE) }.hasOverlappingRendering())
        assertFalse(OutlinedTextView(activity).glyph().hasOverlappingRendering())
        assertFalse(OutlinedTextView(activity).glyph().apply { setOutline(OUTLINE_PX, Color.TRANSPARENT) }.hasOverlappingRendering())
    }

    @Test fun `the prompt's hint is outlined, and its colours come back`() {
        val prompt = OutlinedEditText(activity).glyph().apply {
            setText("")
            hint = "W"
            setHintTextColor(Color.BLACK)
            background = null
            highlightColor = HIGHLIGHT
            setOutline(OUTLINE_PX, Color.WHITE)
        }
        val drawn = render(prompt)
        assertTrue("the hint has an outline", drawn.outline > 0)
        assertEquals(Color.BLACK, prompt.currentHintTextColor)
        assertEquals(HIGHLIGHT, prompt.highlightColor)
        assertFalse(prompt.isDirty)
    }

    @Test fun `the clock is outlined like a text`() {
        val plain = render(OutlinedTextClock(activity).glyph().apply { format24Hour = "'W'"; format12Hour = "'W'" })
        val clock = OutlinedTextClock(activity).glyph().apply {
            format24Hour = "'W'"
            format12Hour = "'W'"
            setOutline(OUTLINE_PX, Color.WHITE)
        }
        val outlined = render(clock)
        assertTrue("$outlined against $plain", outlined.outline > plain.fill / 2)
        assertTrue("$outlined against $plain", outlined.solid >= plain.solid * (1 - FILL_LOSS))
        assertFalse(clock.isDirty)
    }

    /** SRC_IN: a translucent outline is the outline colour at its alpha, not that over the icon's white. */
    @Test fun `an icon's outline takes the outline colour, translucent or switched off`() {
        val icon = { outline: Int ->
            render(OutlinedImageView(activity).apply {
                setImageResource(R.drawable.ic_bolt_outlined)
                setColors(Color.BLACK, outline)
            })
        }
        assertTrue("a white rim", icon(Color.WHITE).outline > 0)
        assertEquals("no white of the drawable's own", 0, icon(TRANSLUCENT_BLACK).light)
        assertEquals("switched off", 0, icon(Color.TRANSPARENT).light)
    }

    @Test fun `a plain icon is tinted whole`() {
        val drawn = render(OutlinedImageView(activity).apply {
            setImageResource(R.drawable.ic_bolt)
            setColors(Color.BLACK, Color.WHITE)
        })
        assertTrue(drawn.fill > 0)
        assertEquals(0, drawn.light)
    }

    /** A rendered bitmap, its opaque pixels sorted into fill (black), outline (white) and mixed. */
    private class Pixels(bitmap: Bitmap) {
        var fill = 0
        /** Fully opaque fill: the glyph's inside, without its anti-aliased edge. */
        var solid = 0
        var outline = 0
        var mixed = 0
        /** Any pixel with some light in it, at any alpha: white that should not be there. */
        var light = 0

        override fun toString() = "fill $fill (solid $solid), outline $outline, mixed $mixed, light $light"

        init {
            for (x in 0 until bitmap.width) for (y in 0 until bitmap.height) {
                val p = bitmap.getPixel(x, y)
                if (Color.alpha(p) > 0 && Color.red(p) > LIGHT) light++
                if (Color.alpha(p) == OPAQUE && Color.red(p) < DARK) solid++
                if (Color.alpha(p) < VISIBLE) continue
                when {
                    Color.red(p) < DARK -> fill++
                    Color.red(p) > 255 - DARK -> outline++
                    else -> mixed++
                }
            }
        }
    }

    private companion object {
        const val TEXT_PX = 80f
        const val OUTLINE_PX = 8f
        /**
         * How much of the solid fill an outline may cost. Measured: stroked first, the glyph keeps
         * 103 % (the stroke's edge turns opaque under the fill); stroked last, 18 %.
         */
        const val FILL_LOSS = 0.05f
        const val HIGHLIGHT = 0x660000FF
        const val TRANSLUCENT_BLACK = 0x80000000.toInt()
        /** Opaque enough to count, at a dimmed row's alpha too. */
        const val VISIBLE = 64
        const val OPAQUE = 255
        const val DARK = 48
        const val LIGHT = 64
    }
}
