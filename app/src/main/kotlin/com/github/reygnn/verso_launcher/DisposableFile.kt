package com.github.reygnn.verso_launcher

import java.io.File

/**
 * Replaces [name] in [dir] with [text] through a temp file and a rename, for the small files the
 * app keeps.
 *
 * Thread: VersoApp.io.
 * Why: there is no fsync because none of these files is precious. If one is lost, the cost is a
 *   slow first frame, the usage counts or one more "~open".
 */
internal fun writeDisposable(dir: File, name: String, text: String) {
    val tmp = File(dir, "$name.tmp")
    tmp.writeText(text)
    if (!tmp.renameTo(File(dir, name))) tmp.delete()
}
