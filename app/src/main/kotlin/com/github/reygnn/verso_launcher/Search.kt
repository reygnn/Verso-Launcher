package com.github.reygnn.verso_launcher

import java.util.Locale

/**
 * What a prompt finds among the apps: by name, by the start of a name ("*") and by tag ("#").
 * Pure: no framework, disk or shared state.
 */
internal object Search {

    fun search(all: List<AppEntry>, needle: String): List<AppEntry> {
        val n = Labels.matchForm(Labels.foldLabel(needle))
        return if (n.isEmpty()) all else all.filter { it.matchLabel.contains(n) }
    }

    /**
     * [found] (a [search]'s result, in drawer order) ranked by where [needle] matches: at the
     * label's start last, nearest the prompt where Enter launches it; at a later word's start above
     * it; inside a word on top. Each group keeps the drawer order.
     */
    fun ranked(found: List<AppEntry>, needle: String): List<AppEntry> {
        val n = Labels.matchForm(Labels.foldLabel(needle))
        if (n.isEmpty()) return found
        // Ranked once per entry: sortedBy asks its selector again at every comparison, a regex each time.
        return found.map { entry ->
            val label = entry.matchLabel
            entry to when {
                label.startsWith(n) -> 2
                WORD_START.findAll(label).any { label.startsWith(n, it.range.first) } -> 1
                else -> 0
            }
        }.sortedBy { it.second }.map { it.first }
    }

    /** A letter or digit after anything else: where a later word starts. */
    private val WORD_START = Regex("""(?<=[^\p{L}\p{N}])[\p{L}\p{N}]""")

    /**
     * The prefix of a star prompt, or null if [trimmed] is none. The star counts at either end
     * ("a*" == "*a"): where the IME leaves the caret after a programmatic setText is not ours to pin.
     */
    fun starPrefix(trimmed: String): String? =
        if (trimmed.startsWith("*") || trimmed.endsWith("*")) {
            // "* gm" is "*gm": no label starts with a space. "a *" keeps it (the word "a").
            trimmed.removePrefix("*").trimStart().removeSuffix("*")
        } else {
            null
        }

    /** The [AppLists.drawer] narrowed to labels starting with [prefix], favorites last. Hidden apps stay out. */
    fun drawerStartingWith(
        all: List<AppEntry>,
        hidden: Set<String>,
        favorites: Collection<String>,
        prefix: String,
    ): List<AppEntry> {
        val p = Labels.matchForm(Labels.foldLabel(prefix))
        val base = AppLists.drawer(all, hidden, favorites)
        val narrowed = if (p.isEmpty()) base else base.filter { it.matchLabel.startsWith(p) }
        return AppLists.orderWithFavorites(narrowed, favorites)
    }

    /**
     * Apps with a tag starting with [needle] (canonicalised like a stored tag, or it could hold
     * characters no tag can); an empty needle gives every tagged app. Hidden apps are included.
     * When no tag matches, the apps whose package matches ([packageMatches]): "#google", "#samsung".
     */
    fun tagged(all: List<AppEntry>, tags: Map<String, List<String>>, needle: String): List<AppEntry> {
        val n = Labels.canonicalTag(needle)
        val byTag = all.filter { entry ->
            val ts = tags[entry.key].orEmpty()
            if (n.isEmpty()) ts.isNotEmpty() else ts.any { it.startsWith(n) }
        }
        if (byTag.isNotEmpty() || n.isEmpty()) return byTag
        return all.filter { packageMatches(it.packageName, n) }
    }

    /** A package segment after the first starts with [needle]; the first ("com", "de") would match nearly everything. */
    fun packageMatches(packageName: String, needle: String): Boolean =
        packageName.lowercase(Locale.ROOT).split('.').drop(1).any { it.startsWith(needle) }
}
