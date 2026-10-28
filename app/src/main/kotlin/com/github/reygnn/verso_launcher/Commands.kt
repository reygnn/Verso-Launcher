package com.github.reygnn.verso_launcher

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.SystemClock

/**
 * The user's config file, owned by the process: linking it with "~open", and reading it again
 * whenever the launcher comes to the front. Verso never writes the file (hard rule 4). What a
 * window decides stays in [MainActivity]: refusing a command up front, the picker, the editor and
 * "~restart".
 *
 * Thread: main only, except the reading threads that [read] starts.
 * Invariant: every hand-back goes to the process and reads the state from [Core], so it lands
 *   whatever happened to the window that asked. A window is reached only as a [LauncherWindow]
 *   (hard rule 16).
 * Why: the document is read and parsed on a thread of its own, never on the IO thread. A provider
 *   such as a cloud folder or a USB stick may block for good, and must then hold neither the IO
 *   thread nor an activity (hard rule 3).
 */
internal class Commands(private val app: VersoApp) {

    private val core get() = app.core
    private val results get() = app.results

    /**
     * The read of the linked file under way, or null. A read asked for meanwhile runs after it
     * ([readAgain]).
     *
     * Invariant: its file is always the link. [relinked], the only place the link changes, drops
     *   it; otherwise only its own hand-back ([readDone]) ends it.
     */
    private var current: Read? = null
    private var readAgain = false

    /** A read of the linked file: its number (from [reads]) and when it began ([SystemClock.uptimeMillis]). */
    private class Read(val number: Int, val since: Long)

    /**
     * Every read gets a number as it starts, "~open"'s too. [adopted] is the number of the read
     * whose text the config has now.
     *
     * Invariant: of one file, a text never replaces one from a read that started later. A read
     *   that hung and comes back at last brings an older text.
     * Why: a file newly linked starts over at 0 ([relinked]). It is the user's last choice (hard
     *   rule 15), whatever was read of the old file meanwhile.
     */
    private var reads = 0
    private var adopted = 0

    /**
     * The last read of the linked file failed, or has run for [READ_STUCK_MS]. That is said once,
     * and again only after a read has worked.
     */
    private var failing = false

    /**
     * Every "~open" answer gets a number ([picks]); [lastPicked] is the file of the newest one.
     *
     * Invariant: the user's last choice stands. An answer that lands after a newer one, from a
     *   slow provider, is dropped without a word ([superseded]).
     */
    private var picks = 0
    private var lastPicked: Uri? = null

    /** Reads the linked file again, at every start (hard rule 5). A text that changed replaces the config at once. */
    fun readLinked() {
        val uri = core.link ?: return
        if (current != null) {
            readAgain = true
            checkStuck()
            return
        }
        val since = SystemClock.uptimeMillis()
        // Only while in front: a word queued now could outlive the read it is about. While the
        // launcher is away, the next start says it instead (the branch above), if the read still
        // hangs then.
        app.onMainAfter(READ_STUCK_MS) { if (app.instance?.inFront == true) checkStuck() }
        // Only a text that changed is parsed: most starts find the file as it was. Whether the
        // config still has the text known now is checked when the read hands back (readDone).
        val known = core.linkedText
        val parse = { text: String -> if (text == known) Same(text) else document(text) }
        // Set before its hand-back can run, because the hand-back is posted to this main thread.
        current = Read(read(uri, parse, ::readDone), since)
    }

    /**
     * Says once that the read under way hangs, if it has run for [READ_STUCK_MS]. The read is left
     * to finish.
     *
     * Why: a second read beside it is never started (hard rule 15). A provider that blocks for good
     *   would take another thread with every return home. Only "~open" of the file, the user's own
     *   step, reads it afresh and drops the hung read ([relinked]).
     */
    private fun checkStuck() {
        val running = current ?: return
        if (failing || SystemClock.uptimeMillis() - running.since < READ_STUCK_MS) return
        failing = true
        results.report(app.getString(R.string.toast_config_file_stuck))
    }

    private fun readDone(number: Int, outcome: Outcome?) {
        // A read that relinked dropped: the file has been read since, so its result is ignored.
        if (current?.number != number) return
        current = null
        when (outcome) {
            null -> {
                if (!failing) results.report(app.getString(R.string.toast_config_file_unreadable))
                failing = true
            }
            is Same -> {
                failing = false
                // The config took another text while this read ran, so the file's text was compared
                // with an old one: it is read again, against the config's text now.
                if (outcome.text != core.linkedText) readAgain = true
            }
            is Document -> {
                failing = false
                if (outcome.text != core.linkedText) adopt(number, outcome)
            }
        }
        if (readAgain) {
            readAgain = false
            readLinked()
        }
    }

    /** "~open", after the picker answered. The file is read first: a file that is no config is not linked. */
    fun link(uri: Uri) {
        val pick = picked(uri)
        if (!keepAccess(uri)) return results.report(app.getString(R.string.toast_link_failed))
        read(uri, ::document) { number, document ->
            if (superseded(pick, uri)) return@read
            if (document == null) {
                dropAccess(uri)
                return@read results.report(app.getString(R.string.toast_open_unreadable))
            }
            if (document.parsed.isNoConfig) {
                dropAccess(uri)
                return@read results.report(app.getString(R.string.toast_not_a_config))
            }
            relinked(uri)
            results.report(app.getString(R.string.toast_linked))
            adopt(number, document)
        }
    }

    private fun picked(uri: Uri): Int {
        lastPicked = uri
        return ++picks
    }

    /**
     * True if a newer pick came meanwhile. This pick then ends here, and so does its grant, unless
     * the file is still in use as the newest pick or as the link.
     */
    private fun superseded(pick: Int, uri: Uri): Boolean {
        if (pick == picks) return false
        if (uri != lastPicked) dropAccess(uri)
        return true
    }

    /** Releases the grant of [uri], unless [uri] is the link: the link keeps its grant. */
    private fun dropAccess(uri: Uri) {
        if (uri != core.link) release(uri)
    }

    /**
     * [uri] is the link now, and was read just now. A read still under way is dropped, even one of
     * this very file that hangs, so the next start reads afresh. For another file, [adopted] starts
     * over.
     */
    private fun relinked(uri: Uri) {
        val old = core.link
        core.relink(uri)
        if (old != uri) {
            if (old != null) release(old)
            adopted = 0
        }
        failing = false
        current = null
        readAgain = false
    }

    /** The config takes [document]'s text, unless a read of this file that started later gave one ([adopted]). */
    private fun adopt(number: Int, document: Document) {
        if (number < adopted) return
        adopted = number
        val parsed = document.parsed
        core.adoptLinked(document.text, parsed)
        if (parsed.badLines.isNotEmpty()) {
            val lines = parsed.badLines.take(SKIPPED_SHOWN).joinToString(", ") +
                if (parsed.badLines.size > SKIPPED_SHOWN) ", …" else ""
            results.report(app.getString(R.string.toast_config_lines_skipped, lines))
        }
    }

    /** What a read of the linked file brings back: the text known when it began ([Same]), or a new [Document]. */
    private sealed interface Outcome

    /**
     * The file's [text] is the one the config had when the read began, so it was not parsed.
     *
     * Why: the config may have taken another text meanwhile. [readDone] compares [text] with the
     *   config's text then, and reads the file again if they differ, so no writer of
     *   [Core.linkedText] needs to know about this shortcut.
     */
    private class Same(val text: String) : Outcome

    /** A document's text and what it says; both are made on the reading thread. */
    private class Document(val text: String, val parsed: ConfigText) : Outcome

    /**
     * Reads [uri] on a thread of its own and makes the result there with [parse], then hands it
     * back on main ([then]) with the read's number, which this also returns. The result is null if
     * the file cannot be read, or if [parse] throws.
     *
     * Why: a text that makes [ConfigText.read] throw becomes a message, never a throw on main (hard
     *   rule 2).
     */
    private fun <T : Any> read(uri: Uri, parse: (String) -> T, then: (number: Int, T?) -> Unit): Int {
        val number = ++reads
        val resolver = app.contentResolver
        Thread(Guard.wrap("config read", ::logWarning) {
            var result: T? = null
            try {
                result = readDocument(resolver, uri)?.let(parse)
            } finally {
                // Also after an Error: a read that never handed back would hold every later one.
                app.onMain { then(number, result) }
            }
        }, "verso-config-read").start()
        return number
    }

    /**
     * Keeps the picker's grant across restarts: read and write if the provider grants both (the
     * editor gets both), else read only. False if neither can be kept.
     */
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

    /** Releases a grant no longer needed: of a file that was not linked, or of the one a new link replaced. */
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

        /** [text] and what it says, made on the reading thread ([read]). */
        fun document(text: String) = Document(text, ConfigText.read(text))

        /** The text of [uri], or null if it cannot be read or is larger than any config; a failure is logged. */
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

/**
 * How long a read of the linked file may run before Verso says it hangs. The read itself goes on.
 * Internal so that a test can wait it out.
 *
 * Why: a cloud provider may download the file before it hands it over.
 */
internal const val READ_STUCK_MS = 15_000L
