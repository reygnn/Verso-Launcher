package com.github.reygnn.verso_launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The app's own small files around the user's config, on real files: written, read back, never thrown from. */
class ConfigLinkTest {

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var dir: File
    private val logs = mutableListOf<String>()
    private val log: (String, Exception?) -> Unit = { msg, _ -> logs += msg }

    @Before fun setUp() {
        dir = tmp.newFolder("files")
    }

    @Test fun `no link is none, a written one reads back without its line break`() {
        assertNull(ConfigLink.readLink(dir, log))
        ConfigLink.writeLink(dir, "content://x/doc%2Fverso.txt")
        assertEquals("content://x/doc%2Fverso.txt", ConfigLink.readLink(dir, log))
        assertEquals("no temp file left", setOf(ConfigLink.LINK), dir.list()!!.toSet())
    }

    @Test fun `a blank link file is no link`() {
        File(dir, ConfigLink.LINK).writeText("  \n")
        assertNull(ConfigLink.readLink(dir, log))
    }

    @Test fun `the copy of the file's text reads back as it was`() {
        assertNull(ConfigLink.readCache(dir, log))
        val text = "# mine\nfav Gmail\n\nname Maps = Karte\n"
        ConfigLink.writeCache(dir, text)
        assertEquals(text, ConfigLink.readCache(dir, log))
    }

    /** A directory where the file should be: none, never a throw. */
    @Test fun `a directory in place of the link or the copy is none`() {
        File(dir, ConfigLink.LINK).mkdirs()
        File(dir, ConfigLink.CACHE).mkdirs()
        File(File(dir, ConfigLink.LINK), "child").writeText("x")
        File(File(dir, ConfigLink.CACHE), "child").writeText("x")
        assertNull(ConfigLink.readLink(dir, log))
        assertNull(ConfigLink.readCache(dir, log))
        assertEquals(emptyList<String>(), logs)  // isFile: a directory is no file, nothing to say
    }

    @Test fun `the drawer order reads back, and is unset until written or when it makes no sense`() {
        assertNull(DrawerOrder.read(dir, log))
        DrawerOrder.write(dir, byUsage = true)
        assertEquals(true, DrawerOrder.read(dir, log))
        DrawerOrder.write(dir, byUsage = false)
        assertEquals(false, DrawerOrder.read(dir, log))
        File(dir, DrawerOrder.FILE).writeText("sideways")
        assertNull(DrawerOrder.read(dir, log))
    }
}
