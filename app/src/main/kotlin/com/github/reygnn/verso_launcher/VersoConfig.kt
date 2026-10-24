package com.github.reygnn.verso_launcher

/**
 * The whole editable state, mirroring verso.json 1:1 — the single source of truth for
 * hidden/favorite/name/tag membership, keyed by [AppEntry.key].
 *
 * Immutable: an edit is a new config ([copy]), so one is handed to the IO thread or kept as an
 * undo point as it is, with no copy taken. The collections are read-only types and every producer
 * (ConfigJson, LauncherState) builds them fresh; nobody holds a mutable view of them.
 *
 * Equality is content, as the data class gives it: the favorites in their order (it ranks the
 * home page), the hidden set and the maps whatever their order, and inside one app's tag list the
 * order counts.
 */
internal data class VersoConfig(
    val hidden: Set<String> = emptySet(),
    // In home-page order, no duplicates (every producer keeps it so).
    val favorites: List<String> = emptyList(),
    val names: Map<String, String> = emptyMap(),
    // Canonical tags (LauncherLogic.parseTags), first-entered order; no empty lists.
    val tags: Map<String, List<String>> = emptyMap(),
    /** The plain drawer most used first ("%"), not A to Z. */
    val byUsage: Boolean = false,
)
