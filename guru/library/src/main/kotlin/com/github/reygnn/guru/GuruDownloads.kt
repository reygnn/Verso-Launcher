package com.github.reygnn.guru

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import java.io.FileOutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The reports as files in Download/[subDir], through MediaStore: no permission, no manifest
 * entry. One `.json` per report — its ACRA JSON, unchanged. Once saved, the file is the user's
 * and Guru removes its own copy (hard rule 7); of the files Guru made there, it keeps the
 * newest [keep]. Never throws: [log] is the error channel. [owner]: only rows of this package
 * are ever listed or removed — the app's own package; a test may leave it out.
 */
internal class GuruDownloads(
    private val resolver: ContentResolver,
    private val log: (String, Throwable?) -> Unit,
    private val owner: String?,
    private val subDir: String = SUB_DIR,
    private val keep: Int = KEEP,
) {

    // Environment.DIRECTORY_DOWNLOADS, spelled out: a constant, and readable on the plain JVM.
    private val relativePath get() = "Download/$subDir"

    /**
     * Saves [json] as [base].json; the file shows only once complete and synced (IS_PENDING).
     * Null when it could not be saved — then the unfinished file is removed, or, if even that
     * fails, left hidden for MediaStore to sweep.
     */
    // anything the provider throws means "not saved", and the report stays
    @Suppress("TooGenericExceptionCaught")
    fun save(base: String, json: String): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$base.json")
            put(MediaStore.MediaColumns.MIME_TYPE, MIME)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        var uri: Uri? = null
        try {
            uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            if (uri != null && publish(uri, json.toByteArray(Charsets.UTF_8))) return uri
            log("saving $base.json refused", null)
        } catch (e: Exception) {
            log("saving $base.json failed", e)
        }
        uri?.let { remove(it, "an unfinished $base.json") }
        return null
    }

    /**
     * The bytes into the pending file, synced, then shown; false when the provider says no. Synced
     * first because Guru's own copy goes right after: a power cut must not leave an empty file and
     * no report (hard rule 4).
     */
    private fun publish(uri: Uri, bytes: ByteArray): Boolean {
        val file = resolver.openFileDescriptor(uri, "w") ?: return false
        file.use {
            FileOutputStream(it.fileDescriptor).apply {
                write(bytes)
                flush()
            }
            it.fileDescriptor.sync()
        }
        val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        return resolver.update(uri, done, null, null) == 1
    }

    /**
     * Keeps the newest [keep] of Guru's own files in the folder, by MediaStore id (ids only grow).
     * A crash loop fills the folder no faster than that. Files the user put there, or an earlier
     * install's, are not ours and never listed.
     *
     * A hidden, still pending file of ours is a save that died halfway — its report is still in
     * Guru's store and saved anew — so it goes too. Safe: every save runs on the one io, before
     * this, so none is in flight now.
     */
    fun prune() {
        val own = listOwn() ?: return
        own.filter { it.pending }.forEach { remove(uriOf(it.id), "an unfinished report file") }
        own.filter { !it.pending && OWN_NAME.matches(it.name) }.map { it.id }.sortedDescending().drop(keep).forEach { id ->
            remove(uriOf(id), "an old report file")
        }
    }

    private class Row(val id: Long, val pending: Boolean, val name: String)

    private fun uriOf(id: Long) = ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id)

    /** The rows of ours in the folder, pending ones included; null if the query failed. */
    // unlistable = nothing pruned this time
    @Suppress("TooGenericExceptionCaught")
    private fun listOwn(): List<Row>? = try {
        val query = Bundle().apply {
            putString(
                ContentResolver.QUERY_ARG_SQL_SELECTION,
                "${MediaStore.MediaColumns.RELATIVE_PATH}=?" +
                    if (owner != null) " AND ${MediaStore.MediaColumns.OWNER_PACKAGE_NAME}=?" else "",
            )
            // RELATIVE_PATH is stored with a trailing slash. Without a storage permission only own
            // rows come back anyway; the owner check holds should that change.
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, listOfNotNull("$relativePath/", owner).toTypedArray())
            // MediaStore hides pending rows even from their owner.
            putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
        }
        val columns = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.IS_PENDING, MediaStore.MediaColumns.DISPLAY_NAME)
        resolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, columns, query, null)
            ?.use { c -> buildList { while (c.moveToNext()) add(Row(c.getLong(0), c.getInt(1) != 0, c.getString(2).orEmpty())) } }
            .orEmpty()
    } catch (e: Exception) {
        log("listing $relativePath failed", e)
        null
    }

    // best effort: what stays is MediaStore's to sweep (pending) or the user's (published)
    @Suppress("TooGenericExceptionCaught")
    private fun remove(uri: Uri, what: String) {
        try {
            resolver.delete(uri, null, null)
        } catch (e: Exception) {
            log("removing $what failed", e)
        }
    }

    companion object {
        const val SUB_DIR = "Guru"
        const val KEEP = 32
        const val MIME = "application/json"
        const val MAX_LABEL = 40
        const val MAX_VERSION = 24

        /**
         * A name [baseName] gives, with MediaStore's " (1)" for a name taken: ends in the crash
         * time and `.json`. The label may differ from today's (a language change), so only the end
         * is checked.
         */
        val OWN_NAME = Regex("""(.+-)?\d{4}-\d{2}-\d{2}_\d{2}-\d{2}-\d{2}( \(\d+\))?\.json""")

        // Digits stay ASCII in any locale: DateTimeFormatter's default DecimalStyle is STANDARD.
        private val STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss", Locale.ROOT)

        /**
         * App name, version, crash time in [zone]: "Verso_Launcher-0.4.0-2026-10-24_14-03-12". Whatever is
         * not a letter, digit, dot or dash becomes `_`; an empty part is left out. A second report
         * of the same second gets MediaStore's " (1)".
         *
         * Label and version are cut to [MAX_LABEL] and [MAX_VERSION] chars: a file name holds 255
         * bytes, a char here up to 3 in UTF-8 (only BMP chars survive [safe]), and a name too long
         * would fail every save of that report.
         */
        fun baseName(label: String, report: GuruReport, zone: ZoneId): String =
            listOf(
                safe(label).take(MAX_LABEL),
                safe(report.env.versionName).take(MAX_VERSION),
                STAMP.format(Instant.ofEpochMilli(report.crashedAt).atZone(zone)),
            ).filter { it.isNotEmpty() }.joinToString("-")

        // Per UTF-16 unit: a surrogate is no letter, so a pair becomes "__" and nothing half survives.
        private fun safe(part: String): String =
            part.trim().map { if (it.isLetterOrDigit() || it == '.' || it == '-') it else '_' }.joinToString("")
    }
}
