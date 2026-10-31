package com.github.reygnn.verso_launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RecentsLogTest {

    @get:Rule val folder = TemporaryFolder()

    private val noLog: (String, Exception?) -> Unit = { _, _ -> }

    @Test fun `launches made before the read come first, de-duplicated and capped`() {
        assertEquals(listOf("c", "a", "b"), RecentsLog.merged(listOf("a", "b", "c"), listOf("c", "a"), 8))
        assertEquals(listOf("c", "a"), RecentsLog.merged(listOf("a", "b"), listOf("c"), 2))
    }

    @Test fun `parse skips blank lines and duplicates and keeps at most the limit`() {
        assertEquals(listOf("a/A", "b/B#10"), RecentsLog.parse("a/A\n\nb/B#10\na/A\n", 8))
        assertEquals(listOf("a", "b"), RecentsLog.parse("a\nb\nc\n", 2))
    }

    @Test fun `written and read back unchanged, newest first`() {
        val dir = folder.root
        RecentsLog.write(dir, listOf("b/B", "a/A#10"))
        assertEquals(listOf("b/B", "a/A#10"), RecentsLog.read(dir, 8, noLog))
        assertEquals(false, File(dir, "${RecentsLog.FILE}.tmp").exists())
    }

    @Test fun `a file of exactly the cap is read`() {
        val dir = folder.root
        val entry = "a/A\n"
        File(dir, RecentsLog.FILE).writeText(entry + "\n".repeat(RecentsLog.MAX_BYTES.toInt() - entry.length))
        assertEquals(listOf("a/A"), RecentsLog.read(dir, 8, noLog))
    }

    @Test fun `no file, or one too large, is an empty list`() {
        val dir = folder.root
        assertEquals(emptyList<String>(), RecentsLog.read(dir, 8, noLog))
        File(dir, RecentsLog.FILE).writeText("x".repeat(RecentsLog.MAX_BYTES.toInt() + 1))
        assertEquals(emptyList<String>(), RecentsLog.read(dir, 8, noLog))
    }

    @Test fun `an unreadable file is null - it must not be written over`() {
        val dir = folder.root
        val file = File(dir, RecentsLog.FILE).apply { writeText("a/A\n") }
        file.setReadable(false)
        // Root reads it all the same: then there is no unreadable file to test, a skip, not a failure.
        assumeFalse("running as root", file.canRead())
        assertNull(RecentsLog.read(dir, 8, noLog))
    }
}
