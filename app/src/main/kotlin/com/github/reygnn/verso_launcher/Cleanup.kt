package com.github.reygnn.verso_launcher

/** [emptyProfiles]: profiles that answered with no apps — maybe just being set up or torn down,
 *  so "~cleanup" keeps their entries. */
internal class Enumeration(
    val apps: List<AppEntry>,
    val complete: Boolean,
    val emptyProfiles: Set<Long> = emptySet(),
)

/** What "~cleanup" can do with the apps as enumerated ([Cleanup.plan]). */
internal sealed interface CleanupPlan {
    data object Incomplete : CleanupPlan
    data object NothingToDo : CleanupPlan
    data class Offer(val orphans: Orphans) : CleanupPlan
}

/** How a confirmed "~cleanup" ended ([LauncherState.cleanup]). */
internal sealed interface CleanupOutcome {
    data object Refused : CleanupOutcome
    data object Incomplete : CleanupOutcome
    data object NothingToDo : CleanupOutcome
    data object UndoFailed : CleanupOutcome
    data class Done(val undoName: String, val keys: Set<String>) : CleanupOutcome
}

internal object Cleanup {

    /** Null/empty [activities]: the package's code is not loaded (unmounted, kept data) — keep it.
     *  Activities without [className]: renamed in an update — gone. */
    fun activityStillThere(className: String, activities: List<String>?): Boolean =
        activities.isNullOrEmpty() || className in activities

    /**
     * A key of another profile (hard rule 14): [user] null — the profile is gone, and the entry with
     * it; there but not [listed] — one the system shows no launcher, kept without asking; listed —
     * [packageThere] asks the system. Generic in the user, so this stays free of Android types.
     */
    fun <U : Any> otherProfileStillThere(user: U?, listed: Boolean, packageThere: (U) -> Boolean): Boolean = when {
        user == null -> false
        !listed -> true
        else -> packageThere(user)
    }

    /**
     * Hard rule 14. [ownSerial]: a key carrying the launcher's own profile serial ("pkg/cls#0")
     * matches no app (own apps are keyed without one), yet the system would call it "still
     * there" — so it counts as gone. It comes from a config of another device or user.
     */
    fun plan(
        config: VersoConfig,
        found: Enumeration?,
        ownSerial: Long? = null,
        stillThere: (KeyParts) -> Boolean,
    ): CleanupPlan {
        if (found == null || !found.complete) return CleanupPlan.Incomplete
        val installed = found.apps.mapTo(HashSet()) { it.key }
        val kept = { key: String ->
            val parts = AppEntry.parseKey(key)
            when {
                parts == null -> false
                parts.profile != null && parts.profile == ownSerial -> false
                parts.profile != null && parts.profile in found.emptyProfiles -> true
                else -> stillThere(parts)
            }
        }
        val orphans = LauncherLogic.orphans(config, installed, kept)
        return if (orphans.isEmpty) CleanupPlan.NothingToDo else CleanupPlan.Offer(orphans)
    }
}
