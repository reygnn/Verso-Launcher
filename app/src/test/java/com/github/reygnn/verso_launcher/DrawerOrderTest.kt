package com.github.reygnn.verso_launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** "%"'s order.txt on real files: written, read back, never thrown from. */
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
}
