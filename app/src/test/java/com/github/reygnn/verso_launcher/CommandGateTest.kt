package com.github.reygnn.verso_launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * JVM tests for [CommandGate]: one "~" command at a time, ended by its last outstanding step.
 *
 * The steps a command hands over to — an IO task, the read thread, a hand-back to main — are one
 * manual FIFO queue here, so a test decides exactly when each runs, like LauncherStateTest's.
 */
class CommandGateTest {

    private val announced = mutableListOf<Boolean>()
    private val gate = CommandGate { busy -> announced += busy }

    /** Steps handed over, waiting to run (IO task + its hand-back, collapsed into one). */
    private val pending = ArrayDeque<Runnable>()

    private fun runNext() = pending.removeFirst().run()
    private fun runAll() { while (pending.isNotEmpty()) runNext() }

    /** What MainActivity.commandIo does: hold [t] now, finish it when the step runs. */
    private fun step(t: CommandGate.Ticket, then: () -> Unit = {}) {
        if (!gate.hold(t)) return
        pending.addLast(Runnable { gate.finish(t, then) })
    }

    // ---- start / end ----------------------------------------------------------------

    @Test fun `a command that ends within its first step is never announced`() {
        var ran = false
        assertTrue(gate.start(Command.SAVE) { ran = true })
        assertTrue(ran)
        assertFalse(gate.busy)
        assertEquals("no busy layer flashing up for nothing", emptyList<Boolean>(), announced)
    }

    @Test fun `a command with a step outstanding is announced once, and ends with that step`() {
        gate.start(Command.BACKUP) { t -> step(t) }
        assertTrue(gate.busy)
        assertEquals(listOf(true), announced)
        runAll()
        assertFalse(gate.busy)
        assertEquals(listOf(true, false), announced)
    }

    @Test fun `a second command is refused while one runs, and its first step never runs`() {
        gate.start(Command.RESTORE) { t -> step(t) }
        var ran = false
        assertFalse(gate.start(Command.BACKUP) { ran = true })
        assertFalse(ran)
        assertEquals(Command.RESTORE, gate.current?.command)
    }

    @Test fun `a new command can start once the previous one has ended`() {
        gate.start(Command.BACKUP) { t -> step(t) }
        runAll()
        assertTrue(gate.start(Command.RESTORE) { t -> step(t) })
        assertEquals(Command.RESTORE, gate.current?.command)
    }

    // ---- chains: a step hands over to the next ------------------------------------------

    @Test fun `a step that starts the next one keeps the command running without a gap`() {
        // ~restore: read (IO) -> back on main, undo point + config (IO) -> back on main, adopt.
        val busyBetween = mutableListOf<Boolean>()
        gate.start(Command.RESTORE) { t ->
            step(t) {
                busyBetween += gate.busy
                step(t) { busyBetween += gate.busy }
            }
        }
        runNext()
        assertTrue("still running between the two steps", gate.busy)
        runNext()
        assertEquals(listOf(true, true), busyBetween)
        assertFalse(gate.busy)
        assertEquals("announced once, ended once", listOf(true, false), announced)
    }

    @Test fun `two steps outstanding at once end the command only with the second`() {
        gate.start(Command.CLEANUP) { t -> step(t); step(t) }
        runNext()
        assertTrue(gate.busy)
        runNext()
        assertFalse(gate.busy)
    }

    @Test fun `a step whose follow-up is refused at once still ends the command`() {
        // The IO thread refused the second task: its caller releases right away.
        gate.start(Command.RESTORE) { t ->
            step(t) {
                if (gate.hold(t)) gate.release(t)  // held and given back in the same breath
            }
        }
        runAll()
        assertFalse(gate.busy)
        assertEquals(listOf(true, false), announced)
    }

    // ---- failures never leave the launcher locked ----------------------------------------

    @Test fun `an exception in the first step gives back its hold and propagates`() {
        try {
            gate.start(Command.BACKUP) { t -> step(t); throw IllegalStateException("bug") }
            fail("the exception must not be swallowed")
        } catch (e: IllegalStateException) {
            // expected
        }
        // The step it had already handed over still holds the command; it ends with it.
        assertTrue(gate.busy)
        runAll()
        assertFalse(gate.busy)
        assertTrue(gate.start(Command.SAVE) {})
    }

    @Test fun `an exception in the first step with nothing outstanding leaves the gate free`() {
        try {
            gate.start(Command.BACKUP) { throw IllegalStateException("bug") }
        } catch (e: IllegalStateException) {
            // expected
        }
        assertFalse(gate.busy)
        assertNull(gate.current)
        assertEquals(emptyList<Boolean>(), announced)
    }

    @Test fun `an exception while a step finishes still releases it`() {
        gate.start(Command.SAVE) { t -> step(t) { throw IllegalStateException("toast failed") } }
        try {
            runNext()
        } catch (e: IllegalStateException) {
            // expected
        }
        assertFalse(gate.busy)
        assertEquals(listOf(true, false), announced)
    }

    // ---- cancel: only the unbounded read ------------------------------------------------

    @Test fun `cancel leaves a command that does not allow it running`() {
        gate.start(Command.BACKUP) { t -> step(t) }
        assertFalse(gate.cancel())
        assertTrue(gate.busy)
    }

    @Test fun `cancel ends a cancelable command at once`() {
        gate.start(Command.RESTORE_SAF) { t -> t.cancelable = true; step(t) }
        assertTrue(gate.cancel())
        assertFalse(gate.busy)
        assertEquals(listOf(true, false), announced)
    }

    @Test fun `cancel with nothing running does nothing`() {
        assertFalse(gate.cancel())
        assertEquals(emptyList<Boolean>(), announced)
    }

    @Test fun `an abandoned command's late answer does nothing and does not touch the next one`() {
        // A read stuck at a stalled cloud drive: Back abandons it, the user picks another file,
        // and only then does the first read answer.
        var lateAnswerRan = false
        gate.start(Command.RESTORE_SAF) { t -> t.cancelable = true; step(t) { lateAnswerRan = true } }
        val stuck = pending.removeFirst()
        gate.cancel()
        gate.start(Command.RESTORE_SAF) { t -> step(t) }
        val second = gate.current

        stuck.run()

        assertFalse("the abandoned read is not adopted", lateAnswerRan)
        assertSame("the newer command still runs", second, gate.current)
        assertTrue(gate.busy)
        runAll()
        assertFalse(gate.busy)
    }

    @Test fun `an abandoned command cannot start another step`() {
        var ticket: CommandGate.Ticket? = null
        gate.start(Command.RESTORE_SAF) { t -> t.cancelable = true; ticket = t; step(t) }
        gate.cancel()
        assertFalse(gate.hold(ticket!!))
        assertFalse(gate.busy)
    }

    @Test fun `finish runs its block only while the ticket is the running command`() {
        var ticket: CommandGate.Ticket? = null
        gate.start(Command.RESTORE_SAF) { t -> t.cancelable = true; ticket = t; gate.hold(t) }
        assertTrue(gate.isCurrent(ticket!!))
        gate.cancel()
        assertFalse(gate.isCurrent(ticket!!))
        var ran = false
        gate.finish(ticket!!) { ran = true }
        assertFalse(ran)
    }
}
