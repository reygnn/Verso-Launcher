package com.github.reygnn.guru

/**
 * A process exit the system recorded ([android.app.ApplicationExitInfo]), already mapped to a
 * kind. [trace] reads the dump only when asked: most exits are older than the last look and are
 * never read. [pid]: the process that ended; null when unknown.
 */
internal class ExitRecord(
    val kind: GuruKind,
    val timestamp: Long,
    val description: String,
    val pid: Int? = null,
    val trace: () -> String? = { null },
)

/** Any recorded end of a process of the app, whatever its reason — the timeline [ExitSelection] reads pids from. */
internal class ProcessEnd(val pid: Int, val timestamp: Long)

/** This process, as its reports describe it — pure, so the crash path is tested on the JVM. */
internal class GuruProcess(val env: GuruEnv, val startedAt: Long) {

    fun crash(id: String, thread: String, error: Throwable, now: Long) = GuruReport(
        id = id,
        kind = GuruKind.CRASH,
        crashedAt = now,
        startedAt = startedAt,
        thread = thread,
        trace = GuruText.cap(error.stackTraceToString(), GuruText.MAX_TRACE_CHARS),
        env = env,
    )

    /**
     * An exit of an earlier process, found now: no start time, no thread. Its first line reads
     * like an exception's ("Kind: description") for a server that groups by it; the env is
     * today's, which is the crashed version unless an update came in between.
     */
    fun exit(id: String, record: ExitRecord): GuruReport {
        val head = "${record.kind.title}: ${record.description}".trimEnd(' ', ':')
        val dump = record.trace()?.takeIf { it.isNotBlank() }
        return GuruReport(
            id = id,
            kind = record.kind,
            crashedAt = record.timestamp,
            startedAt = 0,
            thread = "",
            trace = GuruText.cap(if (dump == null) head else "$head\n\n$dump", GuruText.MAX_TRACE_CHARS),
            env = env,
        )
    }
}
