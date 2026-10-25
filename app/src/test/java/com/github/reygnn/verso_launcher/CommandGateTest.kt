package com.github.reygnn.verso_launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** IO tasks and their hand-backs are one manual FIFO queue, so a test decides when each runs. */

class CommandGateTest {

    private val gate = CommandGate()
    private val pending = ArrayDeque<Runnable>()
    private val logged = mutableListOf<Exception>()

    private fun runNext() = pending.removeFirst().run()
    private fun runAll() { while (pending.isNotEmpty()) runNext() }

    /** An IO step through the real [CommandGate.io]: task and hand-back collapsed into one queue entry. */
    private fun <T : Any> step(
        t: CommandGate.Ticket,
        work: () -> T? = { null },
        accepted: Boolean = true,
        then: (T?) -> Unit = {},
    ) = gate.io(
        t,
        submit = { task -> if (accepted) pending.addLast(task); accepted },
        post = { block -> block() },
        work = work,
        logError = { logged += it },
        then = then,
    )

    @Test fun `a run that holds nothing ends as its first step returns`() {
        var ran = false
        assertTrue(gate.start { ran = true })
        assertTrue(ran)
        assertFalse(gate.busy)
    }

    @Test fun `a second command is refused while one runs, and its first step never runs`() {
        gate.start { t -> step<Unit>(t) }
        var ran = false
        assertFalse(gate.start { ran = true })
        assertFalse(ran)
        runAll()
        assertFalse(gate.busy)
        assertTrue(gate.start { })
    }

    @Test fun `an IO step hands its result back and ends the run`() {
        var got: String? = null
        gate.start { t -> step(t, work = { "name" }) { got = it } }
        assertTrue(gate.busy)
        runAll()
        assertEquals("name", got)
        assertFalse(gate.busy)
    }

    @Test fun `a step that starts the next one keeps the run going without a gap`() {
        // ~restore: read (IO) -> main -> undo point + config (IO) -> main.
        val busyBetween = mutableListOf<Boolean>()
        gate.start { t ->
            step<Unit>(t) {
                busyBetween += gate.busy
                step<Unit>(t) { busyBetween += gate.busy }
            }
        }
        runNext()
        assertTrue("still running between the two steps", gate.busy)
        runNext()
        assertEquals(listOf(true, true), busyBetween)
        assertFalse(gate.busy)
    }

    @Test fun `a main-thread callback held with hold and closed with finish ends the run`() {
        // A confirmed ~cleanup: LauncherState reports back later through a callback.
        var report: (() -> Unit)? = null
        gate.start { t ->
            gate.hold(t)
            report = { gate.finish(t) { } }
        }
        assertTrue(gate.busy)
        report!!()
        assertFalse(gate.busy)
    }

    @Test fun `work that throws is logged, hands back null and ends the run`() {
        var got: String? = "untouched"
        gate.start { t -> step<String>(t, work = { throw IllegalStateException("bug") }) { got = it } }
        runAll()
        assertNull(got)
        assertEquals(1, logged.size)
        assertFalse("the gate must not stay shut", gate.busy)
    }

    @Test fun `a refused IO task hands back null at once and ends the run`() {
        var got: String? = "untouched"
        gate.start { t -> step(t, work = { "x" }, accepted = false) { got = it } }
        assertNull(got)
        assertFalse(gate.busy)
    }

    @Test fun `a then that throws still ends the run`() {
        gate.start { t -> step<Unit>(t) { throw IllegalStateException("bug in then") } }
        try {
            runAll()
            fail("the exception must not be swallowed")
        } catch (expected: IllegalStateException) {
            // expected
        }
        assertFalse(gate.busy)
    }

    @Test fun `a first step that throws ends the run and propagates`() {
        try {
            gate.start { throw IllegalStateException("bug") }
            fail("the exception must not be swallowed")
        } catch (expected: IllegalStateException) {
            // expected
        }
        assertFalse(gate.busy)
    }

    @Test fun `an IO step for an ended run queues nothing either`() {
        var ended: CommandGate.Ticket? = null
        gate.start { t -> ended = t }
        assertFalse(gate.busy)
        step<Unit>(ended!!)
        assertTrue(pending.isEmpty())
        assertEquals(1, logged.size)
    }

    /** What waits for the gate (a "~restore-saf" pick) is told each time a run ends, and only then. */
    @Test fun `onIdle once per run end, not per release`() {
        var idles = 0
        val gate = CommandGate(onIdle = { idles++ })
        gate.start { }
        assertEquals(1, idles)
        var report: (() -> Unit)? = null
        gate.start { t ->
            gate.hold(t)
            gate.hold(t)
            report = { gate.finish(t) { } }
        }
        assertEquals("the first step's release is not the last", 1, idles)
        report!!()
        assertEquals(1, idles)
        report!!()
        assertEquals(2, idles)
        gate.start { t -> gate.hold(t) }   // a run that stays going
        assertFalse(gate.start { })
        assertEquals("a refused start ends nothing", 2, idles)
    }

    @Test fun `an edit permit only while no run is going, and void once one starts`() {
        val gate = CommandGate()
        val permit = gate.idle()!!
        assertTrue(permit.current)
        var during: CommandGate.Idle? = null
        gate.start { during = gate.idle(); assertFalse("kept across a command start", permit.current) }
        assertNull(during)
        assertFalse("nor valid again once the run ended", permit.current)
        assertTrue("a fresh one is", gate.idle()!!.current)
    }
}
