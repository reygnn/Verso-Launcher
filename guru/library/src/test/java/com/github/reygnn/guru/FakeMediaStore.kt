package com.github.reygnn.guru

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import org.robolectric.Robolectric
import java.io.File
import java.io.FileNotFoundException

/**
 * MediaStore for Robolectric, which has no MediaProvider: rows in memory, their bytes in real
 * files. Inserts are numbered from 1; [refuse] turns those down (null), [throwOn] throws on them.
 * [failWrite] refuses every stream, [failWriteOn] those of the inserts named, [refuseUpdate] publishes nothing (0 rows), [throwOnDelete]
 * makes a delete throw, [throwOnQuery] a query. A query answers MediaStore's way for Guru's:
 * published rows only unless MATCH_INCLUDE asks for pending ones too, RELATIVE_PATH with its
 * trailing slash, filtered by path and owner. [pending] seeds a save that died halfway.
 */
class FakeMediaStore : ContentProvider() {

    /** [inserted]: the values as inserted; [values]: as they are now; [sizeAtPublish]: the file's bytes when IS_PENDING went 0. */
    class Row(val collection: Uri, val uri: Uri, val inserted: ContentValues, val file: File, val owner: String) {
        val values = ContentValues(inserted)
        var sizeAtPublish: Long? = null
        val id: Long get() = uri.lastPathSegment!!.toLong()
    }

    val rows = mutableListOf<Row>()
    var refuse = emptySet<Int>()
    var throwOn = emptySet<Int>()
    var failWrite = false
    var failWriteOn = emptySet<Long>()
    var refuseUpdate = false
    var throwOnDelete = false
    var throwOnQuery = false
    private var inserts = 0

    /** A save that died halfway: a hidden row, its file partly written. */
    fun pending(path: String, owner: String = context!!.packageName): Row =
        published(path, owner).also { it.values.put(MediaStore.MediaColumns.IS_PENDING, 1) }

    /**
     * A published file someone else may own, as an earlier install or the user leaves it; by
     * default under a name Guru gives.
     */
    fun published(path: String, owner: String = context!!.packageName, name: String? = null): Row {
        val uri = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val row = Row(uri, uri.buildUpon().appendPath((++inserts).toString()).build(), ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name ?: "Old-1.0-2026-10-01_12-00-00 ($inserts).json")
            put(MediaStore.MediaColumns.RELATIVE_PATH, path)
            put(MediaStore.MediaColumns.IS_PENDING, 0)
        }, File(context!!.cacheDir, "media-$inserts"), owner)
        rows += row
        return row
    }

    override fun onCreate() = true

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        inserts++
        if (inserts in throwOn) throw IllegalStateException("insert $inserts thrown")
        if (inserts in refuse) return null
        val row = Row(uri, uri.buildUpon().appendPath(inserts.toString()).build(), ContentValues(values), File(context!!.cacheDir, "media-$inserts"),
            context!!.packageName)
        rows += row
        return row.uri
    }

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
        val row = rows.find { it.uri == uri }
        if (row == null || refuseUpdate) return 0
        if (values?.getAsInteger(MediaStore.MediaColumns.IS_PENDING) == 0) row.sizeAtPublish = row.file.length()
        row.values.putAll(values)
        return 1
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        if (throwOnDelete) throw IllegalStateException("delete thrown")
        return if (rows.removeIf { it.uri == uri }) 1 else 0
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val row = rows.find { it.uri == uri }
        if (row == null || failWrite || row.id in failWriteOn) throw FileNotFoundException("no stream for $uri")
        return ParcelFileDescriptor.open(row.file, ParcelFileDescriptor.parseMode(mode) or ParcelFileDescriptor.MODE_CREATE)
    }

    override fun query(uri: Uri, projection: Array<out String>?, queryArgs: Bundle?, cancellationSignal: CancellationSignal?): Cursor {
        if (throwOnQuery) throw IllegalStateException("query thrown")
        val args = queryArgs?.getStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS).orEmpty()
        val (path, owner) = args.getOrNull(0) to args.getOrNull(1)
        // Only the selection Guru means; a wrong one would pass here and fail on a device.
        val expected = "${MediaStore.MediaColumns.RELATIVE_PATH}=?" +
            if (owner != null) " AND ${MediaStore.MediaColumns.OWNER_PACKAGE_NAME}=?" else ""
        val selection = queryArgs?.getString(ContentResolver.QUERY_ARG_SQL_SELECTION)
        require(selection == expected) { "unexpected selection: $selection" }
        // As MediaStore: pending rows only when asked for, even for their owner.
        val withPending = queryArgs?.getInt(MediaStore.QUERY_ARG_MATCH_PENDING) == MediaStore.MATCH_INCLUDE
        val columns = requireNotNull(projection) { "Guru names its columns" }
        val cursor = MatrixCursor(columns)
        rows.filter { row ->
            (withPending || row.values.getAsInteger(MediaStore.MediaColumns.IS_PENDING) == 0) &&
                row.values.getAsString(MediaStore.MediaColumns.RELATIVE_PATH).trimEnd('/') + "/" == path &&
                (owner == null || row.owner == owner)
        }.forEach { row ->
            cursor.addRow(columns.map { if (it == MediaStore.MediaColumns._ID) row.id else row.values.get(it) }.toTypedArray())
        }
        return cursor
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor = error("Guru queries with a Bundle")

    override fun getType(uri: Uri): String? = null

    companion object {
        fun install(): FakeMediaStore = Robolectric.setupContentProvider(FakeMediaStore::class.java, MediaStore.AUTHORITY)
    }
}
