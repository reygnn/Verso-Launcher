package com.github.reygnn.verso_launcher

import android.util.Log
import java.io.File

/**
 * The favorites as last shown, so a cold start draws home at once instead of waiting for the
 * enumeration. Disposable — no fsync; a lost or stale file costs one slow frame. Used on VersoApp.io.
 */
internal object HomeCache {

    const val FILE = "home-cache.txt"

    /** One "key<TAB>label" line per favorite. */
    fun format(favorites: List<AppEntry>): String =
        favorites.joinToString("") { "${it.key}\t${it.label.replace(Regex("[\\t\\r\\n]"), " ")}\n" }

    /** A line whose key [AppEntry.keyOf] could not have built is skipped. */
    fun parse(text: String): List<AppEntry> = text.lineSequence().mapNotNull { line ->
        val label = line.substringAfter('\t', "").ifEmpty { return@mapNotNull null }
        val parts = AppEntry.parseKey(line.substringBefore('\t', "")) ?: return@mapNotNull null
        AppEntry(label, parts.packageName, parts.className, label, parts.profile)
    }.toList()

    // a disposable cache: unreadable = none
    @Suppress("TooGenericExceptionCaught")
    fun read(dir: File): List<AppEntry> = try {
        File(dir, FILE).takeIf { it.isFile }?.readText()?.let(::parse).orEmpty()
    } catch (e: Exception) {
        Log.w("Verso", "$FILE unreadable", e)
        emptyList()
    }

    fun write(dir: File, favorites: List<AppEntry>) = writeDisposable(dir, FILE, format(favorites))
}
