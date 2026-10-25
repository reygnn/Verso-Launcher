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
 * The backups in Download/[subDir] via MediaStore, on MediaStore's own terms (hard rule 6): every
 * backup is a new file, and Verso never deletes one. The naming rules are [BackupFiles]. Never
 * throws — [log] is its error channel. [beforePublish] is a test seam to fail between the
 * complete pending file and its publish.
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
     * Returns the published name (MediaStore settles it at publish, a taken one gets " (n)"), or
     * null. A pending row is written and fsynced, then made visible — never seen half written. A failure
     * removes only that pending row; one a crash leaves behind stays hidden until MediaStore
     * expires it.
     */
    fun write(base: String, payload: String): String? {
        var pending: Uri? = null
        val wanted = BackupFiles.publishedName(base, now())
        // hard rule 2: a failed backup is a message (on a val: detekt ignores it behind `return`)
        val published = @Suppress("TooGenericExceptionCaught") try {
            val row = resolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, wanted)
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                },
            ) ?: throw IOException("MediaStore refused an entry for $base")
            pending = row
            // fsync before publishing: the file must not show ahead of its bytes.
            resolver.openFileDescriptor(row, "w")?.use { pfd ->
                val out = FileOutputStream(pfd.fileDescriptor)
                out.write(payload.toByteArray(Charsets.UTF_8))
                out.flush()
                Os.fsync(pfd.fileDescriptor)
            } ?: throw IOException("no descriptor for $row")
            beforePublish()
            val updated = resolver.update(
                row,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null,
            )
            if (updated != 1) throw IOException("publish of $base updated $updated rows")
            pending = null  // published: the catch must not delete it
            row
        } catch (e: Exception) {
            log("backup of $base to Downloads failed", e)
            pending?.let { runCatching { resolver.delete(it, null, null) } }
            null
        }
        return published?.let { nameOf(it) ?: wanted }
    }

    /** The name MediaStore gave [uri] (a taken one gets " (n)"); null if it cannot tell. */
    // hard rule 2: the backup stands, only its name in the message may be the one asked for
    @Suppress("TooGenericExceptionCaught")
    private fun nameOf(uri: Uri): String? = try {
        resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    } catch (e: Exception) {
        log("backup: cannot read the name of $uri", e)
        null
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

    /** Null if the query failed. Pending rows — a backup not yet complete — are not listed. */
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
