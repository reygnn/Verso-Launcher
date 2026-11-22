package com.github.reygnn.verso_launcher

import java.io.File

/**
 * The favorites as last shown, so a cold start draws home at once instead of waiting for the
 * enumeration. Disposable — no fsync; a lost or stale file costs one slow frame. Used on VersoApp.io;
 * [read]'s log is the caller's.
 */
internal object HomeCache {

    const val FILE = "home-cache.txt"

    /** A line per favorite: ours stays far smaller ([readDisposable]). */
    const val MAX_BYTES = 1_000_000L

    /** A tab or line break in a label would break the line format. Compiled once, not per favorite. */
    private val LINE_BREAKERS = Regex("[\\t\\r\\n]")

    /** One "key<TAB>label" line per favorite. */
    fun format(favorites: List<AppEntry>): String =
        favorites.joinToString("") { "${it.key}\t${it.label.replace(LINE_BREAKERS, " ")}\n" }

    /** A line whose key [AppEntry.keyOf] could not have built is skipped. */
    fun parse(text: String): List<AppEntry> = text.lineSequence().mapNotNull { line ->
        val label = line.substringAfter('\t', "").ifEmpty { return@mapNotNull null }
        val parts = AppEntry.parseKey(line.substringBefore('\t', "")) ?: return@mapNotNull null
        AppEntry(label, parts.packageName, parts.className, label, parts.profile)
    }.toList()

    /** The cached favorites; none when there is no cache, or one that is too large or unreadable. */
    fun read(dir: File, log: (String, Exception?) -> Unit): List<AppEntry> =
        readDisposable(dir, FILE, MAX_BYTES, absent = emptyList(), log = log, parse = ::parse).orEmpty()

    fun write(dir: File, favorites: List<AppEntry>) = writeDisposable(dir, FILE, format(favorites))
}
