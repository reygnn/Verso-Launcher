package com.github.reygnn.verso_launcher

/** The IO-thread half of a restore, in its load-bearing order (hard rule 7). */
internal object RestoreSteps {

    sealed interface Outcome {
        data object Unchanged : Outcome
        data object UndoFailed : Outcome
        /** Undo point written, restored config not: nothing adopted, disk unchanged. */
        data object WriteFailed : Outcome
        data class Done(val undoName: String) : Outcome
    }

    /** The undo point first: no undo point, no restore. */
    fun run(
        current: VersoConfig,
        restored: VersoConfig,
        writeUndo: (String) -> String?,
        writeConfig: (String) -> Boolean,
    ): Outcome {
        if (current == restored) return Outcome.Unchanged
        val undo = writeUndo(ConfigJson.serialize(current)) ?: return Outcome.UndoFailed
        if (!writeConfig(ConfigJson.serialize(restored))) return Outcome.WriteFailed
        return Outcome.Done(undo)
    }
}
