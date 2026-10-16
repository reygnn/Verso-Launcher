package com.github.reygnn.verso_launcher

/**
 * The "~" commands, one at a time (hard rule 15). While one runs, the Activity covers its window
 * with a modal busy layer — no tap, no typing, no page turn, no second command — so nothing the
 * user does can interleave with it (short of stepping out of a stalled run, see below): no toggle
 * lands between a command's read and its hand-off, no second restore overtakes the first, no
 * cleanup removes keys from a config replaced under it.
 *
 * A command is a chain of steps — main-thread code, IO tasks, the "~restore-saf" read thread —
 * and it runs while any step is outstanding. A step HOLDS the command when it starts ([hold]) and
 * RELEASES it when it is over ([release], or [finish] around its last bit of work); the last
 * release ends the command. A step that starts the next one before releasing its own hands the
 * command over without a gap. There is no "done" call a path could forget: the end follows from
 * the holds alone, so every way out of a step — a result, a refusal, an exception — ends the
 * command once nothing else holds it.
 *
 * The gate belongs to the Activity, not the process. Every step reports back on every path — an
 * IO step from a finally, LauncherState's save/reload/cleanup too — so a hand-back that never
 * arrives is one that onUi dropped for a destroyed instance, whose gate and busy layer are gone
 * with it. The next instance starts free and needs nothing more: whatever it reads of the config —
 * its first load, or the file itself for a command run before that load (~backup, a restore's
 * undo point) — is read on the one IO thread, queued behind whatever the dead instance left there.
 * A hand-back that is merely LATE — a step stuck in a system service with no timeout — is what the
 * busy layer's way out is for (MainActivity.showBusy): the layer steps aside, the run goes on.
 *
 * Main thread only. Pure Kotlin, JVM-tested (CommandGateTest).
 *
 * @param onBusyChanged true once a started command is still running after its first step (the
 *                      busy layer goes up), false when it ends (the layer comes down). A command
 *                      that ends within its first step — refused, or done at once — is never
 *                      announced, so the layer does not flash up for nothing.
 */
internal class CommandGate(private val onBusyChanged: (Boolean) -> Unit) {

    /** One run of a command. Compared by identity: a late step names a ticket that has ended. */
    class Ticket internal constructor(val command: Command) {
        internal var holds = 0

        /**
         * Whether Back on the busy layer may abandon this run right now ([cancel]). Only for a step
         * with no bound on how long it waits — the "~restore-saf" read, which a stalled content
         * provider can hold up for good. Everything else runs to its end.
         */
        var cancelable = false
    }

    /** The running command, or null. */
    var current: Ticket? = null
        private set

    val busy: Boolean get() = current != null

    /**
     * Start [command] unless one is running — false then, and [first] does not run. [first] is the
     * command's first step and runs at once: what it holds keeps the command running; if it holds
     * nothing (it refused, or finished on the spot), the command ends as it returns. A [first] that
     * throws gives its hold back all the same: the command ends with whatever steps it had already
     * handed over, or at once. (On the main thread such an exception still ends the process, as
     * any would; the gate only makes sure it is not the gate that locks the launcher.)
     */
    fun start(command: Command, first: (Ticket) -> Unit): Boolean {
        if (current != null) return false
        val ticket = Ticket(command)
        ticket.holds = 1
        current = ticket
        try {
            first(ticket)
        } finally {
            if (current === ticket) {
                if (--ticket.holds == 0) current = null  // over within its first step: never shown
                else onBusyChanged(true)
            }
        }
        return true
    }

    /** True while [ticket] is the running command. */
    fun isCurrent(ticket: Ticket): Boolean = current === ticket

    /**
     * A further step of [ticket] starts. False — and nothing held — when [ticket] is no longer
     * running (abandoned by [cancel]): the caller must not start the step then.
     */
    fun hold(ticket: Ticket): Boolean {
        if (current !== ticket) return false
        ticket.holds++
        return true
    }

    /** A step of [ticket] is over; the last one ends the command. A no-op for an ended ticket. */
    fun release(ticket: Ticket) {
        if (current !== ticket) return
        if (--ticket.holds == 0) end()
    }

    /**
     * The end of a step of [ticket] that [hold] started: [block] runs only if [ticket] is still
     * the running command, and the step is released afterwards whatever [block] does — including
     * starting the next step, which then holds the command on.
     */
    fun finish(ticket: Ticket, block: () -> Unit) {
        try {
            if (current === ticket) block()
        } finally {
            release(ticket)
        }
    }

    /**
     * Back on the busy layer: abandon the running command if it allows that right now
     * ([Ticket.cancelable]). Its outstanding steps still finish, find their ticket ended and do
     * nothing. True when a command was abandoned.
     */
    fun cancel(): Boolean {
        val ticket = current ?: return false
        if (!ticket.cancelable) return false
        end()
        return true
    }

    private fun end() {
        current = null
        onBusyChanged(false)
    }
}
