package com.github.reygnn.verso_launcher

import java.io.File
import java.io.IOException

/**
 * The small files the app keeps for itself ([UsageLog], [RecentsLog], [HomeCache], [DrawerOrder]):
 * one way to read one and one way to write one. None of them is precious, and none is the user's
 * (that is [ConfigFile], which is never written).
 */

/**
 * What [name] in [dir] holds, as [parse] reads its text. [absent] when there is no such file or it
 * is larger than [maxBytes]: ours stay far smaller, so a file that big is not ours, is not read
 * into memory and is replaced by the next write. Null when the file is there but cannot be read
 * or parsed, which [log] says: a caller that must not write over such a file then knows.
 *
 * Why: [parse] runs inside the catch. The reads run on the IO thread within a refresh, or on main
 *   before the state exists; a throw from either would cost the app list or the start (hard
 *   rule 2).
 */
// null is the answer: an unreadable file is a log line, never a throw
@Suppress("TooGenericExceptionCaught")
internal fun <T : Any> readDisposable(
    dir: File,
    name: String,
    maxBytes: Long,
    absent: T,
    log: (String, Exception?) -> Unit,
    parse: (String) -> T,
): T? = try {
    val file = File(dir, name)
    if (!file.isFile || file.length() > maxBytes) absent else parse(file.readText())
} catch (e: Exception) {
    log("$name unreadable", e)
    null
}

/**
 * Replaces [name] in [dir] with [text] through a temp file and a rename. A rename that fails
 * throws once the temp file is gone, so [Guard] logs it (hard rule 2) and the file keeps what it
 * held.
 *
 * Thread: VersoApp.io.
 * Why: there is no fsync because none of these files is precious. If one is lost, the cost is a
 *   slow first frame, the recents, the usage counts or the "%" order.
 */
internal fun writeDisposable(dir: File, name: String, text: String) {
    val tmp = File(dir, "$name.tmp")
    tmp.writeText(text)
    if (!tmp.renameTo(File(dir, name))) {
        tmp.delete()
        throw IOException("$name: rename failed, not written")
    }
}
