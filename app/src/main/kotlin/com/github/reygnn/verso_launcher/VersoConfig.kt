package com.github.reygnn.verso_launcher

import java.util.Objects

/**
 * The whole editable state, mirroring verso.json 1:1 — the single source of truth for
 * hidden/favorite/name/tag membership, keyed by [AppEntry.key].
 *
 * Immutable: an edit is a new config ([copy]), so one is handed to the IO thread or kept as an
 * undo point as it is, with no copy taken. The collections are read-only types and every producer
 * (ConfigJson, LauncherState) builds them fresh; nobody holds a mutable view of them.
 *
 * Equality is content: the favorites in their order (it ranks the home page), the other sets and
 * maps whatever their order, and inside one app's tag list the order counts.
 */
internal data class VersoConfig(
    val hidden: Set<String> = emptySet(),
    // A Set for O(1) membership, in insertion order: the favorites' order is the home page's.
    val favorites: Set<String> = emptySet(),
    val names: Map<String, String> = emptyMap(),
    // Canonical tags (LauncherLogic.parseTags), first-entered order; no empty lists.
    val tags: Map<String, List<String>> = emptyMap(),
    /** The plain drawer most used first ("%"), not A to Z. */
    val byUsage: Boolean = false,
) {
    override fun equals(other: Any?): Boolean =
        other is VersoConfig &&
            favorites.toList() == other.favorites.toList() &&
            hidden == other.hidden &&
            names == other.names &&
            tags == other.tags &&
            byUsage == other.byUsage

    override fun hashCode(): Int = Objects.hash(favorites.toList(), hidden, names, tags, byUsage)
}
