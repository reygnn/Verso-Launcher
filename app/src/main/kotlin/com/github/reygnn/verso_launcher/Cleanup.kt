package com.github.reygnn.verso_launcher

/** An enumeration's result: the apps, and whether every profile and app was read. */
internal class Enumeration(val apps: List<AppEntry>, val complete: Boolean)

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
    /** The undo point could not be written: nothing removed (hard rule 14). */
    data object UndoFailed : CleanupOutcome
    /** [keys] removed, after the undo point [undoName] was written. */
    data class Done(val undoName: String, val keys: Set<String>) : CleanupOutcome
}

internal object Cleanup {

    /**
     * What "~cleanup" may offer for [config], given the enumeration [found] (null: it failed).
     * Only a COMPLETE enumeration counts (hard rule 14), and an entry whose package is still on
     * the device in some form — disabled, archived, on unmounted storage, an icon alias switched
     * off, in a profile that exists but was not listed — is never offered: [stillThere] says so
     * for a key. Runs on the IO thread ([stillThere] asks the system).
     */
    fun plan(config: VersoConfig, found: Enumeration?, stillThere: (String) -> Boolean): CleanupPlan {
        if (found == null || !found.complete) return CleanupPlan.Incomplete
        val installed = found.apps.mapTo(HashSet()) { it.key }
        val orphans = LauncherLogic.orphans(config, installed, kept = stillThere)
        return if (orphans.isEmpty) CleanupPlan.NothingToDo else CleanupPlan.Offer(orphans)
    }
}
