package com.github.reygnn.verso_launcher

import java.io.File
import java.io.FileOutputStream

/**
 * The one undo point: the config the last "~restore" or "~undo" replaced, as verso.prev.json beside
 * verso.json. One slot, not a history: "~undo" swaps it with the config in use, so a second "~undo"
 * redoes. It lives with the app like verso.json (gone with an uninstall); what outlives the app is
 * the user's own "~backup".
 *
 * Read and written on main, in the step that adopts (LauncherState.replace): no edit can come between
 * the undo point and the swap (one of hard rule 3's exceptions; a few KB in the app's own files).
 * Never throws.
 */
internal class UndoSlot(
    private val dir: File,
    private val log: (String, Exception?) -> Unit,
) {
    private val file get() = File(dir, FILE)
    private val temp get() = File(dir, "$FILE.new")

    /** Null when there is none, or it cannot be read (logged). */
    fun read(): VersoConfig? {
        if (!file.isFile) return null
        // hard rule 2: an unreadable slot is "nothing to undo"
        val text = @Suppress("TooGenericExceptionCaught") try {
            file.readText()
        } catch (e: Exception) {
            log("$FILE unreadable", e)
            return null
        }
        return ConfigJson.parse(text).also { if (it == null) log("$FILE unparseable", null) }
    }

    /** Written whole or not at all: a temp file, fsynced, then renamed over the slot. */
    fun write(payload: String): Boolean {
        // hard rule 2: no undo point is a refused restore, not a crash
        val written = @Suppress("TooGenericExceptionCaught") try {
            FileOutputStream(temp).use { out ->
                out.write(payload.toByteArray(Charsets.UTF_8))
                out.flush()
                out.fd.sync()
            }
            temp.renameTo(file).also { if (!it) log("$FILE not written: rename refused", null) }
        } catch (e: Exception) {
            log("$FILE not written", e)
            false
        }
        if (!written && temp.exists()) temp.delete()
        return written
    }

    companion object {
        const val FILE = "verso.prev.json"
    }
}
