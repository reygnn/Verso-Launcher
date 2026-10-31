package com.github.reygnn.verso_launcher

import java.io.File
import kotlin.math.exp

/** One app's launches as Kolibri's decayed sum, kept as its value at [at] (epoch ms). */
internal data class Usage(val score: Double, val at: Long)

/**
 * The "%" order: every launch from Verso counts e^(-λ·Δt) (Kolibri's λ = 1e-6/s: a day 92 %,
 * a week 55 %, 30 days 7.5 %). The sum is carried as one number per app, decayed on each use.
 * Its file, among the app's own, is disposable like [HomeCache]: lost or torn, counting starts over.
 * Used on VersoApp.io for the file, pure otherwise; [read]'s log is the caller's.
 */
internal object UsageLog {

    const val FILE = "usage.txt"

    const val LAMBDA_PER_MS = 1e-9

    /** Below this an entry is dropped on write (one launch, about 53 days ago). */
    const val FORGET_BELOW = 0.01

    /** A clock set back counts as no time passed, never as a gain. */
    fun decayed(usage: Usage, now: Long): Double =
        usage.score * exp(-LAMBDA_PER_MS * (now - usage.at).coerceAtLeast(0L))

    /** A launch stamped while the clock ran ahead would never decay: from [now] on it does. */
    fun settled(usage: Usage, now: Long): Usage = if (usage.at > now) usage.copy(at = now) else usage

    fun recorded(log: Map<String, Usage>, key: String, now: Long): Map<String, Usage> {
        val before = log[key]?.let { decayed(it, now) } ?: 0.0
        return log.mapValues { settled(it.value, now) } + (key to Usage(before + 1.0, now))
    }

    /** Launches counted before the file was read are added to what it held. */
    fun merged(disk: Map<String, Usage>, memory: Map<String, Usage>, now: Long): Map<String, Usage> {
        val out = HashMap(disk.mapValues { settled(it.value, now) })
        for ((key, usage) in memory) {
            val sum = decayed(usage, now) + (disk[key]?.let { decayed(it, now) } ?: 0.0)
            out[key] = Usage(sum, now)
        }
        return out
    }

    /** Most used first; unused apps and ties keep [all]'s order (A to Z). */
    fun <T : Ordered> byUsage(all: List<T>, log: Map<String, Usage>, now: Long): List<T> {
        val scores = all.associate { it.key to (log[it.key]?.let { u -> decayed(u, now) } ?: 0.0) }
        return all.sortedByDescending { scores.getValue(it.key) }
    }

    /** One "key<TAB>score<TAB>at" line per app still worth keeping. */
    fun format(log: Map<String, Usage>, now: Long): String = buildString {
        for ((key, usage) in log) {
            if (decayed(usage, now) < FORGET_BELOW) continue
            append(key).append('\t').append(usage.score).append('\t').append(minOf(usage.at, now)).append('\n')
        }
    }

    fun parse(text: String): Map<String, Usage> {
        // One line of [format], or null for a torn or foreign one: it is skipped.
        fun entryOf(line: String): Pair<String, Usage>? {
            val parts = line.split('\t').takeIf { it.size == FIELDS && it[0].isNotEmpty() } ?: return null
            val score = parts[1].toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 }
            val at = parts[2].toLongOrNull()?.takeIf { it >= 0 }
            return if (score != null && at != null) parts[0] to Usage(score, at) else null
        }
        val out = HashMap<String, Usage>()
        for (line in text.lineSequence()) {
            val (key, usage) = entryOf(line) ?: continue
            out[key] = usage
        }
        return out
    }

    /** key, score, at: what [format] writes on each line. */
    private const val FIELDS = 3

    /** Ours stays far smaller; anything larger is not read into memory, just replaced. */
    const val MAX_BYTES = 1_000_000L

    /** Null when the file is there but could not be read: not loaded, the next refresh retries
     *  (writing over it would wipe the counts). No file, or a file too large, is an empty log. */
    // null is the answer (see above)
    @Suppress("TooGenericExceptionCaught")
    fun read(dir: File, log: (String, Exception?) -> Unit): Map<String, Usage>? = try {
        val file = File(dir, FILE)
        if (!file.isFile || file.length() > MAX_BYTES) emptyMap() else parse(file.readText())
    } catch (e: Exception) {
        log("$FILE unreadable, not loaded", e)
        null
    }

    fun write(dir: File, log: Map<String, Usage>, now: Long) = writeDisposable(dir, FILE, format(log, now))
}
