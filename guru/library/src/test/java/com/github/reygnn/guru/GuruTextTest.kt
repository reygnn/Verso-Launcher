package com.github.reygnn.guru

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class GuruTextTest {

    // ---- cap ---------------------------------------------------------------

    @Test
    fun `cap leaves a text of exactly max alone`() {
        assertEquals("abcde", GuruText.cap("abcde", 5))
    }

    @Test
    fun `cap cuts one past max and says how much`() {
        assertEquals("abcde\n[1 chars cut]", GuruText.cap("abcdef", 5))
    }

    /** "a😀b": cutting at 2 would keep the high half of the emoji alone. */
    @Test
    fun `cap never keeps half a surrogate pair`() {
        assertEquals("a\n[3 chars cut]", GuruText.cap("a😀b", 2))
        assertEquals("a😀\n[1 chars cut]", GuruText.cap("a😀b", 3))
    }

    // ---- readCapped -------------------------------------------------------------

    @Test
    fun `readCapped reads exactly max bytes whole`() {
        assertEquals("abcd", GuruText.readCapped(ByteArrayInputStream("abcd".toByteArray()), 4))
    }

    @Test
    fun `readCapped cuts one byte past max`() {
        assertEquals("abcd\n[cut at 4 bytes]", GuruText.readCapped(ByteArrayInputStream("abcde".toByteArray()), 4))
    }

    // ---- screen and messages -------------------------------------------------------

    @Test
    fun `the alert is the Amiga lines, the number and where the reports went`() {
        assertEquals(
            listOf("Software Failure.", "Press left mouse button to continue.", "Guru Meditation #80000001.0000002A",
                "saved to Download/Guru"),
            GuruText.screenLines("#80000001.0000002A", saved = 1, GuruTexts.English),
        )
    }

    @Test
    fun `the last line is the host's word for what was saved`() {
        val counted = object : GuruTexts {
            override fun saved(count: Int) = "$count gesichert"
        }
        assertEquals("3 gesichert", GuruText.screenLines("#1", saved = 3, counted).last())
        assertEquals("1 gesichert", GuruText.screenLines("#1", saved = 1, counted).last())
    }

    @Test
    fun `the subject names number, package and version`() {
        val r = report()
        assertEquals("Guru Meditation ${r.number} · com.example.app 1.2.3", GuruText.subject(r))
    }

    @Test
    fun `died in Guru - a crash on main with a frame of Guru's own package`() {
        val guru = "java.lang.IllegalStateException: x\n\tat com.github.reygnn.guru.GuruView.onDraw(GuruScreen.kt:1)"
        assertTrue(GuruText.diedInGuru(report(thread = "main", trace = guru)))
        assertTrue(GuruText.diedInGuru(report(thread = "main",
            trace = "java.lang.RuntimeException: y\n\tat android.view.View.draw(View.java:1)\n$guru")))
    }

    @Test
    fun `not died in Guru - off main, another kind, a package below Guru's, or none of Guru's frames`() {
        val guru = "java.lang.IllegalStateException: x\n\tat com.github.reygnn.guru.GuruView.onDraw(GuruScreen.kt:1)"
        assertFalse(GuruText.diedInGuru(report(thread = "io", trace = guru)))
        assertFalse(GuruText.diedInGuru(report(kind = GuruKind.ANR, thread = "main", trace = guru)))
        assertFalse(GuruText.diedInGuru(report(thread = "main",
            trace = "java.lang.IllegalStateException: x\n\tat com.github.reygnn.guru.sample.MainActivity.onCreate(MainActivity.kt:1)")))
        assertFalse(GuruText.diedInGuru(report(thread = "main")))
        assertFalse(GuruText.diedInGuru(report(thread = "main", trace = "com.github.reygnn.guru.Guru failed")))
    }
}
