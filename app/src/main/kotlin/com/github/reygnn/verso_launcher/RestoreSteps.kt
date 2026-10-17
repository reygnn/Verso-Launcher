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
        /** The undo point was written, but the restored config could not be: nothing was adopted,
         *  the config on disk is the one from before. */
        data object WriteFailed : Outcome
        /** Undo point [undoName] written, then the restored config persisted. */
        data class Done(val undoName: String) : Outcome
    }

    /**
     * 1. If [restored] is the [current] config, stop: an undo point now would replace the real
     *    one (the config from before the first restore) with a copy of the restored config.
     * 2. Write the undo point — [current] — through [writeUndo] (the backup's published name,
     *    or null). No undo point, no restore.
     * 3. Persist [restored] through [writeConfig] right away, in the same IO task, so the restore
     *    survives even if the main-thread hand-off never runs. Its result counts: WriteFailed.
     * 4. Only then [sweepUndo] the previous undo point — possibly the very file being restored
     *    ("~restore-saf" of a verso-pre-restore file): it must not be gone before the restore is
     *    saved. A crash before this step leaves one extra undo row, which the next one sweeps.
     */
    fun run(
        current: VersoConfig,
        restored: VersoConfig,
        writeUndo: (String) -> String?,
        writeConfig: (String) -> Boolean,
        sweepUndo: () -> Unit,
    ): Outcome {
        if (current.sameContent(restored)) return Outcome.Unchanged
        val undo = writeUndo(ConfigJson.serialize(current)) ?: return Outcome.UndoFailed
        if (!writeConfig(ConfigJson.serialize(restored))) return Outcome.WriteFailed
        sweepUndo()
        return Outcome.Done(undo)
    }
}
