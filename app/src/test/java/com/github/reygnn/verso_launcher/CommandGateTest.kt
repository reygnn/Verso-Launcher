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
    // The activity instance that receives a run's hand-backs; [other] is its successor.
    private val owner = Any()
    private val other = Any()
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
        assertTrue(gate.start(owner) { ran = true })
        assertTrue(ran)
        assertFalse(gate.busy)
    }

    @Test fun `a second command is refused while one runs, and its first step never runs`() {
        gate.start(owner) { t -> step<Unit>(t) }
        var ran = false
        assertFalse(gate.start(owner) { ran = true })
        assertFalse(ran)
        runAll()
        assertFalse(gate.busy)
        assertTrue(gate.start(owner) { })
    }

    @Test fun `an IO step hands its result back and ends the run`() {
        var got: String? = null
        gate.start(owner) { t -> step(t, work = { "name" }) { got = it } }
        assertTrue(gate.busy)
        runAll()
        assertEquals("name", got)
        assertFalse(gate.busy)
    }

    @Test fun `a step that starts the next one keeps the run going without a gap`() {
        // ~restore: read (IO) -> main -> undo point + config (IO) -> main.
        val busyBetween = mutableListOf<Boolean>()
        gate.start(owner) { t ->
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
        gate.start(owner) { t ->
            gate.hold(t)
            report = { gate.finish(t) { } }
        }
        assertTrue(gate.busy)
        report!!()
        assertFalse(gate.busy)
    }

    @Test fun `work that throws is logged, hands back null and ends the run`() {
        var got: String? = "untouched"
        gate.start(owner) { t -> step<String>(t, work = { throw IllegalStateException("bug") }) { got = it } }
        runAll()
        assertNull(got)
        assertEquals(1, logged.size)
        assertFalse("the gate must not stay shut", gate.busy)
    }

    @Test fun `a refused IO task hands back null at once and ends the run`() {
        var got: String? = "untouched"
        gate.start(owner) { t -> step(t, work = { "x" }, accepted = false) { got = it } }
        assertNull(got)
        assertFalse(gate.busy)
    }

    @Test fun `a then that throws still ends the run`() {
        gate.start(owner) { t -> step<Unit>(t) { throw IllegalStateException("bug in then") } }
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
            gate.start(owner) { throw IllegalStateException("bug") }
            fail("the exception must not be swallowed")
        } catch (expected: IllegalStateException) {
            // expected
        }
        assertFalse(gate.busy)
    }

    /** A recreation: the old instance's hand-back is dropped, so its run is given up behind its IO. */
    @Test fun `abandon ends only the run its owner started`() {
        gate.start(owner) { t -> gate.hold(t) }
        gate.abandon(other)
        assertTrue("the successor's abandon is not the owner's", gate.busy)
        gate.abandon(owner)
        assertFalse(gate.busy)
        assertTrue(gate.start(other) { })
    }

    @Test fun `a late finish of an abandoned run leaves the next run alone`() {
        var oldRelease: (() -> Unit)? = null
        gate.start(owner) { t ->
            gate.hold(t)
            oldRelease = { gate.finish(t) { } }
        }
        gate.abandon(owner)
        gate.start(other) { t -> gate.hold(t) }
        oldRelease!!()
        assertTrue("the old ticket must not end the new run", gate.busy)
    }

    @Test fun `an IO step for an abandoned run queues nothing, hands nothing back, is logged`() {
        var old: CommandGate.Ticket? = null
        gate.start(owner) { t ->
            gate.hold(t)
            old = t
        }
        gate.abandon(owner)
        var handedBack = false
        step<Unit>(old!!) { handedBack = true }
        assertTrue("nothing queued", pending.isEmpty())
        assertFalse(handedBack)
        assertEquals(1, logged.size)
        assertFalse("and the gate is not taken by it", gate.busy)
    }

    @Test fun `an IO step for an ended run queues nothing either`() {
        var ended: CommandGate.Ticket? = null
        gate.start(owner) { t -> ended = t }
        assertFalse(gate.busy)
        step<Unit>(ended!!)
        assertTrue(pending.isEmpty())
        assertEquals(1, logged.size)
    }

    /** What waits for the gate (a "~restore-saf" pick) is told each time a run ends, and only then. */
    @Test fun `onIdle once per run end, not per release`() {
        var idles = 0
        val gate = CommandGate(onIdle = { idles++ })
        gate.start(owner) { }
        assertEquals(1, idles)
        var report: (() -> Unit)? = null
        gate.start(owner) { t ->
            gate.hold(t)
            gate.hold(t)
            report = { gate.finish(t) { } }
        }
        assertEquals("the first step's release is not the last", 1, idles)
        report!!()
        assertEquals(1, idles)
        report!!()
        assertEquals(2, idles)
        gate.start(owner) { t -> gate.hold(t) }   // a run that stays going
        assertFalse(gate.start(owner) { })
        assertEquals("a refused start ends nothing", 2, idles)
    }

    @Test fun `onIdle on abandon, not on someone else's`() {
        var idles = 0
        val gate = CommandGate(onIdle = { idles++ })
        gate.start(owner) { t -> gate.hold(t) }
        gate.abandon(other)
        assertEquals(0, idles)
        gate.abandon(owner)
        assertEquals(1, idles)
        gate.abandon(owner)
        assertEquals("nothing left to end", 1, idles)
    }

    @Test fun `an edit permit taken before an abandoned run stays void`() {
        val permit = gate.idle()!!
        gate.start(owner) { t -> gate.hold(t) }
        gate.abandon(owner)
        assertFalse(permit.current)
        assertTrue(gate.idle()!!.current)
    }

    @Test fun `an edit permit only while no run is going, and void once one starts`() {
        val gate = CommandGate()
        val permit = gate.idle()!!
        assertTrue(permit.current)
        var during: CommandGate.Idle? = null
        gate.start(owner) { during = gate.idle(); assertFalse("kept across a command start", permit.current) }
        assertNull(during)
        assertFalse("nor valid again once the run ended", permit.current)
        assertTrue("a fresh one is", gate.idle()!!.current)
    }
}
