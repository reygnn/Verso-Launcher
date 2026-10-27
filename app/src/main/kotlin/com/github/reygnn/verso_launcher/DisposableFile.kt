package com.github.reygnn.verso_launcher

import java.io.File

/**
 * Replaces [name] in [dir] with [text] through a temp file and a rename — for the small files the
 * app keeps ([HomeCache], [UsageLog], [RecentsLog], [ConfigLink], [DrawerOrder]). No fsync: none
 * is precious, a lost one costs a slow frame, a count or one "~open". Used on VersoApp.io.
 */
internal fun writeDisposable(dir: File, name: String, text: String) {
    val tmp = File(dir, "$name.tmp")
    tmp.writeText(text)
    if (!tmp.renameTo(File(dir, name))) tmp.delete()
}
