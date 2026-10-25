package com.github.reygnn.verso_launcher

import java.io.File
import java.io.FileOutputStream

/**
 * verso.json on disk, on SharedPreferences' terms (hard rule 4); [ConfigJson] owns the JSON. Never
 * throws. A write first moves the last complete file to verso.json.bak (keeping one already there),
 * writes and fsyncs verso.json, then deletes the .bak; a load that finds a .bak takes it back, the
 * write before it was cut off. A file that cannot be read or parsed loads as the empty config.
 * [rename], [delete] and [readText] are test seams (root ignores chmod).
 */
internal class ConfigStore(
    private val dir: File,
    private val log: (String, Exception?) -> Unit,
    private val rename: (File, File) -> Boolean = File::renameTo,
    private val delete: (File) -> Boolean = File::delete,
    private val readText: (File) -> String = { it.readText() },
) {

    private val primary get() = File(dir, CONFIG_FILE)
    private val backup get() = File(dir, "$CONFIG_FILE.bak")

    fun load(): VersoConfig {
        var source = primary
        if (backup.exists()) {
            delete(primary)
            // Read in place, or the next write would delete the only complete copy.
            if (!rename(backup, primary)) {
                log("could not take back ${backup.name}, reading it in place", null)
                source = backup
            }
        }
        if (!source.isFile) return VersoConfig()
        // hard rule 4: unreadable = empty, never a throw
        val text = @Suppress("TooGenericExceptionCaught") try {
            readText(source)
        } catch (e: Exception) {
            log("config unreadable, loaded as empty", e)
            null
        }
        val parsed = text?.let(ConfigJson::parse)
        if (text != null && parsed == null) log("config unparseable, loaded as empty", null)
        return parsed ?: VersoConfig()
    }

    fun isFresh(): Boolean = !primary.exists() && !backup.exists()

    /** False if nothing was written; then the last complete file waits in verso.json.bak. */
    fun write(payload: String): Boolean {
        if (primary.exists()) {
            if (!backup.exists()) {
                if (!rename(primary, backup)) {
                    log("config save failed: could not move ${primary.name} to ${backup.name}", null)
                    return false
                }
            } else {
                delete(primary)
            }
        }
        // hard rule 4: a failed write is a false, the .bak stands (on a val: detekt ignores it behind `return`)
        val written = @Suppress("TooGenericExceptionCaught") try {
            FileOutputStream(primary).use { out ->
                out.write(payload.toByteArray(Charsets.UTF_8))
                out.flush()
                out.fd.sync()
            }
            if (backup.exists() && !delete(backup)) log("could not delete ${backup.name}: the next load takes it back", null)
            true
        } catch (e: Exception) {
            log("config save failed", e)
            if (primary.exists() && !delete(primary)) log("could not remove a half-written ${primary.name}", null)
            false
        }
        return written
    }

    companion object {
        const val CONFIG_FILE = "verso.json"
    }
}
