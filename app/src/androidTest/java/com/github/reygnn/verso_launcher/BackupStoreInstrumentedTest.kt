package com.github.reygnn.verso_launcher

import android.content.ContentResolver
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.util.UUID

/**
 * [BackupStore] against the REAL MediaStore — the part of the backup no JVM test can fake,
 * and the part that broke: on a device MediaStore publishes a backup whose name is taken by a
 * row we do not own as "verso (1).json", and rewrites "verso.json.tmp" to
 * "verso.json.tmp.json". The old fixed-name lookup then answered "no backup" right after a
 * successful one, and its temp recovery could never find its temp.
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

    private fun store(beforePublish: () -> Unit = {}) =
        BackupStore(resolver, subDir, { msg, _ -> logs += msg }, beforePublish)

    @Before fun setUp() {
        shell("mkdir -p $dirPath")
    }

    @After fun tearDown() {
        // Through the shell, so it also takes the rows we don't own and any pending ones.
        shell("rm -rf $dirPath")
    }

    // ---- the happy path ----------------------------------------------------------------

    @Test fun firstBackupIsPublishedUnderThePlainNameAndReadsBack() {
        val name = store().write("verso", PAYLOAD_A)

        assertEquals("verso.json", name)
        assertEquals(PAYLOAD_A, readLatest("verso"))
        assertEquals(listOf("verso.json"), ownNames())
    }

    @Test fun aSecondBackupReplacesTheFirstAndLeavesExactlyOne() {
        val s = store()
        s.write("verso", PAYLOAD_A)
        val name = s.write("verso", PAYLOAD_B)

        assertNotNull(name)
        assertEquals(PAYLOAD_B, readLatest("verso"))
        // One current backup — never a pile of "(n)" copies, and no temp left behind.
        assertEquals(listOf(name), ownNames())
    }

    @Test fun manyBackupsStillLeaveExactlyOne() {
        val s = store()
        repeat(5) { s.write("verso", "{\"favorites\":[\"a/$it\"]}") }

        assertEquals(1, ownNames().size)
        assertEquals("{\"favorites\":[\"a/4\"]}", readLatest("verso"))
    }

    @Test fun backupAndUndoPointLiveSideBySide() {
        val s = store()
        s.write("verso", PAYLOAD_A)
        s.write("verso-pre-restore", PAYLOAD_B)

        assertEquals(PAYLOAD_A, readLatest("verso"))
        assertEquals(PAYLOAD_B, readLatest("verso-pre-restore"))
        assertEquals(setOf("verso.json", "verso-pre-restore.json"), ownNames().toSet())
    }

    // ---- the device bug: a name taken by a row we don't own --------------------------

    @Test fun aNameTakenByAForeignRowIsDodgedAndTheBackupIsStillFound() {
        shell("touch $dirPath/verso.json")  // the orphan a reinstall leaves behind

        val name = store().write("verso", PAYLOAD_A)

        assertEquals("verso (1).json", name)
        assertEquals(PAYLOAD_A, readLatest("verso"))  // the old code said "no backup" here
    }

    @Test fun repeatedBackupsBesideAForeignRowDoNotPileUp() {
        shell("touch $dirPath/verso.json")
        val s = store()
        repeat(3) { s.write("verso", "{\"favorites\":[\"a/$it\"]}") }

        assertEquals(1, ownNames().size)
        assertEquals("{\"favorites\":[\"a/2\"]}", readLatest("verso"))
    }

    // ---- failures and crashes: the old backup must survive -----------------------------

    @Test fun aFailedPublishKeepsTheOldBackupAndLeavesNoTemp() {
        store().write("verso", PAYLOAD_A)

        val name = store(beforePublish = { throw IOException("simulated publish failure") })
            .write("verso", PAYLOAD_B)

        assertNull(name)
        assertEquals(PAYLOAD_A, readLatest("verso"))
        assertEquals(listOf("verso.json"), ownNames())
        assertTrue(logs.any { "failed" in it })
    }

    @Test fun aCrashBeforePublishKeepsTheOldBackupAndTheNextBackupSweepsTheTemp() {
        store().write("verso", PAYLOAD_A)

        crashBeforePublish(PAYLOAD_B)

        // The old backup is still what a restore reads; the stranded temp kept its name.
        assertEquals(PAYLOAD_A, readLatest("verso"))
        assertEquals(setOf("verso.json", "verso.tmp.json"), ownNames().toSet())

        store().write("verso", PAYLOAD_C)
        assertEquals(PAYLOAD_C, readLatest("verso"))
        assertEquals(1, ownNames().size)
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
