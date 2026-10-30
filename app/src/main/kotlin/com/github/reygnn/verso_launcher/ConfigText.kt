package com.github.reygnn.verso_launcher

import java.io.InputStream
import java.util.Locale

/**
 * The config as the user writes it: a text file, one entry per line, that Verso reads and never
 * writes. Pure: [parse], and [resolve] against the app list.
 *
 *     # a comment
 *     fav WhatsApp
 *     fav org.mozilla.firefox
 *     hide com.samsung.android.bixby.agent
 *     name com.google.android.apps.maps = Maps
 *     tag Firefox = web, browser
 *
 * The favorites keep the file's order. An app is named by its key ("package/class", with
 * "#serial" for another profile, hard rule 8), its package (every launcher activity of it in the
 * personal profile) or its system label as the system shows it ("Gmail (Work)" for a work copy),
 * matched folded (hard rule 9) and never by a custom name. A name or tag list given twice: the last
 * name wins, the tags add up.
 *
 * @property badLines the lines that are no entry; the rest of the file still counts.
 * @property refs every entry's line and the app it names, in file order, for [unresolved].
 */
internal data class ConfigText(
    val favorites: List<String> = emptyList(),
    val hidden: List<String> = emptyList(),
    val names: List<Pair<String, String>> = emptyList(),
    val tags: List<Pair<String, List<String>>> = emptyList(),
    val badLines: List<ConfigLine> = emptyList(),
    val refs: List<ConfigRef> = emptyList(),
) {
    companion object {

        fun parse(text: String): ConfigText {
            val lines = Lines()
            text.removePrefix(BOM).lineSequence().forEachIndexed { i, raw ->
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith('#')) return@forEachIndexed
                val numbered = ConfigLine(i + 1, line)
                val ref = lines.add(line)
                if (ref == null) lines.bad += numbered else lines.refs += ConfigRef(numbered, ref)
            }
            return ConfigText(lines.favorites, lines.hidden, lines.names, lines.tags, lines.bad, lines.refs)
        }

        /**
         * The text of [input], or null if it holds more than [maxBytes]. One byte past the cap is
         * read to tell, so a file too large is refused whole, never cut (hard rule 7).
         *
         * Why: a real config is a few KB. A file this big is not one, and is not read into memory.
         */
        fun readCapped(input: InputStream, maxBytes: Int = MAX_FILE_BYTES): String? {
            val bytes = input.readNBytes(maxBytes + 1)
            return if (bytes.size > maxBytes) null else bytes.toString(Charsets.UTF_8)
        }

        /** "ref = value" taken apart at the first "=", or null when either side is empty. */
        private fun split(rest: String): Pair<String, String>? {
            val eq = rest.indexOf('=')
            if (eq < 0) return null
            val ref = rest.substring(0, eq).trim()
            val value = rest.substring(eq + 1).trim()
            return if (ref.isEmpty() || value.isEmpty()) null else ref to value
        }

        /**
         * The config [text] sets for [apps] (system labels; byUsage is not the file's). A key
         * stands even for an app that is gone (hard rule 14); a package or label of one that is
         * gone sets nothing until it is back.
         */
        fun resolve(text: ConfigText, apps: List<AppEntry>): VersoConfig {
            val index = Index(apps)
            val favorites = LinkedHashSet<String>()
            text.favorites.forEach { favorites += index.keys(it) }
            val hidden = LinkedHashSet<String>()
            text.hidden.forEach { hidden += index.keys(it) }
            val names = LinkedHashMap<String, String>()
            for ((ref, name) in text.names) index.keys(ref).forEach { names[it] = name }
            val tags = LinkedHashMap<String, List<String>>()
            for ((ref, list) in text.tags) {
                for (key in index.keys(ref)) {
                    tags[key] = (tags[key].orEmpty() + list).distinct().take(LauncherLogic.MAX_TAGS_PER_APP)
                }
            }
            return VersoConfig(hidden = hidden, favorites = favorites.toList(), names = names, tags = tags)
        }

        /**
         * The lines of [text] that name no app of [apps]: a typo, an app that is gone, or one of a
         * profile that was not read. Their entries stay (hard rule 14); this only says so.
         */
        fun unresolved(text: ConfigText, apps: List<AppEntry>): List<ConfigLine> {
            val index = Index(apps)
            return text.refs.filter { ref -> index.keys(ref.ref).none(index::installed) }.map { it.line }
        }

        private const val FAV = "fav"
        private const val HIDE = "hide"
        private const val NAME = "name"
        private const val TAG = "tag"
        private const val MAX_FILE_BYTES = 1024 * 1024
        private const val BOM = "\uFEFF"
        private val WHITESPACE = Regex("\\s+")
    }

    /** The entries of a text, gathered line by line. */
    private class Lines {
        val favorites = ArrayList<String>()
        val hidden = ArrayList<String>()
        val names = ArrayList<Pair<String, String>>()
        val tags = ArrayList<Pair<String, List<String>>>()
        val bad = ArrayList<ConfigLine>()
        val refs = ArrayList<ConfigRef>()

        /** The app [line] names, or null when it is no entry. */
        fun add(line: String): String? {
            val parts = line.split(WHITESPACE, limit = 2)
            val rest = parts.getOrElse(1) { "" }
            return when (parts[0].lowercase(Locale.ROOT)) {
                FAV -> rest.takeIf { it.isNotEmpty() }?.also { favorites += it }
                HIDE -> rest.takeIf { it.isNotEmpty() }?.also { hidden += it }
                NAME -> split(rest)?.let { (ref, value) ->
                    LauncherLogic.cleanName(value).takeIf { it.isNotEmpty() }?.let { names += ref to it; ref }
                }
                TAG -> split(rest)?.let { (ref, value) ->
                    LauncherLogic.parseTags(value).takeIf { it.isNotEmpty() }?.let { tags += ref to it; ref }
                }
                else -> null
            }
        }
    }

    /** The apps by key, by personal package and by folded system label. */
    private class Index(apps: List<AppEntry>) {
        private val byKey = apps.associateBy { it.key }
        private val byPackage = apps.filter { it.profile == null }.groupBy { it.packageName }
        private val byLabel = apps.groupBy { LauncherLogic.foldLabel(it.systemLabel) }

        /** Whether [key] is an app of the list, not a key of one that is gone. */
        fun installed(key: String): Boolean = key in byKey

        /** An app's key first, then a package, then a label; a key of an app that is gone last. */
        fun keys(ref: String): List<String> {
            if (ref in byKey) return listOf(ref)
            val found = byPackage[ref] ?: byLabel[LauncherLogic.foldLabel(ref)]
            return found?.map { it.key } ?: if (AppEntry.parseKey(ref) != null) listOf(ref) else emptyList()
        }
    }
}

/** A line of the user's file: its 1-based [number] and its [text], trimmed. */
internal data class ConfigLine(val number: Int, val text: String)

/** An entry's [line] and the app it names ([ref]: a key, a package or a label). */
internal data class ConfigRef(val line: ConfigLine, val ref: String)
