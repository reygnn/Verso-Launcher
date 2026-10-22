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
 * The backups in Download/[subDir] via MediaStore; the naming rules are [BackupFiles]. Never
 * throws — [log] is its error channel. [beforePublish] is a test seam to fail between the
 * complete temp and its publish.
 */
internal class BackupStore(
    private val resolver: ContentResolver,
    private val subDir: String,
    private val log: (String, Exception?) -> Unit,
    private val beforePublish: () -> Unit = {},
    private val now: () -> LocalDateTime = LocalDateTime::now,
    private val owner: String? = null,
) {

    private val relativePath get() = "${Environment.DIRECTORY_DOWNLOADS}/$subDir"

    /**
     * Returns the published name (MediaStore may add " (n)"), or null. Hard rule 6: a pending
     * temp is written + fsynced, published by one rename-and-unpend update, and only then are
     * the old copies swept; a crash leaves the temp for the next sweep. [sweep] false: for a
     * restore's undo point, whose predecessor may be the very file being restored.
     */
    fun write(base: String, payload: String, sweep: Boolean = true): String? {
        var tmp: Uri? = null
        val wanted = BackupFiles.publishedName(base, now())
        // hard rule 2: a failed backup is a message (on a val: detekt ignores it behind `return`)
        val name = @Suppress("TooGenericExceptionCaught") try {
            tmp = resolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, BackupFiles.tempName(base))
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                },
            ) ?: throw IOException("MediaStore refused a temp entry for $base")
            // fsync before publishing: the rename must not reach disk ahead of the bytes.
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
            // Zero rows leaves the temp pending, i.e. due for MediaStore's pending-expiry sweep.
            if (updated != 1) throw IOException("publish of $base updated $updated rows")
            val published = tmp
            tmp = null  // published: the catch must not delete it
            val id = ContentUris.parseId(published)
            val own = listOwn().orEmpty()
            if (sweep) remove(BackupFiles.replacedBy(base, id, own))
            own.firstOrNull { it.id == id }?.name ?: wanted
        } catch (e: Exception) {
            log("backup of $base to Downloads failed", e)
            tmp?.let { runCatching { resolver.delete(it, null, null) } }
            null
        }
        return name
    }

    /** [write]'s deferred sweep: remove what the newest backup of [base] replaces. */
    fun sweep(base: String) {
        val own = listOwn() ?: return
        val newest = BackupFiles.latestToRestore(base, own) ?: return
        remove(BackupFiles.replacedBy(base, newest.id, own))
    }

    private fun remove(rows: List<BackupFiles.Entry>) {
        for (old in rows) {
            // hard rule 2: the new backup stands
            @Suppress("TooGenericExceptionCaught") try {
                resolver.delete(uriOf(old.id), null, null)
            } catch (e: Exception) {
                log("backup: could not remove the replaced ${old.name}", e)
            }
        }
    }

    /** Rows of an earlier install are orphaned by the uninstall and invisible here; "~restore-saf" reaches them. */
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

    /** Null if the query failed. MATCH_INCLUDE: MediaStore hides pending rows even from their
     *  owner, and a stranded temp is what the sweep and the restore fallback need to see. */
    // hard rule 2: unlistable = null
    @Suppress("TooGenericExceptionCaught")
    private fun listOwn(): List<BackupFiles.Entry>? = try {
        val query = Bundle().apply {
            putString(
                ContentResolver.QUERY_ARG_SQL_SELECTION,
                "${MediaStore.MediaColumns.RELATIVE_PATH}=?" +
                    if (owner != null) " AND ${MediaStore.MediaColumns.OWNER_PACKAGE_NAME}=?" else "",
            )
            // RELATIVE_PATH is stored with a trailing slash. Without a storage permission only own
            // rows come back anyway; the owner check holds should that change.
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, listOfNotNull("$relativePath/", owner).toTypedArray())
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

    // hard rule 2: an unreadable file is a message
    @Suppress("TooGenericExceptionCaught")
    fun readText(uri: Uri): String? = try {
        resolver.openInputStream(uri)?.use { readCapped(it) }
    } catch (e: Exception) {
        log("restore: cannot read $uri", e)
        null
    }

    companion object {
        /** A real verso.json is a few KB; anything this big is not a config. */
        private const val MAX_RESTORE_BYTES = 1024 * 1024

        /** Null when [input] holds more than [maxBytes] (one byte past the cap is read to tell). */
        internal fun readCapped(input: InputStream, maxBytes: Int = MAX_RESTORE_BYTES): String? {
            val bytes = input.readNBytes(maxBytes + 1)
            return if (bytes.size > maxBytes) null else bytes.toString(Charsets.UTF_8)
        }
    }
}
