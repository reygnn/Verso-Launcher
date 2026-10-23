package com.github.reygnn.guru

import java.util.Locale

/**
 * "#80000001.4A3F21C0": the kind, then a hash of where it happened. Messages and line numbers
 * stay out of the hash, so the same bug keeps its number from build to build — as long as R8
 * names it the same (a new mapping can rename it, the way a new Kickstart moved the addresses).
 */
internal object GuruNumber {

    /** Frames of the root cause that make up the signature. */
    const val FRAMES = 3

    private const val CAUSED_BY = "Caused by: "
    private const val JAVA_FRAME = "\tat "
    private const val FNV_OFFSET = 0x811C_9DC5L
    private const val FNV_PRIME = 0x0100_0193L
    private const val LOW_32 = 0xFFFF_FFFFL
    private const val BYTE = 0xFFL

    fun of(kind: GuruKind, trace: String): String =
        String.format(Locale.ROOT, "#%08X.%08X", kind.code, fnv1a(signature(kind, trace)))

    /** What the hash is taken of. Falls back to the kind and the first non-blank line. */
    fun signature(kind: GuruKind, trace: String): String {
        val lines = trace.lines()
        val parsed = when (kind) {
            GuruKind.CRASH -> javaSignature(lines)
            GuruKind.ANR -> anrSignature(lines)
            GuruKind.NATIVE -> null
        }
        return parsed ?: (kind.name + "\n" + lines.firstOrNull { it.isNotBlank() }?.trim().orEmpty())
    }

    /** FNV-1a, 32 bit, over UTF-8: fixed by its spec, unlike a hashCode() that may change. */
    fun fnv1a(text: String): Long {
        var hash = FNV_OFFSET
        for (b in text.toByteArray(Charsets.UTF_8)) {
            hash = ((hash xor (b.toLong() and BYTE)) * FNV_PRIME) and LOW_32
        }
        return hash
    }

    // The root cause: the last "Caused by:" at the left margin (an indented one belongs to a
    // suppressed exception), else the top exception. Its type, then its first frames without
    // the "(File.kt:12)".
    private fun javaSignature(lines: List<String>): String? {
        val start = lines.indices.lastOrNull { lines[it].startsWith(CAUSED_BY) } ?: 0
        val type = lines[start].removePrefix(CAUSED_BY).substringBefore(':').trim()
        if (type.isEmpty() || type.any { it.isWhitespace() }) return null
        val frames = lines.drop(start + 1)
            .takeWhile { it.startsWith(JAVA_FRAME) }
            .take(FRAMES)
            .map { it.removePrefix(JAVA_FRAME).substringBefore('(') }
        return (listOf(type) + frames).joinToString("\n")
    }

    // An ANR dump: the "main" thread's block, its first "at" frames.
    private fun anrSignature(lines: List<String>): String? {
        val main = lines.indexOfFirst { it.startsWith("\"main\"") }
        if (main < 0) return null
        val frames = lines.drop(main + 1)
            .takeWhile { it.isNotBlank() }
            .map { it.trim() }
            .filter { it.startsWith("at ") }
            .take(FRAMES)
            .map { it.removePrefix("at ").substringBefore('(') }
        return if (frames.isEmpty()) null else (listOf(GuruKind.ANR.name) + frames).joinToString("\n")
    }
}
