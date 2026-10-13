package com.github.reygnn.verso_launcher

import com.github.reygnn.verso_launcher.BackupFiles.Entry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/**
 * JVM tests for [BackupFiles] — which of the app's own Download/Verso rows a backup is, which
 * one a restore reads, which ones a new backup replaces. The MediaStore side (that it really
 * renames a clash to "verso (1).json") is pinned on a device by BackupStoreInstrumentedTest.
 */
class BackupFilesTest {

    private val base = "verso"
    private val pre = "verso-pre-restore"

    @Test fun `a backup is named after the moment it was taken`() {
        val at = LocalDateTime.of(2026, 10, 13, 9, 5, 7)
        assertEquals("verso-2026-10-13_090507.json", BackupFiles.publishedName(base, at))
        assertEquals("verso-pre-restore-2026-10-13_090507.json", BackupFiles.publishedName(pre, at))
    }

    @Test fun `a toast shows a backup's time, not its long name`() {
        assertEquals("16:19", BackupFiles.timeOf("verso-pre-restore-2026-10-13_161931.json"))
        assertEquals("16:19", BackupFiles.timeOf("verso-pre-restore-2026-10-13_161931 (1).json"))
        assertEquals("verso.json", BackupFiles.timeOf("verso.json"))  // no stamp: the name itself
    }

    @Test fun `names sort in time order`() {
        val earlier = BackupFiles.publishedName(base, LocalDateTime.of(2026, 9, 30, 23, 59, 59))
        val later = BackupFiles.publishedName(base, LocalDateTime.of(2026, 10, 1, 0, 0, 0))
        assertTrue(earlier < later)
    }

    @Test fun `published names are the stamped name and MediaStore's numbered dodge`() {
        val at = LocalDateTime.of(2026, 10, 13, 12, 7, 45)
        assertTrue(BackupFiles.isPublished(base, BackupFiles.publishedName(base, at)))
        assertTrue(BackupFiles.isPublished(base, "verso-2026-10-13_120745 (1).json"))
        assertTrue(BackupFiles.isPublished(pre, BackupFiles.publishedName(pre, at)))
        assertTrue(BackupFiles.isPublished(pre, "verso-pre-restore-2026-10-13_120745 (2).json"))
    }

    @Test fun `the earlier fixed names still count, so old backups are restored and swept`() {
        assertTrue(BackupFiles.isPublished(base, "verso.json"))
        assertTrue(BackupFiles.isPublished(base, "verso (1).json"))
        assertTrue(BackupFiles.isPublished(base, "verso (12).json"))
        assertTrue(BackupFiles.isPublished(pre, "verso-pre-restore (2).json"))
    }

    @Test fun `the two bases never claim each other's files, nor temps`() {
        assertFalse(BackupFiles.isPublished(base, "verso-pre-restore.json"))
        assertFalse(BackupFiles.isPublished(base, "verso-pre-restore-2026-10-13_120745.json"))
        assertFalse(BackupFiles.isPublished(pre, "verso.json"))
        assertFalse(BackupFiles.isPublished(pre, "verso-2026-10-13_120745.json"))
        assertFalse(BackupFiles.isPublished(base, "verso.tmp.json"))
        assertFalse(BackupFiles.isTemp(base, "verso-pre-restore.tmp.json"))
        assertFalse(BackupFiles.isTemp(base, "verso.json"))
    }

    @Test fun `the cleanup undo point is a base of its own`() {
        val cleanup = "verso-pre-cleanup"
        val name = "verso-pre-cleanup-2026-10-13_120745.json"
        assertTrue(BackupFiles.isPublished(cleanup, name))
        assertFalse(BackupFiles.isPublished(base, name))
        assertFalse(BackupFiles.isPublished(pre, name))
        assertFalse(BackupFiles.isPublished(cleanup, "verso-pre-restore-2026-10-13_120745.json"))
        assertFalse(BackupFiles.isPublished(cleanup, "verso-2026-10-13_120745.json"))
    }

    @Test fun `unrelated look-alikes are not backups`() {
        assertFalse(BackupFiles.isPublished(base, "verso (1).json.bak"))
        assertFalse(BackupFiles.isPublished(base, "my verso.json"))
        assertFalse(BackupFiles.isPublished(base, "verso (x).json"))
        assertFalse(BackupFiles.isPublished(base, "verso(1).json"))
        assertFalse(BackupFiles.isPublished(base, "sigil.json"))
        assertFalse(BackupFiles.isPublished(base, "verso-2026-10-13.json"))
        assertFalse(BackupFiles.isPublished(base, "verso-2026-10-13_1207.json"))
    }

    @Test fun `temps include the numbered dodge and the pre-fix name`() {
        assertTrue(BackupFiles.isTemp(base, BackupFiles.tempName(base)))
        assertTrue(BackupFiles.isTemp(base, "verso.tmp (1).json"))
        // What MediaStore made of the old code's "verso.json.tmp".
        assertTrue(BackupFiles.isTemp(base, "verso.json.tmp.json"))
        assertTrue(BackupFiles.isTemp(pre, "verso-pre-restore.json.tmp.json"))
    }

    @Test fun `temp names end in json, so MediaStore keeps them as given`() {
        assertTrue(BackupFiles.tempName(base).endsWith(".json"))
        assertFalse(BackupFiles.isPublished(base, BackupFiles.tempName(base)))
    }

    @Test fun `a restore takes the newest published backup, by id, whatever its name`() {
        val own = listOf(
            Entry(5, "verso (1).json"),                     // from before the timestamps
            Entry(9, "verso-2026-10-13_120745.json"),
            Entry(7, "verso-2026-10-14_080000.json"),       // a clock set back: id still decides
        )
        assertEquals(Entry(9, "verso-2026-10-13_120745.json"), BackupFiles.latestToRestore(base, own))
    }

    @Test fun `a published backup beats a newer stranded temp`() {
        val own = listOf(Entry(3, "verso.json"), Entry(8, "verso.tmp.json"))
        assertEquals(Entry(3, "verso.json"), BackupFiles.latestToRestore(base, own))
    }

    @Test fun `with nothing published, the newest temp is the interrupted first backup`() {
        val own = listOf(Entry(2, "verso.json.tmp.json"), Entry(4, "verso.tmp.json"), Entry(6, "verso-pre-restore.json"))
        assertEquals(Entry(4, "verso.tmp.json"), BackupFiles.latestToRestore(base, own))
    }

    @Test fun `a restore never reads the undo point, and none at all gives null`() {
        assertNull(BackupFiles.latestToRestore(base, listOf(Entry(1, "verso-pre-restore.json"))))
        assertNull(BackupFiles.latestToRestore(base, emptyList()))
    }

    @Test fun `a new backup replaces older copies and temps of its base only`() {
        val own = listOf(
            Entry(1, "verso.json"),
            Entry(2, "verso.tmp.json"),
            Entry(3, "verso-pre-restore.json"),
            Entry(4, "verso.json.tmp.json"),
            Entry(5, "verso-2026-10-13_120745.json"),  // the one just published
            Entry(6, "notes.txt"),
            Entry(7, "verso-pre-restore-2026-10-13_120000.json"),
        )
        assertEquals(
            listOf(1L, 2L, 4L),
            BackupFiles.replacedBy(base, keepId = 5, own = own).map { it.id },
        )
    }

    @Test fun `the just-published backup is never in its own replacement list`() {
        assertEquals(emptyList<Entry>(), BackupFiles.replacedBy(base, 1, listOf(Entry(1, "verso.json"))))
    }
}
