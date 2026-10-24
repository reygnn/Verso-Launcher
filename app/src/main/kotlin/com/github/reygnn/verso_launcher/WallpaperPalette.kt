package com.github.reygnn.verso_launcher

/**
 * The palette for the wallpaper behind the window (Unity Kolibri's smart contrast): asked once on
 * the IO thread, then kept current by the system's colour listener ([WallpaperSystem.watch]). [onChange]
 * gets each new palette on the main thread, never the one already shown.
 *
 * The question and the listener race: the answer to a question asked before a listener report is
 * older than that report and is dropped. Main thread only, but for [isLight]. Pure: the system
 * side is [WallpaperSystem].
 */
internal class WallpaperPalette(
    initial: Palette,
    private val io: (Runnable) -> Unit,
    private val main: (() -> Unit) -> Unit,
    /** The system's verdict; a binder call that may extract the colours from the bitmap right then. */
    private val isLight: () -> Boolean,
    private val onChange: (Palette) -> Unit,
) {
    /** The palette last handed to [onChange], or the initial one. */
    var current: Palette = initial
        private set

    // Bumped by every palette decision, so an answer cannot overwrite a newer listener report.
    private var generation = 0

    /** Asks the system on the IO thread; until the answer, [current] stands. */
    fun ask() {
        val asked = ++generation
        io(Runnable {
            val light = isLight()
            main { if (asked == generation) show(HomeStyle.palette(light)) }
        })
    }

    /** A listener report: newer than every answer still on its way. */
    fun colorsChanged(light: Boolean) {
        ++generation
        show(HomeStyle.palette(light))
    }

    // Live and time-of-day wallpapers report colours often; repaint only on a real change.
    private fun show(p: Palette) {
        if (p == current) return
        current = p
        onChange(p)
    }
}
