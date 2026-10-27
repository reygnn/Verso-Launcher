package com.github.reygnn.verso_launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The one undo point on real files: written whole or not at all, read back as it was. */
class UndoSlotTest {

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var dir: File
    private lateinit var slot: UndoSlot
    private val logs = mutableListOf<String>()

    @Before
    fun setUp() {
        dir = tmp.newFolder("files")
        slot = UndoSlot(dir, { msg, _ -> logs += msg }, syncDir = {})
    }

    private val file get() = File(dir, UndoSlot.FILE)

    @Test fun `nothing kept yet is nothing to undo, and says nothing`() {
        assertNull(slot.read())
        assertEquals(emptyList<String>(), logs)
    }

    @Test fun `a kept config reads back as it was`() {
        val kept = cfg("a/A", "b/B")
        assertTrue(slot.write(ConfigJson.serialize(kept)))
        assertEquals(kept, slot.read())
        assertEquals("no temp file left", listOf(UndoSlot.FILE), dir.list()!!.sorted())
    }

    /** One slot: "~undo" swaps it, so only the last replaced config is there. */
    @Test fun `a second write replaces the first`() {
        slot.write(ConfigJson.serialize(cfg("first/F")))
        slot.write(ConfigJson.serialize(cfg("second/S")))
        assertEquals(cfg("second/S"), slot.read())
    }

    @Test fun `a torn slot is nothing to undo, and logged`() {
        file.writeText("{\"favorites\":[\"to")
        assertNull(slot.read())
        assertTrue(logs.any { "unparseable" in it })
    }

    /** A directory in the way: the write fails, the caller refuses the restore (no undo point, no restore). */
    @Test fun `a write that cannot land is false, leaves no temp file, and is logged`() {
        file.mkdirs()
        File(file, "child").writeText("x")
        assertFalse(slot.write(ConfigJson.serialize(cfg("a/A"))))
        assertFalse(File(dir, "${UndoSlot.FILE}.new").exists())
        assertTrue(logs.isNotEmpty())
    }

    @Test fun `a missing directory is false, never a throw`() {
        dir.deleteRecursively()
        assertFalse(slot.write(ConfigJson.serialize(cfg("a/A"))))
    }
}
