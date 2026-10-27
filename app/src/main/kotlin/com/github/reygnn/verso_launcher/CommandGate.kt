package com.github.reygnn.verso_launcher

/**
 * The "~" commands, one at a time (hard rule 15). A command is a chain of steps; each step
 * holds the run and releases it on every way out, and the last release ends the run — there
 * is no "done" call a path could forget. Main thread only.
 *
 * One per process (VersoApp.gate), so a recreated activity sees a run its predecessor left
 * going. Every step hands back to the process, never to an instance that a recreation could take
 * away — Commands.step through VersoApp.onMain — so every run ends by itself.
 *
 * @param onIdle called each time a run ends (its last release): something may have
 *   waited for the gate (a "~restore-saf" pick, VersoApp). Called in the middle of the release, so
 *   it must not start a run itself — it posts.
 */
internal class CommandGate(private val onIdle: () -> Unit = {}) {

    /** One run. Compared by identity. */
    class Ticket internal constructor() {
        internal var holds = 0
    }

    /** Proof that no run was going when an edit was asked for (hard rule 15): LauncherState's
     *  edits take one, so an edit path that skips the check does not compile. */
    class Idle internal constructor(private val gate: CommandGate, private val run: Int) {
        /** False once a run has started since: a permit kept across a command is void. */
        val current: Boolean get() = !gate.busy && gate.runs == run
    }

    private var current: Ticket? = null
    // Counts runs started, so a permit outlives no run.
    private var runs = 0

    val busy: Boolean get() = current != null

    /** A permit to edit, or null while a run is going. */
    fun idle(): Idle? = if (busy) null else Idle(this, runs)

    /** False if a run is going. Otherwise [first] runs at once; unless it [hold]s, the run ends as it returns or throws. */
    fun start(first: (Ticket) -> Unit): Boolean {
        if (current != null) return false
        val ticket = Ticket()
        ticket.holds = 1
        current = ticket
        runs++
        try {
            first(ticket)
        } finally {
            release(ticket)
        }
        return true
    }

    fun hold(ticket: Ticket) {
        if (current === ticket) ticket.holds++
    }

    /** Ends a step [hold] started, whatever [block] does. */
    fun finish(ticket: Ticket, block: () -> Unit) {
        try {
            block()
        } finally {
            release(ticket)
        }
    }

    /**
     * [work] on the IO thread, [then] with its result back on main. A refused task or a [work]
     * that throws gives [then] null — a bug must not leave the gate shut for good.
     *
     * Only for the run going: a stale [ticket] (a run that is over) queues nothing and gets no
     * [then] — its hand-back would belong to nothing. It
     * is logged, not thrown (a HOME app must not crash, hard rule 2), and not silent, since only a
     * bug gets here: every legal call comes from a step of the run, while it still holds it.
     */
    fun <T : Any> io(
        ticket: Ticket,
        submit: (Runnable) -> Boolean,
        post: (() -> Unit) -> Unit,
        work: () -> T?,
        logError: (Exception) -> Unit,
        then: (T?) -> Unit,
    ) {
        if (current !== ticket) {
            logError(IllegalStateException("an IO step for a run that is not going"))
            return
        }
        hold(ticket)
        val accepted = submit(Runnable {
            // a throwing step must still release the gate
            val result = @Suppress("TooGenericExceptionCaught") try {
                work()
            } catch (e: Exception) {
                logError(e)
                null
            }
            post { finish(ticket) { then(result) } }
        })
        if (!accepted) finish(ticket) { then(null) }
    }

    private fun release(ticket: Ticket) {
        if (current !== ticket || --ticket.holds > 0) return
        current = null
        onIdle()
    }
}
