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
 * [BackupStore] against the REAL MediaStore — the part of the backup no JVM test can fake,
 * and the part that broke: on a device MediaStore publishes a backup whose name is taken by a
 * row we do not own as "verso (1).json", and rewrites "verso.json.tmp" to
 * "verso.json.tmp.json". The old fixed-name lookup then answered "no backup" right after a
 * successful one, and its temp recovery could never find its temp. Backups are now named by
 * time; the clock is faked so the names are predictable.
 *
 * Each test works in its own Download/VersoTest-<uuid>/ folder, removed afterwards. A row we
 * do NOT own (the orphan an uninstall leaves) is made by the shell, whose files land in
 * MediaStore under the shell's ownership — exactly like a file copied in over adb.
 */
@RunWith(AndroidJUnit4::class)
class BackupStoreInstrumentedTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val resolver: ContentResolver = instrumentation.targetContext.contentResolver
    private val subDir = "VersoTest-${UUID.randomUUID()}"
    private val dirPath = "/sdcard/${Environment.DIRECTORY_DOWNLOADS}/$subDir"
    private val logs = mutableListOf<String>()

    /** The test clock: every backup one second after the previous one, unless frozen. */
    private var clock = LocalDateTime.of(2026, 10, 13, 12, 0, 0)
    private var clockFrozen = false

    private fun store(beforePublish: () -> Unit = {}) =
        BackupStore(resolver, subDir, { msg, e -> logs += listOfNotNull(msg, e?.message).joinToString(": ") }, beforePublish) {
            clock.also { if (!clockFrozen) clock = clock.plusSeconds(1) }
        }

    @Before fun setUp() {
        shell("mkdir -p $dirPath")
    }

    @After fun tearDown() {
        // Through the shell, so it also takes the rows we don't own and any pending ones.
        shell("rm -rf $dirPath")
    }

    // ---- the happy path ----------------------------------------------------------------

    @Test fun firstBackupIsPublishedUnderItsTimestampAndReadsBack() {
        val name = store().write("verso", PAYLOAD_A)

        assertEquals("verso-2026-10-13_120000.json", name)
        assertEquals(PAYLOAD_A, readLatest("verso"))
        assertEquals(listOf(name), ownNames())
    }

    @Test fun aSecondBackupReplacesTheFirstAndLeavesExactlyOne() {
        val s = store()
        s.write("verso", PAYLOAD_A)
        val name = s.write("verso", PAYLOAD_B)

        assertEquals("verso-2026-10-13_120001.json", name)
        assertEquals(PAYLOAD_B, readLatest("verso"))
        // One current backup — never a pile, and no temp left behind.
        assertEquals(listOf(name), ownNames())
    }

    @Test fun manyBackupsStillLeaveExactlyOne() {
        val s = store()
        repeat(5) { s.write("verso", "{\"favorites\":[\"a/$it\"]}") }

        assertEquals(1, ownNames().size)
        assertEquals("{\"favorites\":[\"a/4\"]}", readLatest("verso"))
    }

    @Test fun twoBackupsInTheSameSecondAreDodgedAndStillLeaveExactlyOne() {
        clockFrozen = true
        val s = store()
        val first = s.write("verso", PAYLOAD_A)
        // The first still exists when the second is published under the same stamp.
        val second = s.write("verso", PAYLOAD_B)

        assertEquals("verso-2026-10-13_120000.json", first)
        assertEquals("verso-2026-10-13_120000 (1).json", second)
        assertEquals(PAYLOAD_B, readLatest("verso"))
        assertEquals(listOf(second), ownNames())
    }

    @Test fun backupAndUndoPointLiveSideBySide() {
        val s = store()
        val backup = s.write("verso", PAYLOAD_A)
        val undo = s.write("verso-pre-restore", PAYLOAD_B)

        assertEquals(PAYLOAD_A, readLatest("verso"))
        assertEquals(PAYLOAD_B, readLatest("verso-pre-restore"))
        assertEquals(listOf(backup, undo).sortedBy { it }, ownNames())
    }

    // ---- the device bug: names taken by rows we don't own ------------------------------

    @Test fun aForeignRowUnderTheOldFixedNameDoesNotInterfere() {
        shell("touch $dirPath/verso.json")  // the orphan an older install leaves behind

        val name = store().write("verso", PAYLOAD_A)

        assertEquals("verso-2026-10-13_120000.json", name)
        assertEquals(PAYLOAD_A, readLatest("verso"))  // the old code said "no backup" here
    }

    @Test fun aForeignRowUnderTheVeryStampIsDodgedAndTheBackupIsStillFound() {
        shell("touch $dirPath/verso-2026-10-13_120000.json")

        val name = store().write("verso", PAYLOAD_A)

        assertEquals("verso-2026-10-13_120000 (1).json", name)
        assertEquals(PAYLOAD_A, readLatest("verso"))
    }

    @Test fun anOwnBackupFromBeforeTheTimestampsIsRestoredThenReplaced() {
        // What the fixed-name version left on a device: an own "verso (2).json".
        val legacy = BackupStore(resolver, subDir, { msg, _ -> logs += msg }) { clock }
        legacyWrite(legacy, "verso (2).json", PAYLOAD_A)
        assertEquals(PAYLOAD_A, readLatest("verso"))

        val name = store().write("verso", PAYLOAD_B)
        assertEquals(listOf(name), ownNames())
    }

    // ---- failures and crashes: the old backup must survive -----------------------------

    @Test fun aFailedPublishKeepsTheOldBackupAndLeavesNoTemp() {
        val old = store().write("verso", PAYLOAD_A)

        val name = store(beforePublish = { throw IOException("simulated publish failure") })
            .write("verso", PAYLOAD_B)

        assertNull(name)
        assertEquals(PAYLOAD_A, readLatest("verso"))
        assertEquals(listOf(old), ownNames())
        assertTrue(logs.any { "failed" in it })
    }

    @Test fun aCrashBeforePublishKeepsTheOldBackupAndTheNextBackupSweepsTheTemp() {
        val old = store().write("verso", PAYLOAD_A)

        crashBeforePublish(PAYLOAD_B)

        // The old backup is still what a restore reads; the stranded temp kept its name.
        assertEquals(PAYLOAD_A, readLatest("verso"))
        assertEquals(listOf(old, "verso.tmp.json").sortedBy { it }, ownNames())

        val name = store().write("verso", PAYLOAD_C)
        assertEquals(PAYLOAD_C, readLatest("verso"))
        assertEquals(listOf(name), ownNames())
    }

    @Test fun aCrashAfterPublishBeforeTheSweepLeavesTwoAndTheNewerWins() {
        // What a crash between the publish and the sweep leaves: two published own backups.
        store().write("verso", PAYLOAD_A)
        ownPublished("verso-2026-10-13_120500.json", PAYLOAD_B)

        assertEquals(PAYLOAD_B, readLatest("verso"))  // the newer row, by id

        val name = store().write("verso", PAYLOAD_C)
        assertEquals(listOf(name), ownNames())        // the next backup sweeps both
        assertEquals(PAYLOAD_C, readLatest("verso"))
    }

    @Test fun aPublishWhoseTempVanishedFailsAndKeepsTheOldBackup() {
        val old = store().write("verso", PAYLOAD_A)

        // The temp row vanishes before its publish. On a device MediaStore does not answer the
        // publishing update with "0 rows" but throws ("has no access"); either way it must be a
        // failed backup — the updated != 1 check covers the other answer.
        val name = store(beforePublish = { deleteOwnTemps() }).write("verso", PAYLOAD_B)

        assertNull(name)
        assertEquals(PAYLOAD_A, readLatest("verso"))
        assertEquals(listOf(old), ownNames())
        assertTrue(logs.toString(), logs.any { "failed" in it })
    }

    @Test fun anInterruptedFirstBackupIsStillRestorableFromItsTemp() {
        crashBeforePublish(PAYLOAD_A)

        assertEquals(listOf("verso.tmp.json"), ownNames())
        assertEquals(PAYLOAD_A, readLatest("verso"))
    }

    @Test fun noBackupAtAllFindsNothing() {
        assertNull(store().findLatest("verso"))
    }

    // ---- helpers -----------------------------------------------------------------------

    /** A crash between the complete temp and its publish: an Error is not caught by
     *  BackupStore (it catches Exception), so the write is abandoned exactly there. */
    private fun crashBeforePublish(payload: String) {
        try {
            store(beforePublish = { throw SimulatedCrash() }).write("verso", payload)
            fail("the simulated crash should have escaped")
        } catch (_: SimulatedCrash) {
        }
    }

    private class SimulatedCrash : Error()

    /** A published row of ours named exactly [name], written the way MediaStore publishes. */
    private fun ownPublished(name: String, payload: String) {
        val uri = resolver.insert(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            android.content.ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$subDir")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            },
        )!!
        resolver.openOutputStream(uri)!!.use { it.write(payload.toByteArray()) }
        resolver.update(
            uri,
            android.content.ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
            null,
            null,
        )
    }

    /** Delete every pending temp row of ours — the row a publish is about to update. */
    private fun deleteOwnTemps() {
        val query = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.MediaColumns.RELATIVE_PATH}=?")
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf("${Environment.DIRECTORY_DOWNLOADS}/$subDir/"))
            putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
        }
        resolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME),
            query,
            null,
        )!!.use { c ->
            while (c.moveToNext()) {
                if (BackupFiles.isTemp("verso", c.getString(1))) {
                    resolver.delete(android.content.ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, c.getLong(0)), null, null)
                }
            }
        }
    }

    /** Write [payload] as an own row named exactly [name], as the fixed-name version did:
     *  a normal backup, then renamed in place. */
    private fun legacyWrite(store: BackupStore, name: String, payload: String) {
        store.write("verso", payload)
        val uri = store.findLatest("verso")!!
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
        return s.findLatest(base)?.let { s.readText(it) }
    }

    /** The display names of every row of ours in the folder, pending ones included. */
    private fun ownNames(): List<String> {
        val query = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.MediaColumns.RELATIVE_PATH}=?")
            putStringArray(
                ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                arrayOf("${Environment.DIRECTORY_DOWNLOADS}/$subDir/"),
            )
            putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
        }
        return resolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
            query,
            null,
        )!!.use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }.sorted()
    }

    /** Run [command] as the shell user and wait for it to finish. */
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
