package com.github.reygnn.guru

import java.io.InputStream

/**
 * The Amiga quote, the report's own wording for a developer (the share's subject), and the size
 * limits on what Guru keeps. What a user reads or hears is [GuruTexts], from the host.
 */
internal object GuruText {

    const val SOFTWARE_FAILURE = "Software Failure."
    const val PRESS_LEFT = "Press left mouse button to continue."

    /** A trace kept on disk: a StackOverflowError or an ANR dump stays well below this. */
    const val MAX_TRACE_CHARS = 256 * 1024

    /** An ANR dump read from the system: bytes, before decoding. */
    const val MAX_DUMP_BYTES = 256 * 1024

    /** The alert box: the Amiga's one line, folded for a portrait phone; then where the reports went. */
    fun screenLines(number: String, saved: Int, texts: GuruTexts): List<String> = listOf(
        SOFTWARE_FAILURE,
        PRESS_LEFT,
        "Guru Meditation $number",
        texts.saved(saved),
    )

    /**
     * A crash on the main thread whose root cause was thrown in Guru's own code: the alert (or
     * something else of Guru's on main) died. Decided by the root cause's first frame that is
     * not the platform's — a class right in Guru's package (`guru.GuruView`, not
     * `guru.sample.MainActivity`). Not any Guru frame further down: R8 merges a host's lambda
     * into one of Guru's classes, so a host's own crash can pass through a `GuruScreen$show$5`.
     */
    fun diedInGuru(report: GuruReport): Boolean {
        if (report.kind != GuruKind.CRASH || report.thread != "main") return false
        val lines = report.trace.lines()
        val cause = lines.indices.lastOrNull { lines[it].startsWith("Caused by: ") } ?: 0
        val first = lines.drop(cause + 1)
            .takeWhile { it.startsWith("\tat ") }
            .map { it.removePrefix("\tat ") }
            .firstOrNull { frame -> PLATFORM.none { frame.startsWith(it) } }
        return first != null && GURU_CLASS.containsMatchIn(first)
    }

    private val GURU_CLASS = Regex("""^com\.github\.reygnn\.guru\.[A-Z]""")

    /** Frames that are no app's own: the platform, the runtime, the libraries every app has. */
    private val PLATFORM = listOf(
        "android.", "androidx.", "com.android.", "dalvik.", "java.", "javax.", "jdk.", "kotlin.", "kotlinx.", "libcore.", "sun.",
    )

    fun subject(report: GuruReport): String =
        "Guru Meditation ${report.number} · ${report.env.packageName} ${report.env.versionName}"

    /** At most [max] chars of [text] — never half a surrogate pair — and how much was cut. */
    fun cap(text: String, max: Int): String {
        if (text.length <= max) return text
        val cut = if (max > 0 && text[max - 1].isHighSurrogate()) max - 1 else max
        return text.substring(0, cut) + "\n[${text.length - cut} chars cut]"
    }

    /** At most [maxBytes] of [input] as UTF-8; a sequence split at the cut decodes as U+FFFD. */
    fun readCapped(input: InputStream, maxBytes: Int): String {
        val bytes = input.readNBytes(maxBytes + 1)
        if (bytes.size <= maxBytes) return String(bytes, Charsets.UTF_8)
        return String(bytes, 0, maxBytes, Charsets.UTF_8) + "\n[cut at $maxBytes bytes]"
    }
}
