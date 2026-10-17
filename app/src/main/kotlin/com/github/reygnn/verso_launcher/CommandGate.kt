package com.github.reygnn.verso_launcher

/**
 * The "~" commands, one at a time (hard rule 15). A command is a chain of steps; each step
 * holds the run and releases it on every way out, and the last release ends the run — there
 * is no "done" call a path could forget. Main thread only.
 */
internal class CommandGate {

    /** One run. Compared by identity. */
    class Ticket internal constructor() {
        internal var holds = 0
    }

    /** Proof that no run was going when an edit was asked for (hard rule 15): LauncherState's
     *  edits take one, so an edit path that skips the check does not compile. */
    class Idle internal constructor(private val gate: CommandGate) {
        /** False once a run has started since: a permit kept across a command is void. */
        val current: Boolean get() = !gate.busy
    }

    private var current: Ticket? = null

    val busy: Boolean get() = current != null

    /** A permit to edit, or null while a run is going. */
    fun idle(): Idle? = if (busy) null else Idle(this)

    /** False if a run is going. Otherwise [first] runs at once; unless it [hold]s, the run ends as it returns or throws. */
    fun start(first: (Ticket) -> Unit): Boolean {
        if (current != null) return false
        val ticket = Ticket()
        ticket.holds = 1
        current = ticket
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
     */
    fun <T : Any> io(
        ticket: Ticket,
        submit: (Runnable) -> Boolean,
        post: (() -> Unit) -> Unit,
        work: () -> T?,
        logError: (Exception) -> Unit,
        then: (T?) -> Unit,
    ) {
        hold(ticket)
        val accepted = submit(Runnable {
            val result = try {
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
        if (current === ticket && --ticket.holds == 0) current = null
    }
}
