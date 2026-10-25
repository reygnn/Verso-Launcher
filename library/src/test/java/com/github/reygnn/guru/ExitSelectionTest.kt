package com.github.reygnn.guru

import org.junit.Assert.assertEquals
import org.junit.Test

class ExitSelectionTest {

    private fun exit(kind: GuruKind, at: Long, pid: Int? = null) = ExitRecord(kind, at, "d", pid) { error("select must not read a dump") }

    private fun ExitSelection.Outcome.times() = report.map { it.timestamp }

    @Test
    fun `the first look reports nothing and starts the mark at now`() {
        val outcome = ExitSelection.select(listOf(exit(GuruKind.ANR, 500)), seen = null, now = 1_000, handlerTimes = emptyList())
        assertEquals(emptyList<Long>(), outcome.times())
        assertEquals(1_000L, outcome.seen)
    }

    /** Both sides of the mark: equal was seen, one past it is new. */
    @Test
    fun `only exits after the mark are new`() {
        val outcome = ExitSelection.select(
            listOf(exit(GuruKind.ANR, 100), exit(GuruKind.ANR, 101)), seen = 100, now = 0, handlerTimes = emptyList(),
        )
        assertEquals(listOf(101L), outcome.times())
        assertEquals(101L, outcome.seen)
    }

    @Test
    fun `nothing new keeps the mark`() {
        val outcome = ExitSelection.select(listOf(exit(GuruKind.ANR, 50)), seen = 100, now = 0, handlerTimes = emptyList())
        assertEquals(emptyList<Long>(), outcome.times())
        assertEquals(100L, outcome.seen)
    }

    @Test
    fun `new exits come oldest first`() {
        val outcome = ExitSelection.select(
            listOf(exit(GuruKind.ANR, 300), exit(GuruKind.NATIVE, 200), exit(GuruKind.CRASH, 250)),
            seen = 0, now = 0, handlerTimes = emptyList(),
        )
        assertEquals(listOf(200L, 250L, 300L), outcome.times())
        assertEquals(300L, outcome.seen)
    }

    /** The window from both sides, before and after the handler's report. */
    @Test
    fun `a Java crash the handler caught is not reported twice`() {
        val w = ExitSelection.SAME_CRASH_MS
        val crashes = listOf(100_000 - w - 1, 100_000 - w, 100_000 + w, 100_000 + w + 1).map { exit(GuruKind.CRASH, it) }
        val outcome = ExitSelection.select(crashes, seen = 0, now = 0, handlerTimes = listOf(100_000))
        assertEquals(listOf(100_000 - w - 1, 100_000 + w + 1), outcome.times())
        assertEquals(100_000 + w + 1, outcome.seen)
    }

    @Test
    fun `an ANR next to a handler report is still reported`() {
        val outcome = ExitSelection.select(listOf(exit(GuruKind.ANR, 1_000)), seen = 0, now = 0, handlerTimes = listOf(1_000))
        assertEquals(listOf(1_000L), outcome.times())
    }

    /** The dropped crash still moves the mark: it was looked at. */
    @Test
    fun `a dropped crash still moves the mark`() {
        val outcome = ExitSelection.select(listOf(exit(GuruKind.CRASH, 1_000)), seen = 0, now = 0, handlerTimes = listOf(1_000))
        assertEquals(emptyList<Long>(), outcome.times())
        assertEquals(1_000L, outcome.seen)
    }

    // ---- the same process ending twice (One UI's "keeps stopping" dialog) -------------

    /** As on the A17: the handler's crash, its exit 22 ms later, and the dialog's close a minute on. */
    @Test
    fun `a second end of the same process is no report`() {
        val first = exit(GuruKind.CRASH, 1_000, pid = 7)
        val closed = exit(GuruKind.CRASH, 61_000, pid = 7)
        val outcome = ExitSelection.select(
            listOf(closed, first), seen = 0, now = 0, handlerTimes = listOf(978),
            ends = listOf(ProcessEnd(7, 61_000), ProcessEnd(7, 1_000)),
        )
        assertEquals(emptyList<Long>(), outcome.times())
        assertEquals(61_000L, outcome.seen)
    }

    @Test
    fun `the first end of a process is still reported, the second not`() {
        val outcome = ExitSelection.select(
            listOf(exit(GuruKind.NATIVE, 1_000, pid = 7), exit(GuruKind.CRASH, 61_000, pid = 7)), seen = 0, now = 0,
            handlerTimes = emptyList(), ends = listOf(ProcessEnd(7, 1_000), ProcessEnd(7, 61_000)),
        )
        assertEquals(listOf(1_000L), outcome.times())
    }

    /** An end of any reason counts: a process killed and later a crash under its pid is still one process. */
    @Test
    fun `only the end just before decides - a pid reused later is another process`() {
        val outcome = ExitSelection.select(
            listOf(exit(GuruKind.CRASH, 1_000, pid = 7), exit(GuruKind.CRASH, 9_000, pid = 7), exit(GuruKind.ANR, 12_000, pid = 9)),
            seen = 0, now = 0, handlerTimes = emptyList(),
            ends = listOf(ProcessEnd(7, 1_000), ProcessEnd(8, 5_000), ProcessEnd(7, 9_000), ProcessEnd(9, 11_000), ProcessEnd(9, 12_000)),
        )
        assertEquals(listOf(1_000L, 9_000L), outcome.times())
    }
}
