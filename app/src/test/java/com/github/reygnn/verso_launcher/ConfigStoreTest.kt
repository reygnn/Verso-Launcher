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

/** Real files; only the directory fsync is stubbed, and the rename and read seams fail on demand. */

class ConfigStoreTest {

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var dir: File
    private lateinit var store: ConfigStore
    private val logs = mutableListOf<String>()
    private var dirSyncs = 0

    private val primary get() = File(dir, "verso.json")
    private val pending get() = File(dir, "verso.json.new")
    private val setAside get() = File(dir, "verso.json.unreadable")

    @Before
    fun setUp() {
        dir = tmp.newFolder("files")
        store = ConfigStore(dir = dir, log = { msg, _ -> logs += msg }, syncDir = { dirSyncs++ })
    }

    private fun storeWith(
        rename: (File, File) -> Boolean = File::renameTo,
        readText: (File) -> String = { it.readText() },
    ) = ConfigStore(dir = dir, log = { msg, _ -> logs += msg }, syncDir = { dirSyncs++ }, rename = rename, readText = readText)

    private fun json(vararg favorites: String) = ConfigJson.serialize(cfg(*favorites))

    private fun files() = dir.list()!!.sorted()

    // ---- load ----------------------------------------------------------------

    @Test
    fun `a fresh install loads an empty config without logging`() {
        assertTrue(store.load()!!.favorites.isEmpty())
        assertEquals(emptyList<String>(), logs)
    }

    @Test
    fun `isFresh only while there is no verso json`() {
        assertTrue(store.isFresh())
        store.write(json("a/1"))
        assertFalse(store.isFresh())
    }

    /** Torn is null, never an empty config: the next edit would save that over the file. */
    @Test
    fun `a torn verso json is null, not an empty config`() {
        primary.writeText("{ torn")
        assertNull(store.load())
        assertFalse("a torn file is no fresh install", store.isFresh())
    }

    @Test
    fun `a verso json that cannot be read is null`() {
        primary.writeText(json("a/1"))
        val failing = storeWith(readText = { throw java.io.IOException("EIO") })
        assertNull(failing.load())
    }

    @Test
    fun `a directory where verso json should be is treated as absent`() {
        primary.mkdirs()
        assertTrue(store.load()!!.favorites.isEmpty())
    }

    /** A crash between writing verso.json.new and the rename leaves the old file in force. */
    @Test
    fun `a new file left by a crash does not disturb the load`() {
        primary.writeText(json("old"))
        pending.writeText(json("half written"))
        assertEquals(listOf("old"), store.load()!!.favorites.toList())
    }

    @Test
    fun `the next write replaces a new file left by a crash`() {
        pending.writeText(json("half written"))
        assertTrue(store.write(json("next")))
        assertEquals(listOf("verso.json"), files())
    }

    // ---- write ---------------------------------------------------------------

    @Test
    fun `a write publishes the config, leaves no new file and syncs the directory`() {
        assertTrue(store.write(json("f/1", "f/2")))
        assertEquals(listOf("verso.json"), files())
        assertEquals(listOf("f/1", "f/2"), store.load()!!.favorites.toList())
        assertEquals(1, dirSyncs)
    }

    @Test
    fun `a second write replaces the first`() {
        store.write(json("first"))
        store.write(json("second"))
        assertEquals(listOf("second"), store.load()!!.favorites.toList())
        assertEquals(listOf("verso.json"), files())
    }

    @Test
    fun `a refused rename keeps the old file and leaves no new file`() {
        store.write(json("old"))
        val refusing = storeWith(rename = { _, _ -> false })

        assertFalse(refusing.write(json("new")))
        assertEquals("no .new left behind (checked before load, which would clear it)", listOf("verso.json"), files())
        assertEquals(listOf("old"), store.load()!!.favorites.toList())
        assertTrue(logs.any { "save failed" in it })
    }

    @Test
    fun `a failed write is reported rather than thrown`() {
        dir.deleteRecursively()
        assertFalse(store.write(json("x")))
    }

    @Test
    fun `a written config round-trips back through load`() {
        val original = VersoConfig(
            favorites = linkedSetOf("f/1", "f/2"),
            hidden = linkedSetOf("h/1"),
            names = linkedMapOf("f/1" to "Custom"),
            tags = linkedMapOf("f/2" to mutableListOf("work")),
        )
        store.write(ConfigJson.serialize(original))
        val loaded = store.load()!!
        assertTrue(original.sameContent(loaded))
    }

    // ---- setAsideUnreadable ----------------------------------------------------

    @Test
    fun `set aside renames the config, replaces an earlier copy, and the next load is fresh`() {
        setAside.writeText("earlier")
        primary.writeText("{ torn")

        assertTrue(store.setAsideUnreadable())
        assertEquals("{ torn", setAside.readText())
        assertTrue(store.isFresh())
        assertTrue(store.load()!!.favorites.isEmpty())
    }

    @Test
    fun `set aside exports the config's text, then removes it — nothing stays local`() {
        primary.writeText("{ torn")
        val exported = mutableListOf<String>()
        assertTrue(store.setAsideUnreadable { exported += it; true })
        assertEquals(listOf("{ torn"), exported)
        assertEquals(emptyList<String>(), files())
        assertTrue(store.isFresh())
    }

    @Test
    fun `a failed export renames the config aside locally`() {
        primary.writeText("{ torn")
        assertTrue(store.setAsideUnreadable { false })
        assertEquals("{ torn", setAside.readText())
        assertFalse(primary.exists())
    }

    @Test
    fun `bytes that cannot be read are not exported but renamed aside`() {
        primary.writeText(json("a/1"))
        var exports = 0
        val failing = storeWith(readText = { throw java.io.IOException("EIO") })
        assertTrue(failing.setAsideUnreadable { exports++; true })
        assertEquals(0, exports)
        assertTrue(setAside.isFile)
    }

    @Test
    fun `a refused set-aside leaves the config in place`() {
        primary.writeText("{ torn")
        val refusing = storeWith(rename = { _, _ -> false })

        assertFalse(refusing.setAsideUnreadable())
        assertTrue(primary.isFile)
        assertNull(store.load())
    }
}
