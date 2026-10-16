package com.github.reygnn.verso_launcher

/**
 * The IO-thread half of "~backup" — lifted out of MainActivity.backup so the rule it carries is
 * pinned by a JVM test instead of living untested in an Activity (CLAUDE.md, hard rule 5): before
 * the first load, memory still holds the empty default, so the file on disk is what gets backed
 * up; and when THAT cannot be read either, nothing is written — the previous backup, the only
 * copy off the device, stays rather than being replaced by an empty config.
 */
internal object BackupSteps {

    sealed interface Outcome {
        /** The backup was published as [name]. */
        data class Written(val name: String) : Outcome
        /** There was a config to back up, but the write failed: the previous backup stays. */
        data object WriteFailed : Outcome
        /** Not loaded, and the file on disk cannot be read: nothing was written. */
        data object Unreadable : Outcome
    }

    /**
     * [inMemory]: the loaded config, serialized on the main thread — null before the first load.
     * Then the file on disk ([loadDisk], null when unreadable) through [serialize]. [write]
     * publishes the backup and gives its name, or null.
     */
    fun run(
        inMemory: String?,
        loadDisk: () -> VersoConfig?,
        serialize: (VersoConfig) -> String,
        write: (String) -> String?,
    ): Outcome {
        val text = inMemory ?: loadDisk()?.let(serialize) ?: return Outcome.Unreadable
        return write(text)?.let { Outcome.Written(it) } ?: Outcome.WriteFailed
    }
}
