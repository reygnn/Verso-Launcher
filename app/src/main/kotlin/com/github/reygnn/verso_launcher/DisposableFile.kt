package com.github.reygnn.verso_launcher

import java.io.File

/**
 * Replaces [name] in [dir] with [text] through a temp file and a rename — for the disposable files
 * beside verso.json ([HomeCache], [UsageLog], [RecentsLog]). No fsync: lost or torn, each starts
 * over. Used on VersoApp.io.
 */
internal fun writeDisposable(dir: File, name: String, text: String) {
    val tmp = File(dir, "$name.tmp")
    tmp.writeText(text)
    if (!tmp.renameTo(File(dir, name))) tmp.delete()
}
