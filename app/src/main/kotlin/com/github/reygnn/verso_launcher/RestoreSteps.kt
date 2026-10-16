package com.github.reygnn.verso_launcher

/**
 * The IO-thread half of a restore, in its load-bearing order (CLAUDE.md, hard rule 7) — lifted
 * out of MainActivity.adoptRestored so the order is pinned by a JVM test instead of living
 * untested in an Activity. The Activity reads and parses the document, runs this on the IO
 * thread, and adopts the result on the main thread.
 */
internal object RestoreSteps {

    sealed interface Outcome {
        /** The document holds the config already in use: nothing was written. */
        data object Unchanged : Outcome
        /** The undo point could not be written: nothing else was written either. */
        data object UndoFailed : Outcome
        /** Undo point [undoName] written, then the restored config persisted. */
        data class Done(val undoName: String) : Outcome
        /** The current config could not be read, so there was nothing to keep as an undo point:
         *  its files were set aside as [setAside] (renamed, not deleted), then the restored
         *  config persisted — the way out of an unreadable config. */
        data class ReplacedUnreadable(val setAside: List<String>) : Outcome
        /** The unreadable config could not be set aside: nothing was written. */
        data object SetAsideFailed : Outcome
    }

    /**
     * 1. If [restored] is the [current] config, stop: an undo point now would replace the real
     *    one (the config from before the first restore) with a copy of the restored config.
     * 2. Write the undo point — [current] — through [writeUndo] (the backup's published name,
     *    or null). No undo point, no restore — with one exception: [current] null, the config
     *    on disk unreadable (ConfigStore.load). There is nothing readable to keep then, and the
     *    restore is the way out of that state. Its files are set aside instead ([setAside]:
     *    renamed, never deleted, never written over) — and if THAT fails, nothing is written. If
     *    the file reads after all, its config is the undo point as usual.
     * 3. Persist [restored] through [writeConfig] right away, in the same IO task: writing the
     *    undo point has just replaced the previous one (possibly the very file being restored),
     *    so from here the restore must survive even if the main-thread hand-off never runs.
     */
    fun run(
        current: VersoConfig?,
        restored: VersoConfig,
        writeUndo: (String) -> String?,
        writeConfig: (String) -> Boolean,
        setAside: () -> ConfigStore.SetAside,
    ): Outcome {
        val outgoing = current ?: when (val aside = setAside()) {
            // Readable after all (a passing read error): the normal path, with its undo point.
            is ConfigStore.SetAside.Readable -> aside.config
            is ConfigStore.SetAside.Moved -> {
                writeConfig(ConfigJson.serialize(restored))
                return Outcome.ReplacedUnreadable(aside.names)
            }
            ConfigStore.SetAside.Failed -> return Outcome.SetAsideFailed
        }
        if (outgoing.sameContent(restored)) return Outcome.Unchanged
        val undo = writeUndo(ConfigJson.serialize(outgoing)) ?: return Outcome.UndoFailed
        writeConfig(ConfigJson.serialize(restored))
        return Outcome.Done(undo)
    }
}
