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

    // ---- verso.json of earlier versions ------------------------------------------------

    @Test fun `no verso json is a fresh install`() {
        assertEquals(LegacyConfig.Read.Absent, LegacyConfig.read(dir, log))
    }

    @Test fun `verso json is read as it was, and left as it is`() {
        val config = VersoConfig(favorites = listOf("a/A"), hidden = setOf("b/B"), byUsage = true)
        File(dir, LegacyConfig.FILE).writeText(legacyJson(config))
        assertEquals(LegacyConfig.Read.Found(config), LegacyConfig.read(dir, log))
        assertEquals(setOf(LegacyConfig.FILE), dir.list()!!.toSet())
    }

    /** Up to 0.5.9 a write cut off left only the .bak: the last complete file. */
    @Test fun `a bak is read when verso json is missing, never over it`() {
        File(dir, "${LegacyConfig.FILE}.bak").writeText(legacyJson(cfg("old/Old")))
        assertEquals(LegacyConfig.Read.Found(cfg("old/Old")), LegacyConfig.read(dir, log))
        File(dir, LegacyConfig.FILE).writeText(legacyJson(cfg("new/New")))
        assertEquals(LegacyConfig.Read.Found(cfg("new/New")), LegacyConfig.read(dir, log))
    }

    @Test fun `a torn verso json is unreadable, logged and left as it is`() {
        File(dir, LegacyConfig.FILE).writeText("{\"favorites\":[\"to")
        assertEquals(LegacyConfig.Read.Unreadable, LegacyConfig.read(dir, log))
        assertTrue(logs.any { "unparseable" in it })
        assertEquals("{\"favorites\":[\"to", File(dir, LegacyConfig.FILE).readText())
        assertFalse(File(dir, "${LegacyConfig.FILE}.unreadable").exists())
    }
}
