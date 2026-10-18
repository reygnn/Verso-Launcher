package com.github.reygnn.verso_launcher

/** What [LauncherState.config] hands out: the config, read-only. */
internal interface ConfigView {
    val hidden: Set<String>
    val favorites: Set<String>
    val names: Map<String, String>
    val tags: Map<String, List<String>>
    /** The plain drawer most used first ("%"), not A to Z. */
    val byUsage: Boolean
    fun snapshot(): VersoConfig
}

/**
 * The whole editable state, mirroring verso.json 1:1 — the single source of truth for
 * hidden/favorite/name/tag membership, keyed by [AppEntry.key].
 */
internal class VersoConfig(
    override val hidden: MutableSet<String> = linkedSetOf(),
    // A linked set: favorites need both their order (the home page's) and O(1) membership.
    override val favorites: MutableSet<String> = linkedSetOf(),
    override val names: MutableMap<String, String> = linkedMapOf(),
    // Canonical tags (LauncherLogic.parseTags), first-entered order; no empty lists.
    override val tags: MutableMap<String, MutableList<String>> = linkedMapOf(),
    override var byUsage: Boolean = false,
) : ConfigView {
    /**
     * Same favorites in the same order, same hidden set, names, tags and order; map and set order does
     * not count, the order inside one app's tag list does.
     */
    fun sameContent(other: VersoConfig): Boolean =
        favorites.toList() == other.favorites.toList() &&
            hidden == other.hidden &&
            names == other.names &&
            tags == other.tags &&
            byUsage == other.byUsage

    /** A copy for another thread: these collections are not thread-safe and are mutated on main. */
    override fun snapshot() = VersoConfig(
        LinkedHashSet(hidden),
        LinkedHashSet(favorites),
        LinkedHashMap(names),
        // Deep: the inner tag lists must not be shared with the live config.
        LinkedHashMap<String, MutableList<String>>().also { c ->
            for ((k, v) in tags) c[k] = ArrayList(v)
        },
        byUsage,
    )
}
