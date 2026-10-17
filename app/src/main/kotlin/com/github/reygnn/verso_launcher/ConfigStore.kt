package com.github.reygnn.verso_launcher

import java.io.File
import java.io.FileOutputStream

/**
 * verso.json on disk: one file, written atomically, read once. [ConfigJson] owns the JSON; this
 * owns the FILE. No .bak, no repair from older copies — the safety net is the user's ~backup.
 *
 * Everything Android-only is injected, so a test drives it against a temp directory:
 *   [dir]      where verso.json lives (filesDir in the app)
 *   [log]      android.util.Log in the app, a collector in tests — this class never throws
 *   [syncDir]  fsync of the directory (android.system.Os), so a rename survives a power cut
 *   [rename]   File.renameTo — a seam so a test can make one rename fail
 *   [readText] File.readText — a seam so a test can make a read throw (root ignores chmod)
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

    /**
     * The config, or the empty one when there is no verso.json (a fresh install). Null —
     * never an empty config — when it is there but torn or cannot be read: an empty config
     * would be saved over it with the next edit. The first load then sets it aside and starts
     * over (LauncherState). Never throws.
     */
    fun load(): VersoConfig? {
        // Files of earlier versions (.bak, .tmp, numbered set-aside copies) and a .new left by
        // a crash: none is read any more. This line can go once no device carries them.
        dir.listFiles { f -> f.name.startsWith("$CONFIG_FILE.") && f.name != setAside.name }
            ?.forEach { it.delete() }
        if (!primary.isFile) return VersoConfig()
        val text = try {
            readText(primary)
        } catch (e: Exception) {
            log("config unreadable", e)
            return null
        }
        return ConfigJson.parse(text) ?: run { log("config unparseable", null); null }
    }

    /** True on a fresh install: no verso.json yet. Only then are default favorites seeded. */
    fun isFresh(): Boolean = !primary.exists()

    /**
     * Publish [payload] as verso.json: written to verso.json.new, synced, then renamed over the
     * primary — a reader sees the old file or the new one, never half of one. Never throws;
     * false if nothing was published (the old file stands).
     */
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

    /**
     * Move an unreadable verso.json out of the way. Where the user can see it: [export] gets its
     * text (Download/Verso, a published copy) and only then is verso.json deleted. If its bytes
     * cannot be read or the export fails, it is renamed to verso.json.unreadable instead —
     * replacing an earlier one, so there is only ever one. True when verso.json is gone, so the
     * load can start over. Never throws.
     */
    fun setAsideUnreadable(export: (String) -> Boolean = { false }): Boolean = try {
        val text = try { readText(primary) } catch (e: Exception) { null }
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
    }
}
