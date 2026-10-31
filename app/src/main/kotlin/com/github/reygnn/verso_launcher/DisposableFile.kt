package com.github.reygnn.verso_launcher

import java.io.File
import java.io.IOException

/**
 * Replaces [name] in [dir] with [text] through a temp file and a rename, for the small files the
 * app keeps. A rename that fails throws once the temp file is gone, so [Guard] logs it (hard
 * rule 2) and the file keeps what it held.
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
