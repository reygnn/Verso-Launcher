package com.github.reygnn.verso_launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/** "%"'s order.txt on real files: written and read back. A read never throws; a failed write does, for [Guard]. */
class DrawerOrderTest {

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var dir: File
    private val logs = mutableListOf<String>()
    private val log: (String, Exception?) -> Unit = { msg, _ -> logs += msg }

    @Before fun setUp() {
        dir = tmp.newFolder("files")
    }

    @Test fun `the drawer order reads back, and is unset until written or when it makes no sense`() {
        assertNull(DrawerOrder.read(dir, log))
        DrawerOrder.write(dir, byUsage = true)
        assertEquals(true, DrawerOrder.read(dir, log))
        assertEquals("no temp file left", setOf(DrawerOrder.FILE), dir.list()!!.toSet())
        DrawerOrder.write(dir, byUsage = false)
        assertEquals(false, DrawerOrder.read(dir, log))
        File(dir, DrawerOrder.FILE).writeText("sideways")
        assertNull(DrawerOrder.read(dir, log))
    }

    /** A directory where the file should be: unset, never a throw. */
    @Test fun `a directory in place of order_txt is unset`() {
        File(dir, DrawerOrder.FILE).mkdirs()
        File(File(dir, DrawerOrder.FILE), "child").writeText("x")
        assertNull(DrawerOrder.read(dir, log))
        assertEquals(emptyList<String>(), logs)  // isFile: a directory is no file, nothing to say
    }

    /** The other half of [writeDisposable]: the test above only sees a rename that works. */
    @Test fun `a rename that fails throws for Guard to log and leaves no temp file behind`() {
        // A non-empty directory where the file goes: no rename can replace it.
        File(dir, DrawerOrder.FILE).apply { mkdir() }.resolve("keep").writeText("x")
        assertThrows(IOException::class.java) { DrawerOrder.write(dir, byUsage = true) }
        assertEquals("only what was there", setOf(DrawerOrder.FILE), dir.list()!!.toSet())
    }
}
