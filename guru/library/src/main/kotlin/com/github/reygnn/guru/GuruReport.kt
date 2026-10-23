package com.github.reygnn.guru

/**
 * What went wrong — the first half of the Guru number: the Amiga's dead-end flag (every one of
 * these took the process down), then 1, 2, 3 in declaration order — append, never reorder.
 * [title] heads the report's trace for the kinds that have no Java exception, so a server that
 * groups by the first line (Acrarium does; the family's backend by its class) sees
 * "ApplicationNotResponding: …" instead of a thread dump's header.
 */
internal enum class GuruKind(val title: String) {
    CRASH("Crash"),
    ANR("ApplicationNotResponding"),
    NATIVE("NativeCrash"),
    ;

    val code: Long get() = DEAD_END or (ordinal + 1).toLong()

    private companion object {
        const val DEAD_END = 0x8000_0000L
    }
}

/** The app and the device a report comes from, read once at install — never while dying. */
internal data class GuruEnv(
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    val androidRelease: String,
    val sdk: Int,
    val brand: String,
    val model: String,
    val device: String,
)

/**
 * One crash, ANR or native crash, kept on disk until it is sent or dismissed. [id] is the
 * report's identity on the server too (ACRA's REPORT_ID: a resend is a duplicate, not a second
 * report). [startedAt] is 0 when unknown (an exit found on the next start).
 */
internal data class GuruReport(
    val id: String,
    val kind: GuruKind,
    val crashedAt: Long,
    val startedAt: Long,
    val thread: String,
    val trace: String,
    val env: GuruEnv,
) {
    val number: String get() = GuruNumber.of(kind, trace)
}
