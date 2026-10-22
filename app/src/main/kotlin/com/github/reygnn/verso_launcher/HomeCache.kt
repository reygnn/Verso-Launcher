package com.github.reygnn.verso_launcher

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

    fun parse(text: String): List<AppEntry> = text.lineSequence().mapNotNull { line ->
        val key = line.substringBefore('\t', "").ifEmpty { return@mapNotNull null }
        val label = line.substringAfter('\t', "").ifEmpty { return@mapNotNull null }
        val component = key.substringBefore('#')
        val profile = if ('#' in key) key.substringAfter('#').toLongOrNull() ?: return@mapNotNull null else null
        val pkg = component.substringBefore('/', "").ifEmpty { return@mapNotNull null }
        val cls = component.substringAfter('/', "").ifEmpty { return@mapNotNull null }
        AppEntry.of(label, pkg, cls, label, profile)
    }.toList()

    // a disposable cache: unreadable = none
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    fun read(dir: File): List<AppEntry> = try {
        File(dir, FILE).takeIf { it.isFile }?.readText()?.let(::parse).orEmpty()
    } catch (e: Exception) {
        emptyList()
    }

    fun write(dir: File, favorites: List<AppEntry>) {
        val tmp = File(dir, "$FILE.tmp")
        tmp.writeText(format(favorites))
        if (!tmp.renameTo(File(dir, FILE))) tmp.delete()
    }
}
