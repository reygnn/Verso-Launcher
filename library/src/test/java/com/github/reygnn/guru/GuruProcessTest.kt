package com.github.reygnn.guru

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GuruProcessTest {

    private val process = GuruProcess(testEnv, startedAt = 1_000)

    @Test
    fun `a crash report carries the thread, the times and the whole trace`() {
        val error = IllegalStateException("boom")
        val r = process.crash("id-1", "worker-3", error, now = 2_000)
        assertEquals(GuruKind.CRASH, r.kind)
        assertEquals("worker-3", r.thread)
        assertEquals(2_000L, r.crashedAt)
        assertEquals(1_000L, r.startedAt)
        assertEquals(testEnv, r.env)
        assertEquals(error.stackTraceToString(), r.trace)
    }

    /** A StackOverflowError's trace is thousands of frames; the file stays bounded. */
    @Test
    fun `a crash trace is capped`() {
        val r = process.crash("id-1", "main", IllegalStateException("x".repeat(GuruText.MAX_TRACE_CHARS + 10)), 0)
        assertTrue(r.trace.length < GuruText.MAX_TRACE_CHARS + 100)
        assertTrue(r.trace.endsWith("chars cut]"))
    }

    @Test
    fun `an exit's first line reads like an exception, its dump follows`() {
        val r = process.exit("id-2", ExitRecord(GuruKind.ANR, 5_000, "Input dispatching timed out") { "\"main\" prio=5" })
        assertEquals("ApplicationNotResponding: Input dispatching timed out\n\n\"main\" prio=5", r.trace)
        assertEquals(5_000L, r.crashedAt)
        assertEquals("", r.thread)
    }

    /** It is an earlier process: this one's start time would be wrong. */
    @Test
    fun `an exit has no start time`() {
        assertEquals(0L, process.exit("id-2", ExitRecord(GuruKind.ANR, 5_000, "x")).startedAt)
    }

    @Test
    fun `an exit without description or dump is just its kind`() {
        assertEquals("NativeCrash", process.exit("id-3", ExitRecord(GuruKind.NATIVE, 1, "")).trace)
        assertEquals("NativeCrash", process.exit("id-3", ExitRecord(GuruKind.NATIVE, 1, "") { "  \n" }).trace)
    }

    /** The codes come from the declaration order: pinned, so a reorder cannot renumber old bugs. */
    @Test
    fun `the kind codes are fixed`() {
        assertEquals(listOf(0x80000001L, 0x80000002L, 0x80000003L), GuruKind.entries.map { it.code })
    }
}
