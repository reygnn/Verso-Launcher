package com.github.reygnn.verso_launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The user's config file at its fixed place, on real files: none is the defaults, an empty one is a
 * config, a bad one a log line and never a throw (it is read on main at every start).
 */
class ConfigFileTest {

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var dir: File
    private val logs = mutableListOf<String>()
    private val log: (String, Exception?) -> Unit = { msg, _ -> logs += msg }

    @Before fun setUp() {
        dir = tmp.newFolder("external")
    }

    private fun file(text: String) = File(dir, ConfigFile.NAME).writeText(text)

    @Test fun `no folder or no file is none, said nowhere`() {
        assertNull(ConfigFile.read(null, log))
        assertNull(ConfigFile.read(dir, log))
        assertEquals(emptyList<String>(), logs)
    }

    @Test fun `the file's lines are the config`() {
        file("# mine\nfav Gmail\nhide com.samsung.android.bixby.agent\nname Maps = Karte\ntag Firefox = web\n")
        val read = ConfigFile.read(dir, log)
        assertEquals(ConfigText.parse(File(dir, ConfigFile.NAME).readText()), read)
        assertEquals(listOf("Gmail"), read?.favorites)
        assertEquals(emptyList<String>(), logs)
    }

    /** Hard rule 10: an empty file is the user's config, so no defaults are shown over it. */
    @Test fun `an empty file is a config, not none`() {
        file("")
        assertEquals(ConfigText(), ConfigFile.read(dir, log))
    }

    @Test fun `a line that is no entry is skipped and logged, the rest counts`() {
        file("fav Gmail\nfavourite Zulu\nhide")
        val read = ConfigFile.read(dir, log)
        assertEquals(listOf("Gmail"), read?.favorites)
        assertEquals(listOf(2, 3), read?.badLines)
        assertEquals(1, logs.size)
        assertTrue(logs.single(), logs.single().endsWith("2, 3"))
    }

    /** Hard rule 7: past the cap the file is refused whole, never cut. */
    @Test fun `a file past the cap is none and logged`() {
        File(dir, ConfigFile.NAME).writeBytes(ByteArray(1024 * 1024 + 1) { 'a'.code.toByte() })
        assertNull(ConfigFile.read(dir, log))
        assertEquals(1, logs.size)
    }

    /** A directory where the file should be: none, never a throw. */
    @Test fun `a directory in place of the file is none`() {
        File(dir, ConfigFile.NAME).mkdirs()
        assertNull(ConfigFile.read(dir, log))
    }
}
