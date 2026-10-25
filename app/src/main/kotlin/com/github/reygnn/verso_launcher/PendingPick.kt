package com.github.reygnn.verso_launcher

/**
 * A "~restore-saf" pick, from the moment the picker answers until its adoption starts. Held by
 * the process (VersoApp.pick), not by an activity: a recreation or a stop in between loses
 * nothing, and whichever instance is alive adopts it once the gate is idle and the config loaded.
 *
 * While a pick is pending, `~restore` and `~restore-saf` are refused (MainActivity.runCommand),
 * nothing else: edits and the other commands go on, so a read that hangs on an offline provider
 * blocks only a second restore. That is what makes waiting for the gate safe — the run waited for
 * can never be a restore, whose undo point an automatic restore right behind it would sweep.
 *
 * Main thread only. Pure, so the states are JVM-testable.
 */
internal class PendingPick {

    private sealed interface State {
        data object None : State
        /** The picked file is being read on its own thread (hard rule 3). */
        data class Reading(val pick: Int) : State
        /** Read and parsed; waits for an idle gate and a loaded config. */
        data class Ready(val restored: VersoConfig) : State
    }

    private var state: State = State.None
    // Counts picks, so a read that was dropped or superseded meanwhile is recognised when it lands.
    private var picks = 0

    /** A pick is being read or waits to be adopted: `~restore` and `~restore-saf` wait for it. */
    val pending: Boolean get() = state != State.None

    /** The picker answered: a read of the returned number starts. */
    fun begin(): Int {
        val pick = ++picks
        state = State.Reading(pick)
        return pick
    }

    /**
     * The read of [pick] is back with [restored] (null: unreadable or not a config). False if that
     * pick was dropped or superseded meanwhile — then nothing changes and nothing is said.
     */
    fun arrived(pick: Int, restored: VersoConfig?): Boolean {
        val reading = state as? State.Reading
        if (reading?.pick != pick) return false
        state = if (restored == null) State.None else State.Ready(restored)
        return true
    }

    /** The read config, once, for the adoption about to start; null if none is ready. */
    fun take(): VersoConfig? {
        val ready = state as? State.Ready ?: return null
        state = State.None
        return ready.restored
    }

    /** "~restart": forget the pick, read or still reading. True if there was one (it says so). */
    fun drop(): Boolean = pending.also { state = State.None }
}
