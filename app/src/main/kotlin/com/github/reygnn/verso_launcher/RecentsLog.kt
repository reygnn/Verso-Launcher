package com.github.reygnn.verso_launcher

import java.io.File

/**
 * The "?" list in the app's own files, newest first, so it outlives the process. Disposable like
 * [UsageLog]: lost or torn, the list starts over. Used on VersoApp.io for the file, pure otherwise;
 * [read]'s log is the caller's.
 */
internal object RecentsLog {

    const val FILE = "recents.txt"

    /** Ours is a few hundred bytes; anything larger is not read into memory, just replaced. */
    const val MAX_BYTES = 64_000L

    /** Launches made before the file was read come first. */
    fun merged(disk: List<String>, memory: List<String>, limit: Int): List<String> =
        (memory + disk).distinct().take(limit)

    fun format(keys: List<String>): String = keys.joinToString("") { "$it\n" }

    fun parse(text: String, limit: Int): List<String> =
        text.lineSequence().filter { it.isNotEmpty() }.distinct().take(limit).toList()

    /** Null when the file is there but could not be read: not loaded, the next refresh retries
     *  (writing over it would lose the list). No file, or a file too large, is an empty list. */
    // null is the answer (see above)
    @Suppress("TooGenericExceptionCaught")
    fun read(dir: File, limit: Int, log: (String, Exception?) -> Unit): List<String>? = try {
        val file = File(dir, FILE)
        if (!file.isFile || file.length() > MAX_BYTES) emptyList() else parse(file.readText(), limit)
    } catch (e: Exception) {
        log("$FILE unreadable, not loaded", e)
        null
    }

    fun write(dir: File, keys: List<String>) = writeDisposable(dir, FILE, format(keys))
}
