package com.github.reygnn.verso_launcher

import java.io.File
import java.io.FileOutputStream

/**
 * verso.json on disk (hard rule 4); [ConfigJson] owns the JSON. Never throws.
 *
 * A write goes to verso.json.new, is fsynced and renamed over verso.json, then the directory is
 * synced: the file on disk is always one complete config, the old or the new. A file that cannot be
 * read or parsed is set aside as verso.json.unreadable before [load] answers null (it loads as the
 * empty config, Core.newState, and the user is told), so the next write never lands on it; a later
 * load with no verso.json reads it again and takes it back if it is readable by then (a passing read
 * error heals itself).
 *
 * A verso.json.bak is what versions up to 0.5.9 left when a write was cut off: taken back once.
 * [rename], [delete], [readText] and [writeSynced] are test seams (root ignores chmod).
 *
 * @param syncDir fsyncs the directory after a rename; the platform's (Os.fsync, Core), since
 *   Android's FileChannel refuses to open a directory.
 */
internal class ConfigStore(
    private val dir: File,
    private val log: (String, Exception?) -> Unit,
    private val syncDir: (File) -> Unit,
    private val rename: (File, File) -> Boolean = File::renameTo,
    private val delete: (File) -> Boolean = File::delete,
    private val readText: (File) -> String = { it.readText() },
    private val writeSynced: (File, ByteArray) -> Unit = ::writeAndSync,
) {

    private val primary get() = File(dir, CONFIG_FILE)
    private val temp get() = File(dir, "$CONFIG_FILE.new")
    private val aside get() = File(dir, "$CONFIG_FILE.unreadable")
    private val legacyBackup get() = File(dir, "$CONFIG_FILE.bak")

    /**
     * Null when a file is there but cannot be read or parsed; no file is the empty config.
     * Synchronized with [write]: one lock for the file, whoever reads or writes it (the old state's
     * queued save may still write on IO while Core loads the new one on main, "~restart").
     */
    @Synchronized
    fun load(): VersoConfig? {
        if (temp.exists()) delete(temp)  // a write cut off before its rename: never complete
        val takenBack = takeBackLegacyBackup()
        return when {
            takenBack != null -> takenBack
            !primary.isFile -> takeBackAside()
            else -> read(primary).also { if (it == null) setAside() }
        }
    }

    /** Neither verso.json nor anything that stands for it: a fresh install (hard rule 10). */
    fun isFresh(): Boolean = !primary.exists() && !legacyBackup.exists() && !aside.exists()

    /** False if nothing was written; verso.json is then as it was. */
    @Synchronized
    fun write(payload: String): Boolean {
        // hard rule 4: a failed write is a false, verso.json stands (on a val: detekt ignores it behind `return`)
        val written = @Suppress("TooGenericExceptionCaught") try {
            writeSynced(temp, payload.toByteArray(Charsets.UTF_8))
            if (!rename(temp, primary)) throw java.io.IOException("could not rename ${temp.name} to ${primary.name}")
            true
        } catch (e: Exception) {
            log("config save failed", e)
            if (temp.exists() && !delete(temp)) log("could not remove a half-written ${temp.name}", null)
            false
        }
        if (written) syncDir(dir)
        return written
    }

    /** The parsed config, or null (logged) when [file] cannot be read or parsed. */
    private fun read(file: File): VersoConfig? {
        // hard rule 4: unreadable = null, never a throw
        val text = @Suppress("TooGenericExceptionCaught") try {
            readText(file)
        } catch (e: Exception) {
            log("${file.name} unreadable", e)
            return null
        }
        return ConfigJson.parse(text).also { if (it == null) log("${file.name} unparseable", null) }
    }

    /**
     * Moved out of the next write's way, as it is. One file aside: a later unreadable one replaces
     * it — by the rename itself, so a rename that fails leaves the earlier one in place.
     */
    private fun setAside() {
        if (rename(primary, aside)) log("config set aside as ${aside.name}, loaded as empty", null)
        else log("could not set ${primary.name} aside: the next write replaces it", null)
    }

    /** No verso.json: one set aside earlier comes back if it can be read now. */
    private fun takeBackAside(): VersoConfig? {
        if (!aside.isFile) return VersoConfig()
        val parsed = read(aside) ?: return null
        if (rename(aside, primary)) log("${aside.name} readable again, taken back", null)
        return parsed
    }

    /**
     * Up to 0.5.9 a write moved verso.json to .bak first: one left means that write was cut off and
     * the .bak is the last complete file. Null when there is none, or it cannot be read — then it
     * is set aside like an unreadable verso.json (a read error may pass), and verso.json is read.
     * Left where it is, a later load could take it back over the newer saves.
     */
    private fun takeBackLegacyBackup(): VersoConfig? {
        if (!legacyBackup.isFile) return null
        val parsed = read(legacyBackup)
        if (parsed == null) {
            if (!rename(legacyBackup, aside) && !delete(legacyBackup)) log("could not move ${legacyBackup.name} out of the way", null)
            return null
        }
        if (primary.exists()) delete(primary)
        if (!rename(legacyBackup, primary)) {
            // Rewritten as verso.json, so the .bak can go and a later load cannot take it back.
            if (write(ConfigJson.serialize(parsed))) delete(legacyBackup)
        }
        return parsed
    }

    companion object {
        const val CONFIG_FILE = "verso.json"

        private fun writeAndSync(file: File, bytes: ByteArray) = FileOutputStream(file).use { out ->
            out.write(bytes)
            out.flush()
            out.fd.sync()
        }
    }
}
