package com.github.reygnn.verso_launcher

import java.io.File

/**
 * The user's config file at one fixed place: config.txt in the app's own folder on shared storage
 * (Android/data/<package>/files/), put there with `adb push` or from a PC. Verso reads it and never
 * writes it (hard rule 4). It is read once per state, on main, before the state exists (hard rule
 * 5), so "~restart" is the reload.
 *
 * Why: a fixed place needs no document picker, no grant to keep and no provider that may block,
 *   so the read needs no thread, no copy of the last text and no read numbers of its own. The
 *   price is in CAVEATS.md: since Android 11 no other app on the phone reaches that folder, so the
 *   file is edited on a PC, and an uninstall deletes it.
 */
internal object ConfigFile {

    const val NAME = "config.txt"

    /**
     * The config [dir] holds and how its read went. Anything but [ConfigStatus.READ] means the
     * default favorites (hard rule 10): no folder (shared storage not mounted) or no file, a file
     * past [ConfigText.readCapped]'s cap, or one that cannot be read. An empty file is a config. A
     * line that is no entry is skipped and logged, the rest counts. Every failure is logged; on
     * screen only "~verify" says it ([ConfigReport]).
     *
     * Why: the read runs on main as the state is built, at every start; a throw here would kill
     *   every start (hard rule 2), so the parse is inside the catch too.
     */
    fun read(dir: File?, log: (String, Exception?) -> Unit): ConfigRead {
        val file = dir?.let { File(it, NAME) }?.takeIf { it.isFile } ?: return ConfigRead(ConfigStatus.MISSING)
        // hard rule 2: an unreadable file is the defaults and a log line, never a throw on main
        val read = @Suppress("TooGenericExceptionCaught") try {
            val text = file.inputStream().use { ConfigText.readCapped(it) }
            if (text == null) log("$NAME is larger than any config, not read", null)
            text?.let { ConfigRead(ConfigStatus.READ, ConfigText.parse(it)) } ?: ConfigRead(ConfigStatus.TOO_LARGE)
        } catch (e: Exception) {
            log("$NAME unreadable", e)
            ConfigRead(ConfigStatus.UNREADABLE)
        }
        if (read.text.badLines.isNotEmpty()) {
            log("$NAME: lines that are no entry, skipped: ${read.text.badLines.joinToString(", ") { "${it.number}" }}", null)
        }
        return read
    }
}

/** How the read of the user's file went; only [READ] is a config of theirs. */
internal enum class ConfigStatus { READ, MISSING, TOO_LARGE, UNREADABLE }

/** A read of the user's file: [text] is empty unless [status] is [ConfigStatus.READ]. */
internal data class ConfigRead(val status: ConfigStatus, val text: ConfigText = ConfigText())
