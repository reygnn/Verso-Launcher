package com.github.reygnn.verso_launcher

/**
 * What the user's file says, resolved against the apps ([ConfigText.resolve]) — the single
 * source of truth for hidden/favorite/name/tag membership, keyed by [AppEntry.key].
 *
 * Immutable: a new text or list is a new config, so one is handed on as it is, with no copy
 * taken. The collections are read-only types and every producer (ConfigText and
 * LauncherState) builds them fresh; nobody holds a mutable view of them.
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
