package com.github.reygnn.verso_launcher

/**
 * An enumeration's result: the apps; whether every profile and app was read; and the profiles
 * that answered with no apps at all — no error, but nothing to tell a profile with no apps from
 * one the system will not show right now (being set up or torn down), so "~cleanup" keeps their
 * entries instead of calling them gone.
 */
internal class Enumeration(
    val apps: List<AppEntry>,
    val complete: Boolean,
    val emptyProfiles: Set<Long> = emptySet(),
)

/** What "~cleanup" can do with the apps as enumerated ([Cleanup.plan]). */
internal sealed interface CleanupPlan {
    /** Not everything was read: an app it did not see would look uninstalled. Do nothing. */
    data object Incomplete : CleanupPlan
    /** Every entry belongs to an app that is still there. */
    data object NothingToDo : CleanupPlan
    /** These entries belong to apps that are gone. */
    data class Offer(val orphans: Orphans) : CleanupPlan
}

/** How a confirmed "~cleanup" ended ([LauncherState.cleanup]). */
internal sealed interface CleanupOutcome {
    /** The config is not loaded yet, or the IO thread refused the task. */
    data object Refused : CleanupOutcome
    /** The check before removing found an incomplete enumeration: nothing removed. */
    data object Incomplete : CleanupOutcome
    /** Nothing that was offered is still an orphan (the app came back meanwhile). */
    data object NothingToDo : CleanupOutcome
    /** The config was replaced (a restore) while the check ran: nothing removed —
     *  the counts confirmed and the undo point were for the config before it. */
    data object Superseded : CleanupOutcome
    /** The undo point could not be written: nothing removed (hard rule 14). */
    data object UndoFailed : CleanupOutcome
    /** [keys] removed, after the undo point [undoName] was written. */
    data class Done(val undoName: String, val keys: Set<String>) : CleanupOutcome
}

internal object Cleanup {

    /**
     * The personal-profile verdict once the exact activity was not found but its package was:
     * [activities] is the package's activity class names, or null/empty when its code is not
     * loaded (on unmounted storage, uninstalled with its data kept) — nothing to judge by, so it
     * stays. A package that lists activities without [className] renamed it in an update: gone.
     */
    fun activityStillThere(className: String, activities: List<String>?): Boolean =
        activities.isNullOrEmpty() || className in activities

    /**
     * What "~cleanup" may offer for [config], given the enumeration [found] (null: it failed).
     * Only a COMPLETE enumeration counts (hard rule 14). A key no app can have
     * ([AppEntry.parseKey] says null) is gone without asking; an entry of a profile that
     * answered empty is kept; and an app still on the device in some form — disabled, its icon
     * alias switched off, on unmounted storage, in a profile that exists but was not listed —
     * is never offered: [stillThere] asks the system for one. Runs on the IO thread.
     *
     * [ownSerial] is the serial of the launcher's own profile. A key carrying it ("pkg/cls#0")
     * matches no app — the launcher's own apps are keyed without a serial (AppEntry.keyOf) — yet
     * the system would answer "still there" for it, so it could never be cleaned up. It turns
     * up in a config restored from another device or Android user (whose work profile had that
     * serial), or edited by hand: gone, like a key no app can have.
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
