package com.github.reygnn.verso_launcher

import java.io.File

/**
 * The "?" list beside verso.json, newest first, so it outlives the process. Disposable like
 * [UsageLog]: lost or torn, the list starts over. Used on VersoApp.io for the file, pure otherwise.
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
    fun read(dir: File, limit: Int): List<String>? = try {
        val file = File(dir, FILE)
        if (!file.isFile || file.length() > MAX_BYTES) emptyList() else parse(file.readText(), limit)
    } catch (e: Exception) {
        null
    }

    fun write(dir: File, keys: List<String>) {
        val tmp = File(dir, "$FILE.tmp")
        tmp.writeText(format(keys))
        if (!tmp.renameTo(File(dir, FILE))) tmp.delete()
    }
}
