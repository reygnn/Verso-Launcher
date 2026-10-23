package com.github.reygnn.guru

import kotlin.math.abs

/** Which recorded exits become reports — pure; the Android side only reads and maps them. */
internal object ExitSelection {

    /** A Java crash the handler caught shows up as an exit too, a moment after its report. */
    const val SAME_CRASH_MS = 10_000L

    /** [seen]: the newest exit timestamp looked at so far, to be stored for the next look. */
    class Outcome(val report: List<ExitRecord>, val seen: Long)

    /**
     * Exits newer than [seen], oldest first; a Java crash only when no handler report
     * ([handlerTimes]) lies within [SAME_CRASH_MS] of it — the handler's has the stack trace.
     * No [seen] yet (the first start with Guru): the history predates it, nothing is reported
     * and the mark starts at [now].
     */
    fun select(exits: List<ExitRecord>, seen: Long?, now: Long, handlerTimes: List<Long>): Outcome {
        if (seen == null) return Outcome(emptyList(), now)
        val fresh = exits.filter { it.timestamp > seen }
        val report = fresh
            .filter { exit -> exit.kind != GuruKind.CRASH || handlerTimes.none { abs(it - exit.timestamp) <= SAME_CRASH_MS } }
            .sortedBy { it.timestamp }
        return Outcome(report, fresh.maxOfOrNull { it.timestamp } ?: seen)
    }
}
