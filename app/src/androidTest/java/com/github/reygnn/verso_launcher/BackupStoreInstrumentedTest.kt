package com.github.reygnn.verso_launcher

import android.content.ContentResolver
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.time.LocalDateTime
import java.util.UUID

/**
 * Only a device shows what MediaStore does with our backups: a name taken by a row we do not own
 * published as "verso (1).json", a pending row hidden from the listing, the name it really gave.
 * Verso never deletes a backup (hard rule 6), so every test also checks that none went missing.
 *
 * A row we do NOT own (the orphan an uninstall leaves) is made by the shell, whose files land in
 * MediaStore under the shell's ownership, exactly like a file copied in over adb.
 */
@RunWith(AndroidJUnit4::class)
class BackupStoreInstrumentedTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val resolver: ContentResolver = instrumentation.targetContext.contentResolver
    private val subDir = "VersoTest-${UUID.randomUUID()}"
    private val dirPath = "/sdcard/${Environment.DIRECTORY_DOWNLOADS}/$subDir"
    private val logs = mutableListOf<String>()

    /** Every backup one second after the previous one, unless frozen. */
    private var clock = LocalDateTime.of(2026, 10, 13, 12, 0, 0)
    private var clockFrozen = false

    private fun store(beforePublish: () -> Unit = {}) =
        BackupStore(
            resolver, subDir, { msg, e -> logs += listOfNotNull(msg, e?.message).joinToString(": ") }, beforePublish,
            now = { clock.also { if (!clockFrozen) clock = clock.plusSeconds(1) } },
            // As the app does: only own rows (the test APK runs in the app's package).
            owner = instrumentation.targetContext.packageName,
        )

    @Before fun setUp() {
        shell("mkdir -p $dirPath")
    }

    @After fun tearDown() {
        // Through the shell, so it also takes the rows we don't own and any pending ones.
        shell("rm -rf $dirPath")
        // rm does not tell MediaStore: its row for the folder would stay. No spaces in the
        // argument — executeShellCommand splits on them and runs no shell to honour quotes.
        val dir = "${Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).path}/$subDir"
        shell("content delete --uri content://media/external/file --where _data='$dir'")
    }

    // ---- the happy path ----------------------------------------------------------------

    @Test fun firstBackupIsPublishedUnderItsTimestampAndReadsBack() {
        val name = store().write("verso", PAYLOAD_A)

        assertEquals("verso-2026-10-13_120000.json", name)
        assertEquals(PAYLOAD_A, readLatest("verso"))
        assertEquals(listOf(name), ownNames())
    }

    @Test fun aSecondBackupKeepsTheFirstAndIsTheOneRestored() {
        val s = store()
        val first = s.write("verso", PAYLOAD_A)
        val second = s.write("verso", PAYLOAD_B)

        assertEquals("verso-2026-10-13_120001.json", second)
        assertEquals(PAYLOAD_B, readLatest("verso"))
        assertEquals(listOf(first, second), ownNames())
    }

    @Test fun manyBackupsAreAllKept() {
        val s = store()
        repeat(5) { s.write("verso", "{\"favorites\":[\"a/$it\"]}") }

        assertEquals(5, ownNames().size)
        assertEquals("{\"favorites\":[\"a/4\"]}", readLatest("verso"))
    }

    @Test fun twoBackupsInTheSameSecondAreDodgedAndTheNameSaysSo() {
        clockFrozen = true
        val s = store()
        val first = s.write("verso", PAYLOAD_A)
        val second = s.write("verso", PAYLOAD_B)

        assertEquals("verso-2026-10-13_120000.json", first)
        assertEquals("verso-2026-10-13_120000 (1).json", second)
        assertEquals(PAYLOAD_B, readLatest("verso"))
        assertEquals(listOf(first!!, second!!).sorted(), ownNames())
    }

    @Test fun backupAndUndoPointLiveSideBySide() {
        val s = store()
        val backup = s.write("verso", PAYLOAD_A)
        val undo = s.write("verso-pre-restore", PAYLOAD_B)

        assertEquals(PAYLOAD_A, readLatest("verso"))
        assertEquals(PAYLOAD_B, readLatest("verso-pre-restore"))
        assertEquals(listOf(backup, undo).sortedBy { it }, ownNames())
    }

    // ---- names taken by rows we don't own ----------------------------------------------

    @Test fun aForeignRowUnderTheOldFixedNameDoesNotInterfere() {
        shell("touch $dirPath/verso.json")  // the orphan an older install leaves behind

        val name = store().write("verso", PAYLOAD_A)

        assertEquals("verso-2026-10-13_120000.json", name)
        assertEquals(PAYLOAD_A, readLatest("verso"))
    }

    @Test fun aForeignRowUnderTheVeryStampIsDodgedAndTheBackupIsStillFound() {
        shell("touch $dirPath/verso-2026-10-13_120000.json")

        val name = store().write("verso", PAYLOAD_A)

        assertEquals("verso-2026-10-13_120000 (1).json", name)
        assertEquals(PAYLOAD_A, readLatest("verso"))
    }

    @Test fun anOwnBackupFromBeforeTheTimestampsIsRestoredAndKept() {
        // An own "verso (2).json", as backups were named before the timestamps.
        val legacy = BackupStore(resolver, subDir, { msg, _ -> logs += msg }, now = { clock }, owner = instrumentation.targetContext.packageName)
        legacyWrite(legacy, "verso (2).json", PAYLOAD_A)
        assertEquals(PAYLOAD_A, readLatest("verso"))

        val name = store().write("verso", PAYLOAD_B)
        assertEquals(PAYLOAD_B, readLatest("verso"))
        assertEquals(listOf("verso (2).json", name!!).sorted(), ownNames())
    }

    // ---- failures and crashes: the old backup must survive -----------------------------

    @Test fun aFailedPublishKeepsTheOldBackupAndLeavesNoPendingRow() {
        val old = store().write("verso", PAYLOAD_A)

        val name = store(beforePublish = { throw IOException("simulated publish failure") })
            .write("verso", PAYLOAD_B)

        assertNull(name)
        assertEquals(PAYLOAD_A, readLatest("verso"))
        assertEquals(listOf(old), ownNames())
        assertEquals("the failed write removed its pending row", 0, ownPendingIds().size)
        assertTrue(logs.any { "failed" in it })
    }

    @Test fun aCrashBeforePublishLeavesAHiddenRowThatIsNeverRestored() {
        val old = store().write("verso", PAYLOAD_A)

        crashBeforePublish(PAYLOAD_B)

        assertEquals(PAYLOAD_A, readLatest("verso"))
        assertEquals(1, ownPendingIds().size)

        val name = store().write("verso", PAYLOAD_C)
        assertEquals(PAYLOAD_C, readLatest("verso"))
        assertEquals("the pending row stays hidden", listOf(old, name), ownNames())
    }

    @Test fun aPublishWhosePendingRowVanishedFailsAndKeepsTheOldBackup() {
        val old = store().write("verso", PAYLOAD_A)

        // On a device MediaStore does not answer the publishing update with "0 rows" but throws.
        val name = store(beforePublish = { deleteOwnPending() }).write("verso", PAYLOAD_B)

        assertNull(name)
        assertEquals(PAYLOAD_A, readLatest("verso"))
        assertEquals(listOf(old), ownNames())
        assertEquals(0, ownPendingIds().size)
        assertTrue(logs.toString(), logs.any { "failed" in it })
    }

    @Test fun anInterruptedFirstBackupIsNothingToRestore() {
        crashBeforePublish(PAYLOAD_A)

        assertEquals(BackupStore.Latest.None, store().lookupLatest("verso"))
    }

    @Test fun noBackupAtAllFindsNothing() {
        assertEquals(BackupStore.Latest.None, store().lookupLatest("verso"))
    }

    // ---- helpers -----------------------------------------------------------------------

    /** BackupStore catches Exception, not Error, so the write is abandoned exactly there. */
    private fun crashBeforePublish(payload: String) {
        try {
            store(beforePublish = { throw SimulatedCrash() }).write("verso", payload)
            fail("the simulated crash should have escaped")
        } catch (_: SimulatedCrash) {
        }
    }

    private class SimulatedCrash : Error()

    private fun deleteOwnPending() {
        for (id in ownPendingIds()) {
            resolver.delete(android.content.ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id), null, null)
        }
    }

    /** The ids of our pending rows in the folder — a backup not yet published. */
    private fun ownPendingIds(): List<Long> {
        val query = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.MediaColumns.RELATIVE_PATH}=?")
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf("${Environment.DIRECTORY_DOWNLOADS}/$subDir/"))
            putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_ONLY)
        }
        return resolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.MediaColumns._ID),
            query,
            null,
        )!!.use { c -> buildList { while (c.moveToNext()) add(c.getLong(0)) } }
    }

    /** A normal backup, then renamed in place to exactly [name]. */
    private fun legacyWrite(store: BackupStore, name: String, payload: String) {
        store.write("verso", payload)
        val uri = (store.lookupLatest("verso") as BackupStore.Latest.Found).uri
        resolver.update(
            uri,
            android.content.ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, name) },
            null,
            null,
        )
        assertEquals(listOf(name), ownNames())
    }

    private fun readLatest(base: String): String? {
        val s = store()
        return (s.lookupLatest(base) as? BackupStore.Latest.Found)?.let { s.readText(it.uri) }
    }

    /** The display names of every published row of ours in the folder. */
    private fun ownNames(): List<String> {
        val query = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.MediaColumns.RELATIVE_PATH}=?")
            putStringArray(
                ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                arrayOf("${Environment.DIRECTORY_DOWNLOADS}/$subDir/"),
            )
        }
        return resolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
            query,
            null,
        )!!.use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }.sorted()
    }

    private fun shell(command: String) {
        instrumentation.uiAutomation.executeShellCommand(command).use { pfd ->
            java.io.FileInputStream(pfd.fileDescriptor).use { it.readBytes() }
        }
    }

    private companion object {
        const val PAYLOAD_A = """{"favorites":["a/A"],"hidden":[],"names":{},"tags":{}}"""
        const val PAYLOAD_B = """{"favorites":["b/B"],"hidden":[],"names":{},"tags":{}}"""
        const val PAYLOAD_C = """{"favorites":["c/C"],"hidden":[],"names":{},"tags":{}}"""
    }
}
