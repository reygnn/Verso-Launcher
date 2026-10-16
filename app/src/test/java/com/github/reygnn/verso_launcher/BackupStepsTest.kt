package com.github.reygnn.verso_launcher

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [BackupSteps] — the IO half of "~backup" and its rule (CLAUDE.md, hard rule 5): memory once it
 * is loaded, else the file on disk, and never an empty config over the real backup.
 */
class BackupStepsTest {

    private val written = mutableListOf<String>()
    private var diskLoads = 0
    private var disk: VersoConfig? = VersoConfig(favorites = linkedSetOf("disk/Disk"))
    private var name: String? = "verso-2026-10-16_220000.json"

    private fun run(inMemory: String?) = BackupSteps.run(
        inMemory = inMemory,
        loadDisk = { diskLoads++; disk },
        serialize = { "fav=" + it.favorites.joinToString() },
        write = { written += it; name },
    )

    @Test fun `a loaded config is backed up from memory, the disk is not read`() {
        assertEquals(BackupSteps.Outcome.Written("verso-2026-10-16_220000.json"), run(inMemory = "fav=memory/Memory"))
        assertEquals(listOf("fav=memory/Memory"), written)
        assertEquals(0, diskLoads)
    }

    /** Before the first load memory holds the empty default: the file on disk is the truth. */
    @Test fun `before the first load the file on disk is backed up`() {
        assertEquals(BackupSteps.Outcome.Written("verso-2026-10-16_220000.json"), run(inMemory = null))
        assertEquals(listOf("fav=disk/Disk"), written)
        assertEquals(1, diskLoads)
    }

    /** The previous backup is the only copy off the device: an unreadable file writes nothing,
     *  rather than replacing it with an empty config. */
    @Test fun `an unreadable file before the first load writes nothing`() {
        disk = null
        assertEquals(BackupSteps.Outcome.Unreadable, run(inMemory = null))
        assertEquals(emptyList<String>(), written)
    }

    @Test fun `a failed write is reported, not taken for a backup`() {
        name = null
        assertEquals(BackupSteps.Outcome.WriteFailed, run(inMemory = "fav=memory/Memory"))
        assertEquals(listOf("fav=memory/Memory"), written)
    }
}
