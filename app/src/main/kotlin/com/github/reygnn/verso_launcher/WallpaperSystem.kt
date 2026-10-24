package com.github.reygnn.verso_launcher

import android.app.WallpaperColors
import android.app.WallpaperManager
import android.content.Context
import android.os.Handler
import android.os.Looper

/** The system side of [WallpaperPalette]: its question and its listener. */
internal object WallpaperSystem {

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
