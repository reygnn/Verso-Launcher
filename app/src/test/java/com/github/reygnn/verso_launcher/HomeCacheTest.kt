package com.github.reygnn.verso_launcher

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** [HomeCache] — the cold-start copy of the home page's favorites. */
class HomeCacheTest {

    @get:Rule val tmp = TemporaryFolder()

    private val personal = AppEntry.of("Telefon", "com.google.android.dialer", "com.android.dialer.Main", "Telefon")
    private val work = AppEntry.of("Gmail (Arbeit)", "com.google.android.gm", "com.google.android.gm.Main", "Gmail (Arbeit)", profile = 10)

    @Test fun `a round trip keeps order, keys and labels, work profile included`() {
        val back = HomeCache.parse(HomeCache.format(listOf(personal, work)))
        assertEquals(listOf(personal.key, work.key), back.map { it.key })
        assertEquals(listOf("Telefon", "Gmail (Arbeit)"), back.map { it.label })
        assertEquals(10L, back[1].profile)
    }

    @Test fun `a tab or line break in a label cannot break the format`() {
        val odd = AppEntry.of("a\tb\nc", "p", "p.Main", "a\tb\nc")
        assertEquals(listOf("a b c"), HomeCache.parse(HomeCache.format(listOf(odd))).map { it.label })
    }

    @Test fun `malformed lines are skipped, not fatal`() {
        val text = "no-tab-line\n\tlabel-without-key\npkg-without-class\tX\np/c#notanumber\tY\np/c\tGood\n"
        assertEquals(listOf("p/c"), HomeCache.parse(text).map { it.key })
    }

    @Test fun `a line with an empty label, package or class is skipped`() {
        val text = "a/b\t\n/b\tX\na/\tX\nc/d\tGood\n"
        assertEquals(listOf("c/d"), HomeCache.parse(text).map { it.key })
    }

    @Test fun `written to disk and read back, no file reads as empty`() {
        val dir = tmp.newFolder()
        assertEquals(emptyList<AppEntry>(), HomeCache.read(dir))
        HomeCache.write(dir, listOf(personal, work))
        assertEquals(listOf(personal.key, work.key), HomeCache.read(dir).map { it.key })
    }
}
