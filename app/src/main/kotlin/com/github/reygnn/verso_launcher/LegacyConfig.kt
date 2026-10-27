package com.github.reygnn.verso_launcher

import java.io.File

/**
 * verso.json, the config of versions before the config file: read while no file is linked, never
 * written, never deleted ("~new" carries it over into a file of the user's). A verso.json.bak is
 * what versions up to 0.5.9 left when a write was cut off: the last complete file, read when
 * verso.json is missing. Never throws; [read]'s log is the caller's.
 */
internal object LegacyConfig {

    const val FILE = "verso.json"
    private const val BAK = "$FILE.bak"

    sealed interface Read {
        /** Neither file: a fresh install (hard rule 10). */
        data object Absent : Read
        data class Found(val config: VersoConfig) : Read
        /** There, but it cannot be read or parsed: it is left as it is. */
        data object Unreadable : Read
    }

    fun read(dir: File, log: (String, Exception?) -> Unit): Read {
        val file = listOf(File(dir, FILE), File(dir, BAK)).firstOrNull { it.isFile } ?: return Read.Absent
        // unreadable = the answer, never a throw
        val text = @Suppress("TooGenericExceptionCaught") try {
            file.readText()
        } catch (e: Exception) {
            log("${file.name} unreadable", e)
            return Read.Unreadable
        }
        // Our own file, from before: the lenient parse (a missing section is empty).
        val config = ConfigJson.parse(text) ?: return Read.Unreadable.also { log("${file.name} unparseable", null) }
        return Read.Found(config)
    }
}
