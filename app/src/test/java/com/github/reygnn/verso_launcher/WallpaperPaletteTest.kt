package com.github.reygnn.verso_launcher

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The palette's question (IO thread) and the colour listener (main thread) as manual queues: a
 * test decides when the system answers and when the answer is handed back.
 */
class WallpaperPaletteTest {

    private val io = ArrayDeque<Runnable>()
    private val main = ArrayDeque<() -> Unit>()
    private var systemSaysLight = false
    private val shown = mutableListOf<Palette>()

    private val dark: Palette = HomeStyle.palette(lightWallpaper = false)
    private val light: Palette = HomeStyle.palette(lightWallpaper = true)

    private val palette: WallpaperPalette = WallpaperPalette(
        initial = dark,
        io = { io.addLast(it); true },
        main = { main.addLast(it) },
        isLight = { systemSaysLight },
        onChange = { shown += it },
    )

    private fun runIo() { while (io.isNotEmpty()) io.removeFirst().run() }
    private fun runMain() { while (main.isNotEmpty()) main.removeFirst()() }

    @Test fun `the answer is shown on the main thread`() {
        systemSaysLight = true
        palette.ask()
        runIo()
        assertEquals(emptyList<Palette>(), shown)
        runMain()
        assertEquals(listOf(light), shown)
        assertEquals(light, palette.current)
    }

    @Test fun `an answer equal to what is shown repaints nothing`() {
        palette.ask()
        runIo()
        runMain()
        assertEquals(emptyList<Palette>(), shown)
        assertEquals(dark, palette.current)
    }

    /** Asked before the wallpaper changed, answered after: the answer is the older one. */
    @Test fun `a listener report newer than the question wins over its answer`() {
        palette.ask()
        runIo()  // the system said dark
        palette.colorsChanged(light = true)
        runMain()
        assertEquals(listOf(light), shown)
        assertEquals(light, palette.current)
    }

    /** The report bumps the generation even when it changes nothing shown. */
    @Test fun `a report that repaints nothing still outdates the answer on its way`() {
        systemSaysLight = true
        palette.ask()
        runIo()  // the system said light, before the wallpaper turned dark again
        palette.colorsChanged(light = false)
        runMain()
        assertEquals(emptyList<Palette>(), shown)
        assertEquals(dark, palette.current)
    }

    /** Live and time-of-day wallpapers report often. */
    @Test fun `repeated reports repaint once per change`() {
        palette.colorsChanged(light = true)
        palette.colorsChanged(light = true)
        palette.colorsChanged(light = false)
        palette.colorsChanged(light = false)
        assertEquals(listOf(light, dark), shown)
    }

    /** Two questions in flight: the older answer must not flash in between. */
    @Test fun `only the newest of two questions is answered`() {
        systemSaysLight = true
        palette.ask()
        runIo()
        systemSaysLight = false
        palette.ask()
        runIo()
        runMain()
        assertEquals(emptyList<Palette>(), shown)
        assertEquals(dark, palette.current)
    }
}
