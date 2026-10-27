package com.github.reygnn.verso_launcher

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri

/**
 * The user's config file, owned by the process: linking it ("~open") and reading it again whenever
 * the launcher comes to the front. Every hand-back goes to the process and reads the state from
 * Core, so it lands whatever happens to the window that asked. A window is reached only as a
 * [LauncherWindow]; what a window decides stays in MainActivity: refusing a command up front, the
 * picker, the editor, "~restart".
 *
 * Verso never writes the file (hard rule 4). The document is read on a thread of its own, never on
 * the IO thread: a provider (a cloud folder, a USB stick) may block for good, and must hold neither
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
     * The newest "~open" answer: the user's last choice. One that lands after a newer one (a slow
     * provider) is dropped without a word, so it cannot undo that choice.
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
