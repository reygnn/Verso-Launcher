package com.github.reygnn.guru

import android.content.ContentResolver
import android.content.ContentValues
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.FileInputStream
import java.util.UUID

/**
 * What only a device shows about Download/Guru: MediaStore publishes the file under its name,
 * adds " (1)" to a name already taken, lists only the rows of their owner — and so pruning
 * never touches a file someone else put there, nor one of ours under a name Guru does not give —
 * and hands a hidden pending row of ours to a
 * query that asks for it, so a save that died halfway is cleared.
 *
 * A row we do NOT own is made by the shell, whose files land in MediaStore under the shell's
 * ownership, like a file copied in over adb or left by an earlier install.
 */
@RunWith(AndroidJUnit4::class)
class GuruDownloadsInstrumentedTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val resolver: ContentResolver = instrumentation.targetContext.contentResolver
    private val subDir = "GuruTest-${UUID.randomUUID()}"
    private val dirPath = "/sdcard/${Environment.DIRECTORY_DOWNLOADS}/$subDir"
    private val logs = mutableListOf<String>()

    private fun downloads(keep: Int = GuruDownloads.KEEP) = GuruDownloads(
        resolver,
        { msg, e -> logs += listOfNotNull(msg, e?.message).joinToString(": ") },
        owner = instrumentation.targetContext.packageName,
        subDir = subDir,
        keep = keep,
    )

    @Before fun setUp() {
        shell("mkdir -p $dirPath")
    }

    @After fun tearDown() {
        // Through the shell, so it also takes the files we don't own and any pending ones; MediaStore
        // drops their rows. The folder's own row may stay: best effort, its output is not checked.
        // No spaces in the argument — executeShellCommand splits on them and runs no shell.
        shell("rm -rf $dirPath")
        val dir = "${Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).path}/$subDir"
        shell("content delete --uri content://media/external/file --where _data='$dir'")
    }

    @Test fun aSavedReportIsPublishedUnderItsNameAndReadsBack() {
        val uri = downloads().save("Guru_Sample-1.0.0-2026-10-24_14-03-12", JSON_A)

        assertNotNull(logs.toString(), uri)
        assertEquals(JSON_A, read(uri!!))
        assertEquals(listOf("Guru_Sample-1.0.0-2026-10-24_14-03-12.json"), ownNames())
    }

    @Test fun aNameAlreadyTakenGetsANumber() {
        val d = downloads()
        d.save("Guru-2026-10-24_14-03-12", JSON_A)
        val second = d.save("Guru-2026-10-24_14-03-12", JSON_B)

        assertEquals(JSON_B, read(second!!))
        assertEquals(setOf("Guru-2026-10-24_14-03-12.json", "Guru-2026-10-24_14-03-12 (1).json"), ownNames().toSet())
    }

    @Test fun aNameTakenByAFileNotOursGetsANumberToo() {
        shell("touch $dirPath/Guru-2026-10-24_14-03-12.json")

        val uri = downloads().save("Guru-2026-10-24_14-03-12", JSON_A)

        assertEquals(JSON_A, read(uri!!))
        assertEquals(listOf("Guru-2026-10-24_14-03-12 (1).json"), ownNames())
    }

    /** Ours under a name Guru does not give ("notes", a file of this app moved in) is not counted either. */
    @Test fun pruneKeepsTheNewestOwnFilesAndNoneOfSomeoneElses() {
        shell("touch $dirPath/foreign.json")
        val d = downloads(keep = 2)
        d.save("notes", JSON_A)
        listOf("a", "b", "c").forEach { d.save("Guru-$it-2026-10-24_14-03-12", JSON_A) }

        d.prune()

        assertEquals(
            setOf("notes.json", "Guru-b-2026-10-24_14-03-12.json", "Guru-c-2026-10-24_14-03-12.json"),
            ownNames().toSet(),
        )
        assertTrue(shell("ls $dirPath").contains("foreign.json"))
        assertTrue(logs.toString(), logs.isEmpty())
    }

    /** A save that died between insert and publish: hidden even from us — prune finds and clears it. */
    @Test fun pruneClearsASaveThatDiedHalfway() {
        val halfway = resolver.insert(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "halfway.json")
                put(MediaStore.MediaColumns.MIME_TYPE, GuruDownloads.MIME)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$subDir")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            },
        )
        assertNotNull(halfway)
        val d = downloads()
        d.save("kept", JSON_A)

        d.prune()

        assertEquals(listOf("kept.json"), ownNames(withPending = true))
        assertTrue(logs.toString(), logs.isEmpty())
    }

    private fun read(uri: Uri): String = resolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) }

    /** The display names of every published row of ours in the folder — pending ones too, if asked. */
    private fun ownNames(withPending: Boolean = false): List<String> {
        val query = Bundle().apply {
            putString(
                ContentResolver.QUERY_ARG_SQL_SELECTION,
                "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.OWNER_PACKAGE_NAME}=?",
            )
            putStringArray(
                ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                arrayOf("${Environment.DIRECTORY_DOWNLOADS}/$subDir/", instrumentation.targetContext.packageName),
            )
            if (withPending) putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
        }
        return resolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), query, null)
            ?.use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
            .orEmpty()
    }

    private fun shell(command: String): String =
        instrumentation.uiAutomation.executeShellCommand(command).use { pfd ->
            FileInputStream(pfd.fileDescriptor).use { it.readBytes().toString(Charsets.UTF_8) }
        }

    private companion object {
        const val JSON_A = """{"REPORT_ID":"a","STACK_TRACE":"java.lang.IllegalStateException: boom"}"""
        const val JSON_B = """{"REPORT_ID":"b","STACK_TRACE":"java.lang.IllegalStateException: bang"}"""
    }
}
