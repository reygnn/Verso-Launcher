package com.github.reygnn.verso_launcher

/**
 * A "~restore-saf" pick, from the moment the picker answers until its adoption starts. Held by
 * the process (VersoApp.pick), not by an activity: a recreation or a stop in between loses
 * nothing, and whichever instance is alive asks the user and adopts it.
 *
 * A picked file is any file, also one another app planted in Download/Verso to hide or rename
 * apps: so it is read, then shown with its counts and adopted only on the user's OK, then waits
 * for an idle gate and a loaded config.
 *
 * While a pick is pending, `~restore` and `~restore-saf` are refused (MainActivity.runCommand),
 * nothing else: edits and the other commands go on, so a read that hangs on a slow provider
 * blocks only a second restore. So the run waited for is never a restore: the pick's adoption
 * right behind it would replace what that restore brought.
 *
 * Main thread only. Pure, so the states are JVM-testable.
 */
internal class PendingPick {

    private sealed interface State {
        data object None : State
        /** The picked file is being read on its own thread (hard rule 3). */
        data class Reading(val pick: Int) : State
        /** Read and parsed; waits for a window in front and a loaded config to ask the user. */
        data class Read(val restored: VersoConfig) : State
        /** The user is being asked (a dialog with the counts). */
        data class Asked(val restored: VersoConfig) : State
        /** Confirmed; waits for an idle gate and a loaded config. */
        data class Confirmed(val restored: VersoConfig) : State
    }

    private var state: State = State.None
    // Counts picks, so a read that was dropped or superseded meanwhile is recognised when it lands.
    private var picks = 0

    /** A pick is being read, asked about or waits to be adopted: `~restore` and `~restore-saf` wait for it. */
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
        state = if (restored == null) State.None else State.Read(restored)
        return true
    }

    /** The read config, once, for the dialog about to ask the user; null if none waits to be asked. */
    fun ask(): VersoConfig? {
        val read = state as? State.Read ?: return null
        state = State.Asked(read.restored)
        return read.restored
    }

    /** The user's answer: OK confirms, anything else ends the pick. False if nobody was being asked. */
    fun answer(confirmed: Boolean): Boolean {
        val asked = state as? State.Asked ?: return false
        state = if (confirmed) State.Confirmed(asked.restored) else State.None
        return true
    }

    /** The confirmed config, once, for the adoption about to start; null if none is confirmed. */
    fun take(): VersoConfig? {
        val confirmed = state as? State.Confirmed ?: return null
        state = State.None
        return confirmed.restored
    }

    /** "~restart": forget the pick, whatever its state. True if there was one (it says so). */
    fun drop(): Boolean = pending.also { state = State.None }
}
