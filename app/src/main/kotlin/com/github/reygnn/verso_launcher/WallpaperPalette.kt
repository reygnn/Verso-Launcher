package com.github.reygnn.verso_launcher

import android.app.WallpaperColors
import android.app.WallpaperManager
import android.content.Context
import android.os.Handler
import android.os.Looper

/**
 * The palette for the wallpaper behind the window (Unity Kolibri's smart contrast): asked once on
 * the IO thread, then kept current by the system's colour listener ([Wallpaper.watch]). [onChange]
 * gets each new palette on the main thread, never the one already shown.
 *
 * The question and the listener race: the answer to a question asked before a listener report is
 * older than that report and is dropped. Main thread only, but for [isLight].
 */
internal class WallpaperPalette(
    initial: Palette,
    private val io: (Runnable) -> Boolean,
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

/** The system side of [WallpaperPalette]: its question and its listener. */
internal object Wallpaper {

    /** Android's own judgement (HINT_SUPPORTS_DARK_TEXT); unknown counts as a dark wallpaper. */
    // no colours = the default palette
    @Suppress("TooGenericExceptionCaught")
    fun isLight(context: Context, log: (String, Throwable?) -> Unit): Boolean = try {
        WallpaperManager.getInstance(context).getWallpaperColors(WallpaperManager.FLAG_SYSTEM).wantsDarkText()
    } catch (e: RuntimeException) {
        log("wallpaper colours unavailable", e)
        false
    }

    /** Reports the system wallpaper's colour changes to [palette] on the main thread; returns the unwatch. */
    fun watch(context: Context, palette: WallpaperPalette): () -> Unit {
        val manager = WallpaperManager.getInstance(context)
        val listener = WallpaperManager.OnColorsChangedListener { colors, which ->
            if (which and WallpaperManager.FLAG_SYSTEM != 0) palette.colorsChanged(colors.wantsDarkText())
        }
        manager.addOnColorsChangedListener(listener, Handler(Looper.getMainLooper()))
        return { manager.removeOnColorsChangedListener(listener) }
    }

    private fun WallpaperColors?.wantsDarkText(): Boolean =
        this != null && (colorHints and WallpaperColors.HINT_SUPPORTS_DARK_TEXT) != 0
}
