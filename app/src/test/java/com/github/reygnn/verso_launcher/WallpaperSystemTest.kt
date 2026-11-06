package com.github.reygnn.verso_launcher

import android.app.WallpaperColors
import android.app.WallpaperManager
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

/**
 * The system's wallpaper as far as Verso asks it. Robolectric's own shadow knows no colours and no
 * listener, so this fake answers them and hands the test the listener to report through.
 */
@Implements(WallpaperManager::class)
open class FakeWallpaperManager {
    @Implementation
    protected open fun getWallpaperColors(which: Int): WallpaperColors? {
        asked += which
        failure?.let { throw it }
        return colors
    }

    @Implementation
    protected open fun addOnColorsChangedListener(listener: WallpaperManager.OnColorsChangedListener, handler: Handler) {
        listeners += listener
        looper = handler.looper
    }

    @Implementation
    protected open fun removeOnColorsChangedListener(listener: WallpaperManager.OnColorsChangedListener) {
        listeners -= listener
    }

    companion object {
        var colors: WallpaperColors? = null
        var failure: RuntimeException? = null
        var looper: Looper? = null
        val asked = mutableListOf<Int>()
        val listeners = mutableListOf<WallpaperManager.OnColorsChangedListener>()

        fun reset() {
            colors = null
            failure = null
            looper = null
            asked.clear()
            listeners.clear()
        }
    }
}

@Config(shadows = [FakeWallpaperManager::class])
@RunWith(RobolectricTestRunner::class)
class WallpaperSystemTest {

    private val context = RuntimeEnvironment.getApplication()
    private val logged = mutableListOf<String>()

    private val dark = HomeStyle.palette(lightWallpaper = false)
    private val light = HomeStyle.palette(lightWallpaper = true)
    private val shown = mutableListOf<Palette>()

    private fun colors(hints: Int) = WallpaperColors(Color.valueOf(Color.GRAY), null, null, hints)
    private val darkText get() = colors(WallpaperColors.HINT_SUPPORTS_DARK_TEXT)
    private val noHints get() = colors(0)

    private fun isLight() = WallpaperSystem.isLight(context) { message, _ -> logged += message }

    private fun palette(initial: Palette) = WallpaperPalette(
        initial = initial,
        io = { it.run() },
        main = { it() },
        isLight = { error("the question is not asked here") },
        onChange = { shown += it },
    )

    private fun report(colors: WallpaperColors?, which: Int) =
        FakeWallpaperManager.listeners.toList().forEach { it.onColorsChanged(colors, which) }

    @Before fun clean() = FakeWallpaperManager.reset()

    // ---- the question ---------------------------------------------------------------------

    @Test fun `a wallpaper that wants dark text is light, whatever other hints it has`() {
        FakeWallpaperManager.colors = darkText
        assertTrue(isLight())
        FakeWallpaperManager.colors = colors(WallpaperColors.HINT_SUPPORTS_DARK_TEXT or WallpaperColors.HINT_SUPPORTS_DARK_THEME)
        assertTrue(isLight())
    }

    @Test fun `a wallpaper with other hints or none is dark`() {
        FakeWallpaperManager.colors = noHints
        assertFalse(isLight())
        FakeWallpaperManager.colors = colors(WallpaperColors.HINT_SUPPORTS_DARK_THEME)
        assertFalse(isLight())
    }

    /** A live wallpaper may have none, and a Samsung answers none at all (seen on the A17). */
    @Test fun `no colours count as a dark wallpaper, and that is no failure`() {
        FakeWallpaperManager.colors = null
        assertFalse(isLight())
        assertEquals(emptyList<String>(), logged)
    }

    @Test fun `a question the system refuses counts as dark and is logged`() {
        FakeWallpaperManager.colors = darkText
        FakeWallpaperManager.failure = IllegalStateException("no service")
        assertFalse(isLight())
        assertEquals(listOf("wallpaper colours unavailable"), logged)
    }

    @Test fun `the question is about the home screen's wallpaper`() {
        isLight()
        assertEquals(listOf(WallpaperManager.FLAG_SYSTEM), FakeWallpaperManager.asked)
    }

    // ---- the listener ---------------------------------------------------------------------

    @Test fun `a change of the home screen's wallpaper reaches the palette`() {
        WallpaperSystem.watch(context, palette(dark))
        report(darkText, WallpaperManager.FLAG_SYSTEM)
        assertEquals(listOf(light), shown)
    }

    @Test fun `a change of the lock screen's wallpaper alone is ignored`() {
        WallpaperSystem.watch(context, palette(dark))
        report(darkText, WallpaperManager.FLAG_LOCK)
        assertEquals(emptyList<Palette>(), shown)
    }

    @Test fun `one wallpaper for both screens counts`() {
        WallpaperSystem.watch(context, palette(dark))
        report(darkText, WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK)
        assertEquals(listOf(light), shown)
    }

    @Test fun `a report without colours counts as dark`() {
        WallpaperSystem.watch(context, palette(light))
        report(null, WallpaperManager.FLAG_SYSTEM)
        assertEquals(listOf(dark), shown)
    }

    /** [WallpaperPalette] is main-thread only. */
    @Test fun `reports come on the main thread`() {
        WallpaperSystem.watch(context, palette(dark))
        assertSame(Looper.getMainLooper(), FakeWallpaperManager.looper)
    }

    @Test fun `after the unwatch nothing is listening`() {
        val unwatch = WallpaperSystem.watch(context, palette(dark))
        assertEquals(1, FakeWallpaperManager.listeners.size)
        unwatch()
        assertEquals(0, FakeWallpaperManager.listeners.size)
    }
}
