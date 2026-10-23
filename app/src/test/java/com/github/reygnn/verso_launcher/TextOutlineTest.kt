package com.github.reygnn.verso_launcher

import android.graphics.Color
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Robolectric for ColorStateList: [TextOutline.set] reports a change, and only a change. */
@RunWith(RobolectricTestRunner::class)
class TextOutlineTest {

    private val white = 0xCCFFFFFF.toInt()
    private val black = 0x99000000.toInt()

    @Test fun `the same width and colour again is no change - a row bind does not redraw`() {
        val outline = TextOutline()
        assertTrue(outline.set(4f, white))
        assertFalse(outline.set(4f, white))
        assertTrue(outline.isActive)
    }

    @Test fun `a new colour or a new width is a change`() {
        val outline = TextOutline()
        outline.set(4f, white)
        assertTrue("palette switch", outline.set(4f, black))
        assertTrue("display size", outline.set(6f, black))
        assertTrue(outline.isActive)
    }

    @Test fun `transparent switches the outline off, and back on`() {
        val outline = TextOutline()
        outline.set(4f, white)
        assertTrue(outline.set(4f, Color.TRANSPARENT))
        assertFalse(outline.isActive)
        assertTrue(outline.set(4f, white))
        assertTrue(outline.isActive)
    }

    @Test fun `a new outline starts off, and setting it off is no change`() {
        val outline = TextOutline()
        assertFalse(outline.isActive)
        assertFalse(outline.set(0f, Color.TRANSPARENT))
    }
}
