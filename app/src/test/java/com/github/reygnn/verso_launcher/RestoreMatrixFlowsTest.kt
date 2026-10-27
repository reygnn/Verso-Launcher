package com.github.reygnn.verso_launcher

import android.app.Activity
import android.app.AlertDialog
import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.content.DialogInterface
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import com.github.reygnn.verso_launcher.RestoreMatrix.Cell
import com.github.reygnn.verso_launcher.RestoreMatrix.Cmd
import com.github.reygnn.verso_launcher.RestoreMatrix.Content
import com.github.reygnn.verso_launcher.RestoreMatrix.Downloads
import com.github.reygnn.verso_launcher.RestoreMatrix.Fault
import com.github.reygnn.verso_launcher.RestoreMatrix.Outcome
import com.github.reygnn.verso_launcher.RestoreMatrix.Role
import com.github.reygnn.verso_launcher.RestoreMatrix.Start
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowToast
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * docs/spec/restore-matrix.md through the real Activity and the real Commands: the cells a user can
 * reach, each held to [RestoreMatrix]'s answer — the toast, memory, verso.json and Download/Verso.
 * RestoreMatrixTest runs every cell on the JVM; this one pins the glue it retells there.
 *
 * Not here: a failing verso.json write (the JVM covers it), a fresh install (its default favorites seed through the same gate).
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
class RestoreMatrixFlowsTest internal constructor(private val cell: Cell) {

    /**
     * Download/Verso for the matrix: rows with an _ID and a name that a query lists, and one fault
     * at a time. It models no MediaStore rule — no renamed name, no owner, no expiry: those stay
     * with BackupStoreInstrumentedTest on a device.
     */
    class ListingDownloads : ContentProvider() {
        class Row(val id: Long, val name: String, var pending: Boolean)

        val rows = mutableListOf<Row>()
        internal var fault = Fault.NONE

        /** Published rows something deleted: hard rule 6 says none, ever. */
        val deleted = mutableListOf<String>()

        fun seed(name: String, payload: String) {
            val id = rows.size + 1L
            fileOf(id).writeText(payload)
            rows += Row(id, name, pending = false)
        }

        fun published() = rows.filter { !it.pending }.map { it.name to fileOf(it.id).readText() }

        private fun fileOf(id: Long) = File(context!!.cacheDir, "matrix-row-$id")

        override fun onCreate() = true

        override fun insert(uri: Uri, values: ContentValues?): Uri? {
            if (fault == Fault.DOWNLOAD_WRITE) return null
            val id = rows.size + 1L
            rows += Row(id, values?.getAsString(MediaStore.MediaColumns.DISPLAY_NAME).orEmpty(), pending = true)
            return ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id)
        }

        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            if (mode.startsWith("r") && fault == Fault.READ) throw FileNotFoundException("matrix: read fails")
            return ParcelFileDescriptor.open(
                fileOf(ContentUris.parseId(uri)),
                ParcelFileDescriptor.parseMode(mode) or ParcelFileDescriptor.MODE_CREATE,
            )
        }

        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
            val row = rows.singleOrNull { it.id == ContentUris.parseId(uri) } ?: return 0
            row.pending = values?.getAsInteger(MediaStore.MediaColumns.IS_PENDING) == 1
            return 1
        }

        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
            val row = rows.singleOrNull { it.id == ContentUris.parseId(uri) } ?: return 0
            if (!row.pending) deleted += row.name
            rows -= row
            return 1
        }

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor {
            val one = uri != MediaStore.Downloads.EXTERNAL_CONTENT_URI
            if (!one && fault == Fault.LIST) throw IllegalStateException("matrix: listing fails")
            val columns = projection ?: arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME)
            return MatrixCursor(columns).apply {
                rows.filter { !it.pending && (!one || it.id == ContentUris.parseId(uri)) }.forEach { row ->
                    addRow(columns.map { if (it == MediaStore.MediaColumns._ID) row.id else row.name })
                }
            }
        }

        override fun getType(uri: Uri): String? = null
    }

    private val app = RuntimeEnvironment.getApplication() as VersoApp
    private val controller = Robolectric.buildActivity(MainActivity::class.java)
    private val activity: MainActivity get() = controller.get()
    private val hold = CountDownLatch(1)
    private val media = Robolectric.setupContentProvider(ListingDownloads::class.java, MediaStore.AUTHORITY)

    @After fun release() {
        hold.countDown()
        Thread.getAllStackTraces().keys.filter { it.name == "verso-restore-read" }.forEach { it.join(5000) }
        settle()
        if (!activity.isDestroyed) controller.pause().stop().destroy()
    }

    private fun settle() = repeat(4) {
        app.io.submit {}.get(5, TimeUnit.SECONDS)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private val prompt: EditText get() = activity.window.decorView.collect<EditText>().single()

    private fun enter(text: String) {
        prompt.setText(text)
        prompt.onEditorAction(EditorInfo.IME_ACTION_GO)
    }

    /** "~restore-saf": the picker answers, the read lands, the dialog (if one comes) gets its OK. */
    private fun restoreSaf() {
        val picked = Uri.parse("content://cloud/picked.json")
        val content: InputStream =
            if (cell.fault == Fault.READ) object : InputStream() {
                override fun read(): Int = throw IOException("matrix: the provider fails the read")
            }
            else RestoreMatrix.payloadOf(cell, RestoreMatrix.picked).byteInputStream()
        shadowOf(activity.contentResolver).registerInputStream(picked, content)
        enter(cell.cmd.typed)
        val request = shadowOf(activity).nextStartedActivityForResult ?: return
        shadowOf(activity).receiveResult(request.intent, Activity.RESULT_OK, Intent().setData(picked))
        Thread.getAllStackTraces().keys.filter { it.name == "verso-restore-read" }.forEach { it.join(5000) }
        shadowOf(Looper.getMainLooper()).idle()
        (ShadowDialog.getLatestDialog() as? AlertDialog)?.takeIf { it.isShowing }?.let {
            it.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Test fun `the real Activity ends the cell as the spec says`() {
        val seeded = RestoreMatrix.payloads(cell)
        seeded.forEach { (name, payload) -> media.seed(name, payload) }
        media.fault = cell.fault
        val file = File(app.filesDir, ConfigStore.CONFIG_FILE)
        when (cell.start) {
            Start.GOOD -> file.writeText(ConfigJson.serialize(RestoreMatrix.GOOD_CONFIG))
            Start.UNREADABLE -> file.writeText(RestoreMatrix.TORN)
            Start.FRESH -> error("not reachable here")
        }
        if (cell.loaded) {
            controller.setup()
            settle()
        } else {
            app.io.execute { hold.await(30, TimeUnit.SECONDS) }  // the first load waits behind it
            controller.setup()
        }

        if (cell.cmd == Cmd.RESTORE_SAF && cell.loaded) restoreSaf() else enter(cell.cmd.typed)
        if (cell.loaded) settle()

        val expected = RestoreMatrix.expected(cell)
        assertMessage(expected.outcome)
        assertEquals("memory", expected.memory, app.core.state.config)
        assertEquals("verso.json", expected.disk, ConfigStore(app.filesDir, log = { _, _ -> }).load())

        // 6: nothing deleted or changed, at most the one new row the spec names, holding the config the user had.
        assertEquals("deleted", emptyList<String>(), media.deleted)
        val rows = media.published()
        assertEquals("seeded rows", seeded, rows.take(seeded.size))
        val added = rows.drop(seeded.size)
        if (expected.newRow == null) {
            assertEquals("added rows", emptyList<Pair<String, String>>(), added)
        } else {
            assertEquals("added rows", 1, added.size)
            assertTrue("${added.single().first} is not a ${expected.newRow}", BackupFiles.isPublished(expected.newRow, added.single().first))
            assertEquals("the added row", cell.current, ConfigJson.parse(added.single().second))
        }
    }

    /** The toast the spec names; one with the time or a name in it matched around the argument. */
    private fun assertMessage(outcome: Outcome) {
        val shown = ShadowToast.getTextOfLatestToast()
        val (before, after) = activity.getString(outcome.message, "\u0000").split("\u0000").let { it[0] to it.getOrElse(1) { "" } }
        assertTrue(
            "expected \"$before…$after\" (${outcome.name}), got \"$shown\"",
            shown != null && shown.startsWith(before) && shown.endsWith(after),
        )
    }

    companion object {
        /** The cells a user can reach on the real Activity, without the ones only the JVM can make. */
        private fun reachable(c: Cell): Boolean = when {
            !c.loaded -> c.start == Start.GOOD && c.typical
            c.start == Start.UNREADABLE -> c.typical
            c.start == Start.FRESH -> false
            else -> when (c.cmd) {
                Cmd.BACKUP -> backupReachable(c)
                Cmd.RESTORE_SAF -> c.downloads == Downloads.BOTH_UNDO_NEWEST && c.eachFaultOnce
                Cmd.RESTORE, Cmd.UNDO -> restoreReachable(c)
            }
        }

        /** One cell per command: a full folder, a real config, no fault. */
        private val Cell.typical get() = downloads == Downloads.BOTH_UNDO_NEWEST && plain

        /** Its content and the reading faults change nothing: an empty and a full folder, written or not. */
        private fun backupReachable(c: Cell) = c.downloads in setOf(Downloads.EMPTY, Downloads.BOTH_UNDO_NEWEST) &&
            c.content == Content.DIFFERENT && c.fault in setOf(Fault.NONE, Fault.DOWNLOAD_WRITE)

        private fun restoreReachable(c: Cell): Boolean {
            val role = if (c.cmd == Cmd.UNDO) Role.UNDO_TARGET else Role.RESTORE_TARGET
            return when {
                c.fault == Fault.LIST -> c.downloads == Downloads.BOTH_UNDO_NEWEST && c.content == Content.DIFFERENT
                c.downloads.seeds.none { it.role == role } -> c.plain
                else -> c.eachFaultOnce
            }
        }

        private val Cell.plain get() = content == Content.DIFFERENT && fault == Fault.NONE

        /** Every content without a fault, and the read and the undo point failing for a real config. */
        private val Cell.eachFaultOnce get() =
            fault == Fault.NONE || (content == Content.DIFFERENT && fault in setOf(Fault.READ, Fault.DOWNLOAD_WRITE))

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun cells(): List<Array<Any>> = RestoreMatrix.cells().filter(::reachable).map { arrayOf(it) }
    }
}
