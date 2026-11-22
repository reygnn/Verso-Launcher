package com.github.reygnn.verso_launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/**
 * The read and the write the app's small files share. An unreadable file needs chmod, which root
 * ignores (the tests of the four files skip it there); a parse that throws takes the same way out
 * and runs everywhere.
 */
class DisposableFileTest {

    @get:Rule val folder = TemporaryFolder()

    private val logs = mutableListOf<String>()
    private val log: (String, Exception?) -> Unit = { msg, _ -> logs += msg }

    private fun read(dir: File, maxBytes: Long = 100L, parse: (String) -> String = { it }): String? =
        readDisposable(dir, "f.txt", maxBytes, absent = "absent", log = log, parse = parse)

    @Test fun `no file is the absent value, and no log line`() {
        assertEquals("absent", read(folder.root))
        assertEquals(emptyList<String>(), logs)
    }

    @Test fun `a folder of that name is no file`() {
        File(folder.root, "f.txt").mkdir()
        assertEquals("absent", read(folder.root))
    }

    @Test fun `a file is read up to the cap and absent one byte past it`() {
        val file = File(folder.root, "f.txt")
        file.writeText("x".repeat(100))
        assertEquals("x".repeat(100), read(folder.root, maxBytes = 100L))
        file.writeText("x".repeat(101))
        assertEquals("absent", read(folder.root, maxBytes = 100L))
        assertEquals("too large is not unreadable", emptyList<String>(), logs)
    }

    @Test fun `a parse that throws is null and logged, never a throw`() {
        File(folder.root, "f.txt").writeText("torn")
        assertNull(read(folder.root) { error("cannot parse $it") })
        assertEquals(listOf("f.txt unreadable"), logs)
    }

    @Test fun `a write replaces the file and leaves no temp file`() {
        writeDisposable(folder.root, "f.txt", "one")
        writeDisposable(folder.root, "f.txt", "two")
        assertEquals("two", read(folder.root))
        assertEquals(setOf("f.txt"), folder.root.list()!!.toSet())
    }

    @Test fun `a rename that fails throws and takes its temp file along`() {
        File(folder.root, "f.txt").apply { mkdir() }.resolve("keep").writeText("x")
        assertThrows(IOException::class.java) { writeDisposable(folder.root, "f.txt", "one") }
        assertEquals(setOf("f.txt"), folder.root.list()!!.toSet())
    }
}
