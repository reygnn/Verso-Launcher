package com.github.reygnn.verso_launcher

import java.io.File

/**
 * The favorites as last shown on the home page — key and label — kept in a small file so a cold
 * start can draw them at once, instead of an empty page until every app of every profile has been
 * enumerated (a label per app: a few hundred milliseconds on a cold process, which is the moment
 * a launcher is most often seen being slow). Replaced by the real list as soon as it is loaded.
 *
 * A cache, nothing more: not durable state (verso.json is), so no fsync and no .bak — a lost or
 * stale file only means one cold start without the instant draw, or a favorite shown a moment
 * longer than it exists (a tap on it reports "not found"). Read and written on VersoApp.io.
 */
internal object HomeCache {

    const val FILE = "home-cache.txt"

    /** One "key<TAB>label" line per favorite, in order. Tabs and line breaks in a label (none in
     *  practice: names are cleaned) become spaces so they cannot break the format. */
    fun format(favorites: List<AppEntry>): String =
        favorites.joinToString("") { "${it.key}\t${it.label.replace(Regex("[\\t\\r\\n]"), " ")}\n" }

    /** The entries [format] wrote; a line that is not "pkg/cls[#serial]<TAB>label" is skipped. */
    fun parse(text: String): List<AppEntry> = text.lineSequence().mapNotNull { line ->
        val key = line.substringBefore('\t', "").ifEmpty { return@mapNotNull null }
        val label = line.substringAfter('\t', "").ifEmpty { return@mapNotNull null }
        val component = key.substringBefore('#')
        val profile = if ('#' in key) key.substringAfter('#').toLongOrNull() ?: return@mapNotNull null else null
        val pkg = component.substringBefore('/', "").ifEmpty { return@mapNotNull null }
        val cls = component.substringAfter('/', "").ifEmpty { return@mapNotNull null }
        AppEntry.of(label, pkg, cls, label, profile)
    }.toList()

    /** The cached favorites, or an empty list when there is no (readable) cache. Never throws. */
    fun read(dir: File): List<AppEntry> = try {
        File(dir, FILE).takeIf { it.isFile }?.readText()?.let(::parse).orEmpty()
    } catch (e: Exception) {
        emptyList()
    }

    /** Replace the cache with [favorites] (temp + rename, so a reader never sees half a file).
     *  Never throws — a cache that could not be written is simply not there next time. */
    fun write(dir: File, favorites: List<AppEntry>) {
        try {
            val tmp = File(dir, "$FILE.tmp")
            tmp.writeText(format(favorites))
            if (!tmp.renameTo(File(dir, FILE))) tmp.delete()
        } catch (_: Exception) {
        }
    }
}
