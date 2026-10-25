package com.github.reygnn.verso_launcher

import com.github.reygnn.verso_launcher.BackupFiles.Entry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/** That MediaStore really renames a clash to "verso (1).json" only a device shows (BackupStoreInstrumentedTest). */
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

    @Test fun `the earlier fixed names still count, so old backups are restored`() {
        assertTrue(BackupFiles.isPublished(base, "verso.json"))
        assertTrue(BackupFiles.isPublished(base, "verso (1).json"))
        assertTrue(BackupFiles.isPublished(base, "verso (12).json"))
        assertTrue(BackupFiles.isPublished(pre, "verso-pre-restore (2).json"))
    }

    @Test fun `the two bases never claim each other's files, nor an old temp`() {
        assertFalse(BackupFiles.isPublished(base, "verso-pre-restore.json"))
        assertFalse(BackupFiles.isPublished(base, "verso-pre-restore-2026-10-13_120745.json"))
        assertFalse(BackupFiles.isPublished(pre, "verso.json"))
        assertFalse(BackupFiles.isPublished(pre, "verso-2026-10-13_120745.json"))
        assertFalse(BackupFiles.isPublished(base, "verso.tmp.json"))
    }

    @Test fun `every base is distinct and claims no other base's files`() {
        val bases = listOf(BackupFiles.BACKUP_BASE, BackupFiles.PRE_RESTORE_BASE, BackupFiles.PRE_CLEANUP_BASE, BackupFiles.UNREADABLE_BASE)
        assertEquals(bases.size, bases.toSet().size)
        for (a in bases) for (b in bases) if (a != b) {
            assertFalse("$a claims $b", BackupFiles.isPublished(a, "$b-2026-10-13_120745.json"))
            assertFalse("$a claims $b (1)", BackupFiles.isPublished(a, "$b-2026-10-13_120745 (1).json"))
        }
    }

    @Test fun `the cleanup undo point is a base of its own`() {
        val cleanup = BackupFiles.PRE_CLEANUP_BASE
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

    @Test fun `a restore takes the newest published backup, by id, whatever its name`() {
        val own = listOf(
            Entry(5, "verso (1).json"),                     // from before the timestamps
            Entry(9, "verso-2026-10-13_120745.json"),
            Entry(7, "verso-2026-10-14_080000.json"),       // a clock set back: id still decides
        )
        assertEquals(Entry(9, "verso-2026-10-13_120745.json"), BackupFiles.latestToRestore(base, own))
    }

    /** Earlier versions wrote a temp and renamed it; one left behind is no backup. */
    @Test fun `a temp an earlier version left behind is never restored`() {
        assertEquals(Entry(3, "verso.json"), BackupFiles.latestToRestore(base, listOf(Entry(3, "verso.json"), Entry(8, "verso.tmp.json"))))
        assertNull(BackupFiles.latestToRestore(base, listOf(Entry(2, "verso.json.tmp.json"), Entry(4, "verso.tmp.json"))))
    }

    @Test fun `a restore never reads the undo point, and none at all gives null`() {
        assertNull(BackupFiles.latestToRestore(base, listOf(Entry(1, "verso-pre-restore.json"))))
        assertNull(BackupFiles.latestToRestore(base, emptyList()))
    }
}
