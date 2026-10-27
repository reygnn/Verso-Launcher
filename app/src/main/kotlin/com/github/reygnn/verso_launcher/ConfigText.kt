package com.github.reygnn.verso_launcher

import java.util.Locale

/**
 * The config as the user writes it: a text file, one entry per line, that Verso reads and never
 * writes over. Pure: [parse], [resolve] against the app list, [format] for a new file.
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
 * @property badLines the 1-based numbers of the lines that are no entry; the rest of the file
 *   still counts.
 */
internal data class ConfigText(
    val favorites: List<String> = emptyList(),
    val hidden: List<String> = emptyList(),
    val names: List<Pair<String, String>> = emptyList(),
    val tags: List<Pair<String, List<String>>> = emptyList(),
    val badLines: List<Int> = emptyList(),
) {
    val entries: Int get() = favorites.size + hidden.size + names.size + tags.size

    /** Lines, and not one of them an entry: a picked file that is no config at all. */
    val isNoConfig: Boolean get() = entries == 0 && badLines.isNotEmpty()

    companion object {

        fun parse(text: String): ConfigText {
            val lines = Lines()
            text.removePrefix(BOM).lineSequence().forEachIndexed { i, raw ->
                val line = raw.trim()
                if (line.isNotEmpty() && !line.startsWith('#') && !lines.add(line)) lines.bad += i + 1
            }
            return ConfigText(lines.favorites, lines.hidden, lines.names, lines.tags, lines.bad)
        }

        /** "ref = value" taken apart at the first "=", or null when either side is empty. */
        private fun split(rest: String): Pair<String, String>? {
            val eq = rest.indexOf('=')
            if (eq < 0) return null
            val ref = rest.substring(0, eq).trim()
            val value = rest.substring(eq + 1).trim()
            return if (ref.isEmpty() || value.isEmpty()) null else ref to value
        }

        /** A config of keys, as verso.json held it: every reference is a key. */
        fun of(config: VersoConfig): ConfigText = ConfigText(
            favorites = config.favorites,
            hidden = config.hidden.toList(),
            names = config.names.toList(),
            tags = config.tags.toList(),
        )

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
                    tags[key] = (tags[key].orEmpty() + list).distinct().take(ConfigJson.MAX_TAGS_PER_APP)
                }
            }
            return VersoConfig(hidden = hidden, favorites = favorites.toList(), names = names, tags = tags)
        }

        /**
         * [config] as a file of its own: [header] as comment lines, then one line per entry. Each
         * app is named by its package where that names it alone, else by its key — never by its
         * label, which changes with the device language; the label stands in a comment above.
         * [apps] tells; an app that is gone keeps its key. [parse] and [resolve] give [config] back.
         */
        fun format(config: VersoConfig, apps: List<AppEntry>, header: List<String>): String {
            val index = Index(apps)
            return buildString {
                header.forEach { append(if (it.isEmpty()) "#\n" else "# $it\n") }
                // One line per key, the app named by ref: (ref) -> line.
                fun section(entries: List<Pair<String, (String) -> String>>) {
                    if (entries.isEmpty()) return
                    append('\n')
                    for ((key, line) in entries) {
                        index.label(key)?.let { append("# ").append(it).append('\n') }
                        append(line(index.ref(key))).append('\n')
                    }
                }
                section(config.favorites.map { key -> key to { ref: String -> "$FAV $ref" } })
                section(config.hidden.map { key -> key to { ref: String -> "$HIDE $ref" } })
                section(config.names.map { (key, name) -> key to { ref: String -> "$NAME $ref = $name" } })
                section(config.tags.map { (key, list) -> key to { ref: String -> "$TAG $ref = ${list.joinToString(", ")}" } })
            }
        }

        private const val FAV = "fav"
        private const val HIDE = "hide"
        private const val NAME = "name"
        private const val TAG = "tag"
        private const val BOM = "\uFEFF"
        private val WHITESPACE = Regex("\\s+")
    }

    /** The entries of a text, gathered line by line. */
    private class Lines {
        val favorites = ArrayList<String>()
        val hidden = ArrayList<String>()
        val names = ArrayList<Pair<String, String>>()
        val tags = ArrayList<Pair<String, List<String>>>()
        val bad = ArrayList<Int>()

        /** False when [line] is no entry. */
        fun add(line: String): Boolean {
            val parts = line.split(WHITESPACE, limit = 2)
            val rest = parts.getOrElse(1) { "" }
            return when (parts[0].lowercase(Locale.ROOT)) {
                FAV -> rest.isNotEmpty() && favorites.add(rest)
                HIDE -> rest.isNotEmpty() && hidden.add(rest)
                NAME -> split(rest)?.let { (ref, value) ->
                    LauncherLogic.cleanName(value).takeIf { it.isNotEmpty() }?.let { names += ref to it }
                } != null
                TAG -> split(rest)?.let { (ref, value) ->
                    LauncherLogic.parseTags(value).takeIf { it.isNotEmpty() }?.let { tags += ref to it }
                } != null
                else -> false
            }
        }
    }

    /** The apps by key, by personal package and by folded system label. */
    private class Index(apps: List<AppEntry>) {
        private val byKey = apps.associateBy { it.key }
        private val byPackage = apps.filter { it.profile == null }.groupBy { it.packageName }
        private val byLabel = apps.groupBy { LauncherLogic.foldLabel(it.systemLabel) }

        /** An app's key first, then a package, then a label; a key of an app that is gone last. */
        fun keys(ref: String): List<String> {
            if (ref in byKey) return listOf(ref)
            val found = byPackage[ref] ?: byLabel[LauncherLogic.foldLabel(ref)]
            return found?.map { it.key } ?: if (AppEntry.parseKey(ref) != null) listOf(ref) else emptyList()
        }

        /** What [keys] maps back to [key] alone: its package if that is all of it, else the key. */
        fun ref(key: String): String {
            val entry = byKey[key] ?: return key
            return if (entry.profile == null && byPackage[entry.packageName]?.size == 1) entry.packageName else key
        }

        /** The app's label as one comment line, or null for an app that is gone. */
        fun label(key: String): String? = byKey[key]?.systemLabel
            ?.map { if (it.isISOControl() || LauncherLogic.isSpoofChar(it)) ' ' else it }
            ?.joinToString("")?.trim()?.takeIf { it.isNotEmpty() }
    }
}
