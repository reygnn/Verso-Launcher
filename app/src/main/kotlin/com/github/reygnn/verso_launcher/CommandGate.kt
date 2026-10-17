package com.github.reygnn.verso_launcher

/**
 * The "~" commands, one at a time (hard rule 15): while one runs, [start] refuses a second.
 * Nothing else is held back — taps, edits and page turns go on as before. An edit that lands
 * during a run is safe through LauncherState's own guards (configEpoch, the cleanup's
 * Superseded). One gap stays: a save queued during a restore's IO step lands after the restored
 * file and writes the old config back — adopt() saves the restored one again right after, so
 * only a process death in that moment loses the restore on disk (the restore can be repeated).
 *
 * A command is a chain of steps — main-thread code and IO tasks. A step HOLDS the run when it
 * starts and RELEASES it when it is over ([finish], or [io]); the last release ends the run.
 * A step that starts the next one before releasing its own hands the run over without a gap.
 * There is no "done" call a path could forget: every way out of a step ends the run once
 * nothing else holds it.
 *
 * The gate belongs to the Activity. A hand-back that onUi drops for a destroyed instance goes
 * with that instance's gate; the next one starts free.
 *
 * Main thread only. Pure Kotlin, JVM-tested (CommandGateTest).
 */
internal class CommandGate {

    /** One run. Compared by identity. */
    class Ticket internal constructor() {
        internal var holds = 0
    }

    private var current: Ticket? = null

    val busy: Boolean get() = current != null

    /**
     * Start a run unless one is running — false then, and [first] does not run. [first] runs at
     * once; what it [hold]s keeps the run going, otherwise the run ends as it returns (also when
     * it throws).
     */
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

    /** A further step of [ticket] starts. */
    fun hold(ticket: Ticket) {
        if (current === ticket) ticket.holds++
    }

    /** The end of a step [hold] started: [block] runs, then the step is released, whatever [block] does. */
    fun finish(ticket: Ticket, block: () -> Unit) {
        try {
            block()
        } finally {
            release(ticket)
        }
    }

    /**
     * One IO step of [ticket]: [work] through [submit] (the IO thread), [then] with its result
     * back through [post] (the main thread), as a step of [ticket]. A refused task ends the step
     * at once with [then] (null). A [work] that throws — a bug, every IO function catches its
     * own errors (hard rule 2) — must not leave the gate shut for good: [logError] gets it (on the
     * IO thread), and [then] gets null too.
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
