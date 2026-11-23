package com.github.reygnn.verso_launcher

import java.text.Collator
import java.util.Locale

/**
 * Which apps a list holds and in which order: the drawer, the favorites, the recents and the tags
 * in use. Pure: no framework, disk or shared state.
 */
internal object AppLists {

    /**
     * [apps] under their custom names from [names]. They arrive sorted (the enumeration sorts on the
     * IO thread), so only a changed label re-sorts — a name can move an app alphabetically.
     */
    fun named(apps: List<AppEntry>, names: Map<String, String>, locale: Locale = Locale.getDefault()): List<AppEntry> {
        var relabelled = false
        val shown = apps.map { e ->
            val label = names[e.key] ?: e.systemLabel
            if (label == e.label) e else e.withLabel(label).also { relabelled = true }
        }
        return if (relabelled) sortedForDrawer(shown, locale) else shown
    }

    /**
     * Alphabetical by the device language's collation (German files Ä under A, Swedish after Z),
     * then folded label and key, so the order is total. A Collator per call: it is not
     * thread-safe, and this runs on the IO thread too.
     */
    fun sortedForDrawer(all: List<AppEntry>, locale: Locale = Locale.getDefault()): List<AppEntry> {
        val collator = Collator.getInstance(locale)
        return all.sortedWith(
            compareBy<AppEntry, String>(collator) { it.labelLower }
                .thenBy { it.labelLower }
                .thenBy { it.key },
        )
    }

    fun favoritesInDisplayOrder(all: List<AppEntry>, favorites: Collection<String>): List<AppEntry> {
        val rank = favorites.withIndex().associate { (i, p) -> p to i }
        return all.filter { it.key in rank }.sortedBy { rank[it.key] }
    }

    /** [slots]: per slot (phone, messenger, …) the candidate keys, preferred first. An app is picked once. */
    fun pickDefaultFavorites(all: List<AppEntry>, slots: List<List<String>>): List<String> {
        val launchable = all.mapTo(HashSet()) { it.key }
        val picked = LinkedHashSet<String>()
        for (candidates in slots) {
            candidates.firstOrNull { it in launchable }?.let { picked += it }
        }
        return picked.toList()
    }

    /** Everything not hidden, plus every favorite: favoriting overrides hiding. */
    fun drawer(all: List<AppEntry>, hidden: Set<String>, favorites: Collection<String>): List<AppEntry> =
        all.filter { it.key !in hidden || it.key in favorites }

    /** Favorites last, in rank order — under isStackFromBottom the first favorite sits nearest the prompt. */
    fun orderWithFavorites(apps: List<AppEntry>, favorites: Collection<String>): List<AppEntry> {
        if (favorites.isEmpty()) return apps
        val rank = favorites.withIndex().associate { (i, p) -> p to i }
        val (favs, rest) = apps.partition { it.key in rank }
        return rest + favs.sortedBy { rank[it.key] }
    }

    /** The newest [limit] of the apps still there, reversed: under isStackFromBottom the most
     *  recent sits nearest the prompt (the Enter target). */
    fun recentsInDisplayOrder(
        all: List<AppEntry>, recentKeys: List<String>, limit: Int = LaunchHistory.RECENTS_LIMIT,
    ): List<AppEntry> {
        val byKey = all.associateBy { it.key }
        return recentKeys.mapNotNull { byKey[it] }.take(limit).asReversed()
    }

    /** Only tags of installed apps, so every tag in the "#" overview drills into a non-empty list. */
    fun tagsInUse(
        all: List<AppEntry>,
        tags: Map<String, List<String>>,
        locale: Locale = Locale.getDefault(),
    ): List<String> = sortedTags(all.flatMap { tags[it.key].orEmpty() }.distinct(), locale)

    /** The same collation as [sortedForDrawer], then plain string order, so the order is total. */
    fun sortedTags(tags: Collection<String>, locale: Locale = Locale.getDefault()): List<String> {
        val collator = Collator.getInstance(locale)
        return tags.sortedWith(compareBy<String, String>(collator) { it }.thenBy { it })
    }
}
