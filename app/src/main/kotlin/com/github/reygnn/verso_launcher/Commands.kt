package com.github.reygnn.verso_launcher

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.SyncFailedException

/**
 * The "~" commands' work, owned by the process: every hand-back goes to the process and reads the
 * state from Core, so a command lands whatever happens to the window that started it. A window is
 * reached only as a [LauncherWindow]. What a window decides stays in MainActivity: refusing a
 * command up front, the prompt, the pickers, "~restart".
 *
 * "~restore" and "~undo" swap the config on main in one step (LauncherState.replace), so nothing
 * runs in the background that an edit could race. Only the picked document is read or written on a
 * thread of its own: a provider (a cloud folder, a USB stick) may block for good, and must hold
 * neither the IO thread nor an activity.
 *
 * Main thread only, but for those two threads.
 */
internal class Commands(private val app: VersoApp) {

    private val core get() = app.core
    private val results get() = app.results
    private val window get() = app.instance

    /** "~backup" after the picker answered with a new document: the config as it is now. */
    fun writeBackup(uri: Uri) {
        val snapshot = core.state.loadedSnapshot() ?: return results.refuse(app.getString(R.string.toast_not_loaded))
        val payload = ConfigJson.serialize(snapshot)
        val resolver = app.contentResolver
        Thread(Guard.wrap("backup write", ::logWarning) {
            val written = writeDocument(resolver, uri, payload)
            app.onMain { results.report(app.getString(if (written) R.string.toast_backup_ok else R.string.toast_backup_failed)) }
        }, "verso-backup-write").start()
    }

    /**
     * "~restore" after the picker answered; the pick is the process's from here (PendingPick).
     * Back on main a dropped or superseded pick is ignored.
     */
    fun readPick(uri: Uri) {
        val number = app.pick.begin()
        val resolver = app.contentResolver
        Thread(Guard.wrap("restore read", ::logWarning) {
            // hard rule 7: parseForeign, never the lenient parse, for a file from outside.
            val restored = readDocument(resolver, uri)?.let { ConfigJson.parseForeign(it) }
            app.onMain {
                if (app.pick.arrived(number, restored)) {
                    if (restored == null) results.report(app.getString(R.string.toast_restore_failed)) else offerPendingPick()
                }
            }
        }, "verso-restore-read").start()
    }

    /**
     * Asks the window in front about a read pick. Only with the config loaded: a first load would
     * replace the config under the open dialog and void it (hard rule 5).
     */
    fun offerPendingPick() {
        if (!core.state.configLoaded) return
        val front = window?.takeIf { it.inFront } ?: return
        val restored = app.pick.ask() ?: return
        front.offerRestore(restored)
    }

    /**
     * The restore dialog's answer: OK adopts (once the config is loaded). Cancel ends the pick
     * without a word; a dialog gone unanswered (the page turned, the window left) ends it with one.
     */
    fun answerPick(confirmed: Boolean, byCancel: Boolean) {
        if (!app.pick.answer(confirmed)) return
        when {
            confirmed -> adoptPendingPick()
            !byCancel -> results.report(app.getString(R.string.toast_restore_dropped))
        }
    }

    /** Adopts a confirmed pick once the config is loaded (hard rule 5): of the OK and a load, the last one adopts. */
    fun adoptPendingPick() {
        if (!core.state.configLoaded) return
        val restored = app.pick.take() ?: return
        // So a "my favorites are gone" report can be settled from the log.
        Log.i(
            "Verso",
            "restore: ${restored.favorites.size} favorites, ${restored.hidden.size} hidden, " +
                "${restored.names.size} names, ${restored.tags.size} tagged",
        )
        swap(restored, undo = false)
    }

    /** "~undo": the config the last "~restore" or "~undo" replaced comes back, and this one waits in its place. */
    fun undo() {
        val previous = core.undoSlot.read() ?: return results.report(app.getString(R.string.toast_undo_none))
        swap(previous, undo = true)
    }

    private fun swap(next: VersoConfig, undo: Boolean) {
        val message = when (core.state.replace(next) { payload -> core.undoSlot.write(payload) }) {
            Replaced.NOT_LOADED -> return results.refuse(app.getString(R.string.toast_not_loaded))
            Replaced.SAME -> if (undo) R.string.toast_undo_same else R.string.toast_restore_same
            Replaced.UNDO_FAILED -> R.string.toast_restore_undo_failed
            Replaced.DONE -> if (undo) R.string.toast_undo_ok else R.string.toast_restore_ok
        }
        results.report(app.getString(message))
    }

    private companion object {
        /**
         * False if the document could not be written whole (logged); then it is deleted where the
         * provider allows, so no empty or half file waits to be picked by a later "~restore".
         */
        fun writeDocument(resolver: ContentResolver, uri: Uri, payload: String): Boolean {
            // hard rule 2: a failed backup is a message
            val written = @Suppress("TooGenericExceptionCaught") try {
                openTruncated(resolver, uri)?.use { out ->
                    out.write(payload.toByteArray(Charsets.UTF_8))
                    out.flush()
                    sync(out)
                    true
                } ?: false.also { logWarning("backup: no stream for $uri", null) }
            } catch (e: Exception) {
                logWarning("backup: cannot write $uri", e)
                false
            }
            if (!written) deleteDocument(resolver, uri)
            return written
        }

        /**
         * "wt": a provider may hand over an existing file (some overwrite instead of renaming), and
         * since Android 10 "w" no longer truncates — a shorter config would keep the old tail, and
         * the file would not parse. A provider that refuses "t" gets "w".
         */
        private fun openTruncated(resolver: ContentResolver, uri: Uri): OutputStream? = try {
            resolver.openOutputStream(uri, "wt")
        } catch (e: IllegalArgumentException) {
            logWarning("backup: $uri takes no \"wt\", writing with \"w\"", e)
            resolver.openOutputStream(uri, "w")
        } catch (e: FileNotFoundException) {
            logWarning("backup: $uri takes no \"wt\", writing with \"w\"", e)
            resolver.openOutputStream(uri, "w")
        }

        /** On disk before "backed up" is said, where the provider is a local file; a cloud one syncs itself. */
        private fun sync(out: OutputStream) {
            val fd = (out as? FileOutputStream)?.fd ?: return
            try {
                fd.sync()
            } catch (e: SyncFailedException) {
                // The bytes are written; only the hardening failed.
                logWarning("backup: sync failed", e)
            }
        }

        private fun deleteDocument(resolver: ContentResolver, uri: Uri) {
            // hard rule 2: tidying up after a failure must not fail the message
            @Suppress("TooGenericExceptionCaught") try {
                if (!DocumentsContract.deleteDocument(resolver, uri)) logWarning("backup: $uri not deleted", null)
            } catch (e: Exception) {
                logWarning("backup: cannot delete the failed $uri", e)
            }
        }

        /** Null if it cannot be read or is beyond any config's size; logged. */
        fun readDocument(resolver: ContentResolver, uri: Uri): String? =
            // hard rule 2: an unreadable file is a message
            @Suppress("TooGenericExceptionCaught") try {
                resolver.openInputStream(uri)?.use { ConfigJson.readCapped(it) }
            } catch (e: Exception) {
                logWarning("restore: cannot read $uri", e)
                null
            }
    }
}
