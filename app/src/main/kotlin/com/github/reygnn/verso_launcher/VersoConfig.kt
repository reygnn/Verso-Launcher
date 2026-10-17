package com.github.reygnn.verso_launcher

/**
 * The whole editable state, mirroring verso.json 1:1 — the single source of truth for
 * hidden/favorite/name/tag membership, keyed by [AppEntry.key].
 */
internal class VersoConfig(
    val hidden: MutableSet<String> = linkedSetOf(),
    // A linked set: favorites need both their order (the home page's) and O(1) membership.
    val favorites: MutableSet<String> = linkedSetOf(),
    val names: MutableMap<String, String> = linkedMapOf(),
    // Canonical tags (LauncherLogic.parseTags), first-entered order; no empty lists.
    val tags: MutableMap<String, MutableList<String>> = linkedMapOf(),
) {
    /**
     * Same favorites in the same order, same hidden set, names and tags; map and set order does
     * not count, the order inside one app's tag list does.
     */
    fun sameContent(other: VersoConfig): Boolean =
        favorites.toList() == other.favorites.toList() &&
            hidden == other.hidden &&
            names == other.names &&
            tags == other.tags

    /** A copy for another thread: these collections are not thread-safe and are mutated on main. */
    fun snapshot() = VersoConfig(
        LinkedHashSet(hidden),
        LinkedHashSet(favorites),
        LinkedHashMap(names),
        // Deep: the inner tag lists must not be shared with the live config.
        LinkedHashMap<String, MutableList<String>>().also { c ->
            for ((k, v) in tags) c[k] = ArrayList(v)
        },
    )
}
