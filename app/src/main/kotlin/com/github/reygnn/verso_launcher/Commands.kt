package com.github.reygnn.verso_launcher

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.SyncFailedException

/**
 * The user's config file, owned by the process: linking it ("~open"), writing a new one ("~new")
 * and reading it again whenever the launcher comes to the front. Every hand-back goes to the
 * process and reads the state from Core, so it lands whatever happens to the window that asked. A
 * window is reached only as a [LauncherWindow]; what a window decides stays in MainActivity:
 * refusing a command up front, the pickers, the editor, "~restart".
 *
 * Verso never writes the file over (hard rule 4): "~new" writes a document the picker has just
 * made, and only that once. The document is read and written on a thread of its own, never on the
 * IO thread: a provider (a cloud folder, a USB stick) may block for good, and must hold neither
 * the IO thread nor an activity.
 *
 * Main thread only, but for those threads.
 */
internal class Commands(private val app: VersoApp) {

    private val core get() = app.core
    private val results get() = app.results

    /**
     * The file a read is under way for; one more asked for meanwhile runs after it. Per file: a
     * provider that never hands one over holds only the reads of that one, not of a file linked
     * since.
     */
    private var reading: Uri? = null
    private var readAgain = false

    /** The last read of the linked file failed: said once, until one works again. */
    private var failing = false

    /**
     * The newest "~open" or "~new" answer: the user's last choice. One that lands after a newer
     * one (a slow provider) is dropped without a word, so it cannot undo that choice.
     */
    private var picks = 0
    private var lastPicked: Uri? = null

    /** Reads the linked file (every onStart): a changed text replaces the config at once. */
    fun readLinked() {
        val uri = core.link ?: return
        if (reading == uri) {
            readAgain = true
            return
        }
        reading = uri
        read(uri) { text -> readDone(uri, text) }
    }

    private fun readDone(uri: Uri, text: String?) {
        if (reading == uri) reading = null
        // A link replaced meanwhile: this text is the old file's, and the new one is read below.
        if (uri == core.link) {
            if (text == null) {
                if (!failing) results.report(app.getString(R.string.toast_config_file_unreadable))
                failing = true
            } else {
                failing = false
                if (text != core.linkedText) adopt(text, ConfigText.read(text))
            }
        }
        if (readAgain) {
            readAgain = false
            readLinked()
        }
    }

    /** "~open" after the picker answered: read first — a file that is no config is not linked. */
    fun link(uri: Uri) {
        val pick = picked(uri)
        if (!keepAccess(uri)) return results.report(app.getString(R.string.toast_link_failed))
        read(uri) { text ->
            if (superseded(pick, uri)) return@read
            if (text == null) {
                dropAccess(uri)
                return@read results.report(app.getString(R.string.toast_open_unreadable))
            }
            val parsed = ConfigText.read(text)
            if (parsed.isNoConfig) {
                dropAccess(uri)
                return@read results.report(app.getString(R.string.toast_not_a_config))
            }
            relinked(uri)
            results.report(app.getString(R.string.toast_linked))
            adopt(text, parsed)
        }
    }

    private fun picked(uri: Uri): Int {
        lastPicked = uri
        return ++picks
    }

    /** A newer pick came meanwhile: this one ends here, its grant with it unless still in use. */
    private fun superseded(pick: Int, uri: Uri): Boolean {
        if (pick == picks) return false
        if (uri != lastPicked) dropAccess(uri)
        return true
    }

    /** The grant of a file not linked; the linked one keeps its own. */
    private fun dropAccess(uri: Uri) {
        if (uri != core.link) release(uri)
    }

    /**
     * "~new" after the picker answered with a new document: the config in use (the defaults, or
     * an earlier version's verso.json) written into it, which is linked from then on. Hard rule 4:
     * only into a document that is still empty — the picker hands over an existing file too, when
     * the user picks it and agrees to replace it, and that may be the linked one.
     */
    fun create(uri: Uri) {
        val pick = picked(uri)
        if (uri == core.link) return results.report(app.getString(R.string.toast_new_not_new))
        // At once, while the picker's grant is fresh; the write below needs only that grant.
        val kept = keepAccess(uri)
        val state = core.state
        val payload = ConfigText.format(state.config, state.apps, app.getString(R.string.config_header).lines())
        val resolver = app.contentResolver
        Thread(Guard.wrap("config write", ::logWarning) {
            var outcome = Written.FAILED
            try {
                outcome = writeNew(resolver, uri, payload)
            } finally {
                app.onMain { created(pick, uri, outcome, kept, payload) }
            }
        }, "verso-config-write").start()
    }

    private fun created(pick: Int, uri: Uri, outcome: Written, kept: Boolean, payload: String) {
        if (superseded(pick, uri)) return
        if (outcome != Written.DONE) {
            if (kept) dropAccess(uri)
            val word = if (outcome == Written.NOT_NEW) R.string.toast_new_not_new else R.string.toast_new_failed
            return results.report(app.getString(word))
        }
        // Written, but not to be read again: the file is the user's, not linked.
        if (!kept) return results.report(app.getString(R.string.toast_link_failed))
        relinked(uri)
        results.report(app.getString(R.string.toast_new_ok))
        adopt(payload, ConfigText.read(payload))
    }

    private enum class Written { DONE, FAILED, NOT_NEW }

    private fun relinked(uri: Uri) {
        val old = core.link
        core.relink(uri)
        if (old != null && old != uri) release(old)
        failing = false
    }

    private fun adopt(text: String, parsed: ConfigText) {
        core.adoptLinked(text, parsed)
        if (parsed.badLines.isNotEmpty()) {
            val lines = parsed.badLines.take(SKIPPED_SHOWN).joinToString(", ") +
                if (parsed.badLines.size > SKIPPED_SHOWN) ", …" else ""
            results.report(app.getString(R.string.toast_config_lines_skipped, lines))
        }
    }

    /** [uri]'s text on a thread of its own, handed back on main ([then]): null if it cannot be read. */
    private fun read(uri: Uri, then: (String?) -> Unit) {
        val resolver = app.contentResolver
        Thread(Guard.wrap("config read", ::logWarning) {
            var text: String? = null
            try {
                text = readDocument(resolver, uri)
            } finally {
                // Even after an Error: a read that never handed back would hold every later one.
                app.onMain { then(text) }
            }
        }, "verso-config-read").start()
    }

    /** The picker's grant kept across restarts: read and write (the editor gets both), else read. */
    private fun keepAccess(uri: Uri): Boolean {
        val resolver = app.contentResolver
        val modes = listOf(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            Intent.FLAG_GRANT_READ_URI_PERMISSION,
        )
        for (mode in modes) {
            try {
                resolver.takePersistableUriPermission(uri, mode)
                return true
            } catch (e: SecurityException) {
                logWarning("config: no persistable grant ($mode) for $uri", e)
            }
        }
        return false
    }

    /** A grant no longer needed: a file not linked, or the one a new link replaced. */
    private fun release(uri: Uri) {
        val resolver = app.contentResolver
        val held = resolver.persistedUriPermissions.firstOrNull { it.uri == uri } ?: return
        val mode = (if (held.isReadPermission) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or
            (if (held.isWritePermission) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
        try {
            resolver.releasePersistableUriPermission(uri, mode)
        } catch (e: SecurityException) {
            logWarning("config: could not release $uri", e)
        }
    }

    private companion object {
        /** How many skipped line numbers a toast names. */
        const val SKIPPED_SHOWN = 5

        /**
         * The new file written, if the document is empty: one with content (or of a size the
         * provider will not tell, so none is truncated or deleted blind) is never written over or
         * deleted. A write that fails on an empty document deletes it where the provider allows,
         * so no half file is left to be linked later.
         */
        private fun writeNew(resolver: ContentResolver, uri: Uri, payload: String): Written {
            val size = sizeOf(resolver, uri)
            if (size != null && size > 0) return Written.NOT_NEW
            val empty = size == 0L
            if (writeDocument(resolver, uri, payload, empty)) return Written.DONE
            if (empty) deleteDocument(resolver, uri)
            return Written.FAILED
        }

        /** The document's size in bytes, or null when the provider does not say. */
        private fun sizeOf(resolver: ContentResolver, uri: Uri): Long? =
            // hard rule 2: an unknown size is a null, the caller then writes over nothing
            @Suppress("TooGenericExceptionCaught") try {
                resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_SIZE), null, null, null)?.use { c ->
                    if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
                }
            } catch (e: Exception) {
                logWarning("config: no size for $uri", e)
                null
            }

        /** False if the document could not be written whole (logged). */
        private fun writeDocument(resolver: ContentResolver, uri: Uri, payload: String, empty: Boolean): Boolean {
            // hard rule 2: a failed write is a message
            val written = @Suppress("TooGenericExceptionCaught") try {
                openTruncated(resolver, uri, empty)?.use { out ->
                    out.write(payload.toByteArray(Charsets.UTF_8))
                    out.flush()
                    sync(out)
                    true
                } ?: false.also { logWarning("config: no stream for $uri", null) }
            } catch (e: Exception) {
                logWarning("config: cannot write $uri", e)
                false
            }
            return written
        }

        /**
         * "wt": since Android 10 "w" no longer truncates. A provider that refuses "t" gets "w", but
         * only for a document known to be empty: on one of unknown size "w" could leave an old
         * tail behind the new text.
         */
        private fun openTruncated(resolver: ContentResolver, uri: Uri, empty: Boolean): OutputStream? = try {
            resolver.openOutputStream(uri, "wt")
        } catch (e: IllegalArgumentException) {
            if (!empty) throw e
            logWarning("config: $uri takes no \"wt\", writing with \"w\"", e)
            resolver.openOutputStream(uri, "w")
        } catch (e: FileNotFoundException) {
            if (!empty) throw e
            logWarning("config: $uri takes no \"wt\", writing with \"w\"", e)
            resolver.openOutputStream(uri, "w")
        }

        /** On disk before "created" is said, where the provider is a local file; a cloud one syncs itself. */
        private fun sync(out: OutputStream) {
            val fd = (out as? FileOutputStream)?.fd ?: return
            try {
                fd.sync()
            } catch (e: SyncFailedException) {
                // The bytes are written; only the hardening failed.
                logWarning("config: sync failed", e)
            }
        }

        private fun deleteDocument(resolver: ContentResolver, uri: Uri) {
            // hard rule 2: tidying up after a failure must not fail the message
            @Suppress("TooGenericExceptionCaught") try {
                if (!DocumentsContract.deleteDocument(resolver, uri)) logWarning("config: $uri not deleted", null)
            } catch (e: Exception) {
                logWarning("config: cannot delete the failed $uri", e)
            }
        }

        /** Null if it cannot be read or is beyond any config's size; logged. */
        fun readDocument(resolver: ContentResolver, uri: Uri): String? =
            // hard rule 2: an unreadable file is a message
            @Suppress("TooGenericExceptionCaught") try {
                resolver.openInputStream(uri)?.use { ConfigJson.readCapped(it) }
            } catch (e: Exception) {
                logWarning("config: cannot read $uri", e)
                null
            }
    }
}
