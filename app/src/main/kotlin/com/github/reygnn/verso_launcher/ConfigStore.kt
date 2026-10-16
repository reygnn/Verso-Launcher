package com.github.reygnn.verso_launcher

import java.io.File
import java.io.FileOutputStream

/**
 * The verso.json file layer: reading the config with its .tmp and .bak fallbacks, and
 * publishing it back atomically with a validated .bak rotation. [ConfigJson] owns the JSON; this owns
 * the FILES.
 *
 * Lifted out of MainActivity so the rotation state machine can be unit-tested on the JVM.
 * That machine — re-parse the on-disk primary, skip the rotation when it no longer parses,
 * heal-without-rotating versus normal-save-with-rotating, rename-or-fall-back-to-in-place —
 * is the highest-consequence logic in the app: getting it wrong silently destroys the last
 * known-good backup, which has happened here once already. It was also the only part with
 * no test, precisely because it sat behind an Activity.
 *
 * Everything it needs from Android is injected, so a test can drive it against a temp
 * directory:
 *   [dir]     where verso.json, its .bak and its .tmp live (filesDir in the app)
 *   [log]     android.util.Log in the app, a collector in tests — this class never
 *             throws, so the log IS its error channel and a test can assert on it
 *   [syncDir] fsync of the directory, the one genuinely Android-only call
 *             (android.system.Os). File CONTENTS are synced with plain
 *             java.io.FileDescriptor.sync(), which works on any JVM and is therefore
 *             exercised for real by the tests.
 *   [rename]  the atomic rename primitive, defaulting to [File.renameTo]. A seam only
 *             so a test can force it to fail on a chosen file: the two "rename refused"
 *             paths below (the .bak rotation and the temp->primary publish with its
 *             in-place fallback) never fire on a normal filesystem, so a real temp dir
 *             cannot reach them. Production is unchanged — it uses [File.renameTo].
 */
internal class ConfigStore(
    private val dir: File,
    private val log: (String, Exception?) -> Unit,
    private val syncDir: (File) -> Unit,
    private val rename: (File, File) -> Boolean = File::renameTo,
) {

    private val primary get() = File(dir, CONFIG_FILE)
    private val backup get() = File(dir, "$CONFIG_FILE.bak")
    private val temp get() = File(dir, "$CONFIG_FILE.tmp")

    /**
     * Read the config, preferring the primary verso.json and falling back to an unpublished
     * .tmp, then to the .bak mirror written by [write], if the primary is missing or unreadable.
     * A HOME app must never throw on resume, so no path here does — and a single torn read of
     * the primary does not discard the user's favorites/hidden/names/tags.
     *
     * Two kinds of "nothing to read", told apart:
     *  - No published copy at all (neither primary nor .bak is a file): a fresh install, or one
     *    whose very first save never got published. The empty config IS the truth then.
     *  - A published copy exists, but nothing parses — a read error, or corruption of both:
     *    returns NULL. The user's config is there, just not to be had right now, and an empty
     *    default must never stand in for it: saved, it would rotate the real primary into .bak
     *    and push it out with the next save. Callers treat null as "not loaded" — no edit, no
     *    save, no backup of it (hard rule 5) — and try again later.
     *
     * And a primary that could not be READ (an I/O error) is not a torn one: its content may be
     * perfectly good — the newest save. Healing from .bak then would write the config from
     * before that save over it, losing the latest change for good. So no fallback and no heal
     * for a read error: null, "not to be had right now", and the next load tries again. Only a
     * primary that reads but does not parse (or is missing) falls back to .tmp and .bak.
     */
    fun load(): VersoConfig? {
        when (val first = read(primary)) {
            is Read.Parsed -> return first.config
            Read.Failed -> {
                log("primary config could not be read — not replaced from a fallback, tried again later", null)
                return null
            }
            Read.Absent, Read.Corrupt -> Unit
        }
        // The temp before the .bak: a temp survives only when the most recent write did not
        // complete its publish (every completed write renames it away), so a temp that parses
        // is the NEWEST config — e.g. after a crash between the .bak rotation and the publish,
        // where the primary is gone and .bak is one save behind. A temp torn mid-write does not
        // parse and falls through to .bak. Healed without rotating, like the .bak case below:
        // .bak still holds the previous good copy.
        parse(temp)?.let { recovered ->
            log("primary config unreadable — recovered the newer, unpublished .tmp", null)
            publishTemp(recovered)
            return recovered
        }
        parse(backup)?.let { recovered ->
            log("primary config unreadable — recovered from .bak", null)
            // Heal the primary now instead of waiting for the next toggle: rewrite the
            // recovered config over the bad primary so later resumes stop hitting this
            // path and we're never left running on a single copy. rotateBackup = false:
            // .bak IS the good copy — rotating the torn primary into it would destroy the
            // very backup we just read from.
            write(ConfigJson.serialize(recovered), rotateBackup = false)
            return recovered
        }
        // isFile, not exists(): a directory where a file should be holds no config either.
        if (primary.isFile || backup.isFile) {
            log("config unreadable — not loaded, nothing written", null)
            return null
        }
        return VersoConfig()
    }

    /**
     * Heal from a .tmp that just parsed: publish THAT file as the primary by renaming it, the
     * step its interrupted write never got to. Not via [write]: write() starts by rewriting the
     * .tmp, which would truncate the only copy of the newest config before writing it again —
     * a crash or a full disk in that moment would leave it torn and lose the save it held.
     * If the rename is refused, the primary is written in place (fsynced) from [recovered] and
     * the .tmp is left alone; .bak is never touched.
     */
    private fun publishTemp(recovered: VersoConfig) {
        try {
            if (!rename(temp, primary)) {
                FileOutputStream(primary).use { fos ->
                    fos.write(ConfigJson.serialize(recovered).toByteArray(Charsets.UTF_8))
                    fos.flush()
                    fos.fd.sync()
                }
            }
            syncDir(dir)
        } catch (e: Exception) {
            log("config heal from .tmp failed", e)
        }
    }

    /** What [setAsideUnreadable] did. */
    sealed interface SetAside {
        /** The config reads after all — or there is none (a fresh install): nothing was moved.
         *  [config] is what [load] gave. */
        data class Readable(val config: VersoConfig) : SetAside
        /** The unreadable files were renamed to [names]; no published copy is left, so the next
         *  [load] is a fresh install's. */
        data class Moved(val names: List<String>) : SetAside
        /** A rename was refused (logged): what was moved stays moved, the rest stays — still
         *  unreadable, nothing lost. */
        data object Failed : SetAside
    }

    /**
     * Move an unreadable config out of the way — "~reset", and a restore over an unreadable
     * config. Renamed, NEVER deleted: "<name>.unreadable" (or ".unreadable-2", -3 … beside an earlier
     * one), so the bytes stay for a manual rescue — the launcher can only ever tell that a file
     * cannot be read NOW, never that it is lost for good. A leftover .tmp, the .bak and the
     * primary all go — in that order, the primary last, so a refused rename (or a crash in
     * between) always leaves a published copy behind: still unreadable, as [SetAside.Failed]
     * says, never a silent fresh start on the next load. With all three moved, no published
     * copy is left: the next [load] is a fresh install's.
     *
     * Checks first, with [load] itself: a config that reads after all (a passing read error,
     * or a .tmp/.bak it can recover from) is returned untouched as [SetAside.Readable].
     * Never throws.
     */
    fun setAsideUnreadable(): SetAside {
        load()?.let { return SetAside.Readable(it) }
        return try {
            val moved = mutableListOf<String>()
            for (file in listOf(temp, backup, primary)) {
                if (!file.isFile) continue
                val target = freeUnreadableName(file)
                if (!rename(file, target)) {
                    log("could not set ${file.name} aside", null)
                    return SetAside.Failed
                }
                moved += target.name
            }
            syncDir(dir)
            log("unreadable config set aside: ${moved.joinToString()}", null)
            SetAside.Moved(moved)
        } catch (e: Exception) {
            log("setting the unreadable config aside failed", e)
            SetAside.Failed
        }
    }

    /**
     * The text of an unreadable config, read where it lies, for a copy out of the app's private
     * directory — taken BEFORE [setAsideUnreadable] moves the files, so a process killed in
     * between never leaves the user with no copy they can reach. The first of the primary, the
     * .bak and a leftover .tmp that can be read, holds more than whitespace (a primary torn to
     * 0 bytes says nothing, a .bak beside it may hold nearly everything) and is no larger than
     * [maxBytes] (a config is a few KB; anything far beyond is not worth a copy); null when none
     * is. Never throws.
     */
    fun readForCopy(maxBytes: Int = MAX_SET_ASIDE_COPY): String? {
        for (file in listOf(primary, backup, temp)) {
            if (!file.isFile) continue
            try {
                val bytes = file.inputStream().use { it.readNBytes(maxBytes + 1) }
                if (bytes.size > maxBytes) continue
                val text = bytes.toString(Charsets.UTF_8)
                if (text.isNotBlank()) return text
            } catch (e: Exception) {
                log("${file.name} unreadable for a copy", e)
            }
        }
        return null
    }

    /** "<name>.unreadable", or the first free "<name>.unreadable-2", -3 … — never over an earlier one. */
    private fun freeUnreadableName(file: File): File {
        var target = File(dir, "${file.name}.unreadable")
        var n = 1
        while (target.exists()) target = File(dir, "${file.name}.unreadable-${++n}")
        return target
    }

    /**
     * True on a fresh install: neither verso.json nor its .bak exists. Only then are default
     * favorites seeded — an existing config with no favorites is the user's choice. A file that
     * exists but is unreadable still counts as "not fresh": that is corruption, not a first start.
     */
    fun isFresh(): Boolean = !primary.exists() && !backup.exists()

    /**
     * Parse one config file. Returns null when the file is absent (a normal state, not
     * logged) or unparseable (logged, so real corruption is visible) — the caller decides
     * what to fall back to. Never throws.
     */
    private fun parse(file: File): VersoConfig? = (read(file) as? Read.Parsed)?.config

    /** What reading one config file gave — [load] tells a read error from a torn file. */
    private sealed interface Read {
        data object Absent : Read
        /** The file is there, but reading it threw (logged): its content is unknown. */
        data object Failed : Read
        /** Read, but not a config (logged). */
        data object Corrupt : Read
        data class Parsed(val config: VersoConfig) : Read
    }

    private fun read(file: File): Read {
        if (!file.isFile) return Read.Absent
        val text = try {
            file.readText()
        } catch (e: Exception) {
            log("config unreadable: ${file.path}", e)
            return Read.Failed
        }
        // ConfigJson owns the JSON parsing (and is unit-tested); it returns null, never
        // throws, on malformed input — log here where the file path is known.
        return ConfigJson.parse(text)?.let { Read.Parsed(it) }
            ?: run { log("config unparseable: ${file.path}", null); Read.Corrupt }
    }

    /**
     * Atomically publish [payload] as verso.json, writing the WHOLE file each time.
     * MUST be called from the caller's single disk-writing thread so concurrent writes
     * stay serialized on the temp file. Never throws — a failure degrades durability, not
     * correctness, and is logged: a HOME app must not crash on a bad save. Returns whether
     * the publish succeeded.
     *
     * Sequence: temp-write + fsync-contents -> rotate -> publish + fsync-dir.
     *   - fsync of the temp CONTENTS closes the window where the rename's metadata could
     *     reach disk ahead of the bytes (which would publish a truncated file).
     *   - the final fsync of the DIRECTORY makes the rename itself durable: rename is
     *     atomic for visibility but not durability, so without it a power-cut can roll the
     *     publish back to the previous file — a lost most-recent toggle, never corruption.
     *
     * [rotateBackup] moves the current primary into .bak (by rename, never an unsynced
     * copy) before the new one is published, so a good copy is on disk at all times; a
     * normal save wants this. A HEAL after recovering from .bak must NOT rotate at all:
     * there the on-disk primary is the torn file we're replacing and .bak holds the ONLY
     * good copy — rotating would overwrite that good .bak with garbage.
     */
    fun write(payload: String, rotateBackup: Boolean): Boolean {
        val tmp = temp
        val dst = primary
        val bak = backup
        return try {
            // (1) Write to the temp file and force its bytes onto disk BEFORE anything
            //     is published, so a rename can never expose contents that aren't there.
            FileOutputStream(tmp).use { fos ->
                fos.write(payload.toByteArray(Charsets.UTF_8))
                fos.flush()
                fos.fd.sync()
            }

            // (2) Rotate the current good primary into .bak by RENAME (atomic, can't
            //     tear) — unless we're healing, where the on-disk primary is bad and
            //     .bak is the good copy we must not clobber. We do NOT delete .bak
            //     first: renaming onto it replaces it atomically on POSIX, and leaving
            //     it keeps a good copy present at all times. On the first save dst
            //     doesn't exist yet, so .bak appears from save #2 onward.
            //
            //     But rename promotes the primary UNVALIDATED, so first re-parse it and
            //     skip the rotation if it no longer parses. After the first load the
            //     config is served from memory, so a primary that silently went bad afterwards (bit rot,
            //     or a torn in-place fallback at step 3 last time) would otherwise be
            //     rotated straight onto .bak — destroying the last known-good backup.
            //     This read is the only place that re-checks the on-disk primary; the
            //     preserved .bak refreshes on the next save once the primary is good
            //     again. One read+parse of a tiny file per save is a cheap insurance.
            if (rotateBackup && dst.exists()) {
                when {
                    parse(dst) == null ->
                        log("config primary invalid — keeping .bak, skipping rotate", null)
                    !rename(dst, bak) ->
                        log("config .bak rotate failed (non-fatal)", null)
                }
            }

            // (3) Publish the new primary. After a rotation dst is gone, so this
            //     renames onto a FREE name — accepted by every filesystem, and it
            //     sidesteps the old "refuse rename onto existing target" problem. The
            //     in-place fallback only fires on an exotic FS that still refused; it
            //     fsyncs (unlike the old copyTo), and .bak still holds a good config,
            //     so even a torn in-place dst stays recoverable.
            if (!rename(tmp, dst)) {
                FileOutputStream(dst).use { fos ->
                    fos.write(payload.toByteArray(Charsets.UTF_8))
                    fos.flush()
                    fos.fd.sync()
                }
                tmp.delete()
            }

            // (4) Make the renames themselves durable (see the doc above).
            syncDir(dir)
            true
        } catch (e: Exception) {
            log("config save failed", e)
            false
        }
    }

    companion object {
        const val CONFIG_FILE = "verso.json"
        /** Cap for [readSetAside]'s copy — the same 1 MiB a restore reads at most. */
        const val MAX_SET_ASIDE_COPY = 1024 * 1024
    }
}
