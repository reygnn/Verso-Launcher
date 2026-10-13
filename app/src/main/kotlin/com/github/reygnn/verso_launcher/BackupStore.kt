package com.github.reygnn.verso_launcher

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.system.Os
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.time.LocalDateTime

/**
 * The shared-storage backup layer: publishing the config into Download/[subDir] and reading
 * it back. [ConfigStore] owns verso.json in filesDir; this owns the off-device copy that
 * "~backup"/"~restore" use and that survives an uninstall.
 *
 * Lifted out of MainActivity to mirror the ConfigStore split — the Activity keeps the parts
 * that are inherently Activity-bound (the "~restore-saf" ACTION_OPEN_DOCUMENT dance in
 * pickRestoreFile/onActivityResult, and adoptRestored, which replaces the live cfg). Which
 * rows are a backup, which one a restore reads and which ones a backup replaces is decided
 * by [BackupFiles] (JVM-tested); the MediaStore behaviour itself — its renaming of clashing
 * names, pending rows, ownership — can only be checked on a device, so this class is covered
 * by an instrumented test (BackupStoreInstrumentedTest).
 *
 * Everything Android-specific is injected so the class names no framework singleton:
 *   [resolver]      the app's ContentResolver
 *   [subDir]        the sub-folder of Downloads to write into (e.g. "Verso")
 *   [log]           android.util.Log in the app, a collector in a test — this class never
 *                   throws, so the log IS its error channel
 *   [beforePublish] runs between the complete temp and its publish. A no-op in the app; a
 *                   seam only so the instrumented test can fail (or "crash") exactly there.
 *   [now]           the clock a backup's name is stamped from; fixed in a test.
 */
internal class BackupStore(
    private val resolver: ContentResolver,
    private val subDir: String,
    private val log: (String, Exception?) -> Unit,
    private val beforePublish: () -> Unit = {},
    private val now: () -> LocalDateTime = LocalDateTime::now,
) {

    // MediaStore stores RELATIVE_PATH WITH a trailing slash; the query below must match it.
    private val relativePath get() = "${Environment.DIRECTORY_DOWNLOADS}/$subDir"

    /**
     * Publish [payload] as a backup of [base] (e.g. "verso") in Download/[subDir]. Returns the
     * name it was published under — "<base>-<date>_<time>.json" ([BackupFiles.publishedName]),
     * with a " (n)" if MediaStore had to dodge a taken name — or null if nothing was published.
     * Needs no storage permission: an app may always write into the Downloads collection and
     * rewrite what it wrote itself. Never throws — a failed backup is reported and forgotten,
     * it must not take a HOME app down with it.
     *
     * The invariant: the previous backup is never touched until its replacement is published.
     * It is the only off-device copy, so it must survive every failure and every crash.
     *   1. Insert a PENDING temp row and write + fsync the payload into it. Pending keeps the
     *      half-written file invisible to file managers.
     *   2. Publish: rename the temp to its timestamped name and clear pending in ONE update,
     *      and demand that it changed exactly that one row. The old backup still exists at
     *      this point — which is why a restore picks the newest row by pattern ([BackupFiles]).
     *   3. Only now delete what the new backup replaces: older published copies and stranded
     *      temps. A failure here leaves an extra file, never a missing one.
     * A failure in 1 or 2 deletes only the temp — the old backup is untouched. A crash in 1 or 2
     * leaves the temp, which the next backup sweeps (step 3) and a restore reads if no backup
     * was ever published.
     */
    fun write(base: String, payload: String): String? {
        var tmp: Uri? = null
        val wanted = BackupFiles.publishedName(base, now())
        return try {
            tmp = resolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, BackupFiles.tempName(base))
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                },
            ) ?: throw IOException("MediaStore refused a temp entry for $base")
            // fsync the CONTENTS before publishing, exactly as ConfigStore does: the rename
            // must not be able to reach disk ahead of the bytes it publishes.
            resolver.openFileDescriptor(tmp, "w")?.use { pfd ->
                val out = FileOutputStream(pfd.fileDescriptor)
                out.write(payload.toByteArray(Charsets.UTF_8))
                out.flush()
                Os.fsync(pfd.fileDescriptor)
            } ?: throw IOException("no descriptor for $tmp")
            beforePublish()
            val updated = resolver.update(
                tmp,
                ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, wanted)
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                },
                null,
                null,
            )
            // A publish that touched no row left the temp pending — invisible, and on the clock
            // for MediaStore's pending-expiry sweep. That is a failed backup, not a success.
            if (updated != 1) throw IOException("publish of $base updated $updated rows")
            val published = tmp
            tmp = null  // published: from here on the catch must never delete it
            val id = ContentUris.parseId(published)
            val own = listOwn().orEmpty()
            for (old in BackupFiles.replacedBy(base, id, own)) {
                try {
                    resolver.delete(uriOf(old.id), null, null)
                } catch (e: Exception) {
                    log("backup: could not remove the replaced ${old.name}", e)
                }
            }
            own.firstOrNull { it.id == id }?.name ?: wanted
        } catch (e: Exception) {
            log("backup of $base to Downloads failed", e)
            // Only the unpublished temp — every older backup is still in place.
            tmp?.let { runCatching { resolver.delete(it, null, null) } }
            null
        }
    }

    /**
     * The row a restore of [base] should read (see [BackupFiles.latestToRestore]) — telling
     * "this install has no backup" apart from "the folder could not be read": the two need
     * different advice, and only one of them means "run ~backup". Rows of an earlier install
     * are not ours any more — an uninstall orphans them, and they can be neither seen nor
     * overwritten; "~restore-saf" reaches them.
     */
    fun lookupLatest(base: String): Latest {
        val own = listOwn() ?: return Latest.Failed
        val entry = BackupFiles.latestToRestore(base, own) ?: return Latest.None
        return Latest.Found(uriOf(entry.id))
    }

    sealed interface Latest {
        data class Found(val uri: Uri) : Latest
        data object None : Latest
        data object Failed : Latest
    }

    /**
     * Every row of ours in Download/[subDir], or null if the query failed. Without a storage
     * permission a Downloads query only ever returns the app's own rows, which is exactly the
     * scope wanted. MATCH_INCLUDE, because MediaStore hides IS_PENDING rows from a plain query
     * even from the app that wrote them — and a stranded temp is precisely what the sweep and
     * the restore fallback need to see.
     */
    private fun listOwn(): List<BackupFiles.Entry>? = try {
        val query = Bundle().apply {
            putString(
                ContentResolver.QUERY_ARG_SQL_SELECTION,
                "${MediaStore.MediaColumns.RELATIVE_PATH}=?",
            )
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf("$relativePath/"))
            putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
        }
        resolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME),
            query,
            null,
        )?.use { c ->
            buildList {
                while (c.moveToNext()) add(BackupFiles.Entry(c.getLong(0), c.getString(1).orEmpty()))
            }
        } ?: emptyList()
    } catch (e: Exception) {
        log("listing Download/$subDir failed", e)
        null
    }

    private fun uriOf(id: Long): Uri =
        ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id)

    /** Read a document's whole text, or null if it cannot be read. Shared by both restore
     *  paths ("~restore"'s newest own backup and the SAF picker's uri); never throws. The bound itself
     *  lives in [readCapped] so it is unit-testable without Android. */
    fun readText(uri: Uri): String? = try {
        resolver.openInputStream(uri)?.use { readCapped(it) }
    } catch (e: Exception) {
        log("restore: cannot read $uri", e)
        null
    }

    companion object {
        /** Cap for a restore read. A verso.json is small text (keys, names, tags) — a few KB
         *  even for hundreds of apps; anything larger is not a real config, so refuse it rather
         *  than spend memory and parse time on a hostile or huge file — the write path is
         *  bounded by our own config, only reads are exposed. */
        private const val MAX_RESTORE_BYTES = 1024 * 1024 // 1 MiB

        /**
         * Read at most [maxBytes] from [input] as UTF-8, or null when it holds more. Extracted
         * from [readText] so the bound — the load-bearing part against an OOM on a huge or
         * streaming/pipe restore file — is a pure function of an InputStream, unit-testable on
         * the JVM without Android (the Uri -> stream step in [readText] is trivial glue).
         * Reads one byte past the cap so an over-limit file is detected, not silently truncated.
         * ([maxBytes] is a parameter only so a test can drive the boundary with a tiny cap;
         * production always uses [MAX_RESTORE_BYTES]. readNBytes is API 33+; minSdk 36.)
         */
        internal fun readCapped(input: InputStream, maxBytes: Int = MAX_RESTORE_BYTES): String? {
            val bytes = input.readNBytes(maxBytes + 1)
            return if (bytes.size > maxBytes) null else bytes.toString(Charsets.UTF_8)
        }
    }
}
