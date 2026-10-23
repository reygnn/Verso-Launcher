package com.github.reygnn.verso_launcher

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream

/**
 * verso.json on disk, written atomically (hard rule 4); [ConfigJson] owns the JSON. Never throws.
 * [syncDir] fsyncs the directory so a rename survives a power cut; [rename] and [readText] are
 * test seams (root ignores chmod).
 */
internal class ConfigStore(
    private val dir: File,
    private val log: (String, Exception?) -> Unit,
    private val syncDir: (File) -> Unit,
    private val rename: (File, File) -> Boolean = File::renameTo,
    private val readText: (File) -> String = { it.readText() },
) {

    private val primary get() = File(dir, CONFIG_FILE)
    private val pending get() = File(dir, "$CONFIG_FILE.new")
    private val setAside get() = File(dir, "$CONFIG_FILE.unreadable")

    /** Null, never an empty config, when verso.json is there but torn or unreadable. */
    fun load(): VersoConfig? {
        if (!primary.isFile) return VersoConfig()
        // hard rule 4: unreadable = null, never a throw
        val text = @Suppress("TooGenericExceptionCaught") try {
            readText(primary)
        } catch (e: Exception) {
            log("config unreadable", e)
            return null
        }
        return ConfigJson.parse(text) ?: run { log("config unparseable", null); null }
    }

    fun isFresh(): Boolean = !primary.exists()

    /** False if nothing was published (the old file stands). */
    // hard rule 4: the old file stands
    @Suppress("TooGenericExceptionCaught")
    fun write(payload: String): Boolean = try {
        FileOutputStream(pending).use { out ->
            out.write(payload.toByteArray(Charsets.UTF_8))
            out.flush()
            out.fd.sync()
        }
        if (!rename(pending, primary)) throw java.io.IOException("rename of ${pending.name} refused")
        syncDir(dir)
        true
    } catch (e: Exception) {
        log("config save failed", e)
        pending.delete()
        false
    }

    /** Exports the text (to Download/Verso) before deleting; failing that, renames it to
     *  verso.json.unreadable. True when verso.json is gone. */
    // hard rule 4: breaks the crash loop
    @Suppress("TooGenericExceptionCaught")
    fun setAsideUnreadable(export: (String) -> Boolean = { false }): Boolean = try {
        val text = try {
            readText(primary)
        } catch (e: Exception) {
            log("config unreadable for export, renaming instead", e)
            null
        }
        if (text != null && export(text)) {
            primary.delete().also { if (it) syncDir(dir) else log("could not remove the exported config", null) }
        } else {
            rename(primary, setAside).also { if (it) syncDir(dir) else log("could not set the config aside", null) }
        }
    } catch (e: Exception) {
        log("setting the config aside failed", e)
        false
    }

    companion object {
        const val CONFIG_FILE = "verso.json"

        /**
         * fsync [dir] so a preceding rename() survives power loss, not merely is visible: the
         * [syncDir] MainActivity hands in. Best-effort: a failure only weakens durability, so it
         * is logged, not thrown.
         */
        fun fsyncDir(dir: File, log: (String, Exception?) -> Unit) {
            var fd: FileDescriptor? = null
            try {
                fd = Os.open(dir.path, OsConstants.O_RDONLY, 0)
                Os.fsync(fd)
            } catch (e: ErrnoException) {
                log("dir fsync failed (non-fatal): ${dir.path}", e)
            } finally {
                if (fd != null) {
                    @Suppress("SwallowedException") try {
                        Os.close(fd)
                    } catch (e: ErrnoException) {
                        // Nothing actionable at close time; the rename or delete already happened.
                    }
                }
            }
        }
    }
}
