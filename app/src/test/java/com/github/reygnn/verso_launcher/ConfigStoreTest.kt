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

/** verso.json on real files: written whole or not at all, an unreadable one set aside; the seams fail on demand. */
class ConfigStoreTest {

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var dir: File
    private lateinit var store: ConfigStore
    private val logs = mutableListOf<String>()
    private var dirSyncs = 0

    private val primary get() = File(dir, "verso.json")
    private val temp get() = File(dir, "verso.json.new")
    private val aside get() = File(dir, "verso.json.unreadable")
    private val legacyBackup get() = File(dir, "verso.json.bak")

    @Before
    fun setUp() {
        dir = tmp.newFolder("files")
        store = storeWith()
    }

    private fun storeWith(
        rename: (File, File) -> Boolean = File::renameTo,
        delete: (File) -> Boolean = File::delete,
        readText: (File) -> String = { it.readText() },
        writeSynced: (File, ByteArray) -> Unit = { file, bytes -> file.writeBytes(bytes) },
    ) = ConfigStore(dir, { msg, _ -> logs += msg }, { dirSyncs++ }, rename, delete, readText, writeSynced)

    private fun json(vararg favorites: String) = ConfigJson.serialize(cfg(*favorites))

    private fun files() = dir.list()!!.sorted()

    // ---- load ----------------------------------------------------------------

    @Test
    fun `a fresh install loads an empty config without logging`() {
        assertEquals(VersoConfig(), store.load())
        assertEquals(emptyList<String>(), logs)
    }

    @Test
    fun `isFresh only while nothing stands for verso json`() {
        assertTrue(store.isFresh())
        for (stand in listOf(primary, aside, legacyBackup)) {
            stand.writeText(json("a/1"))
            assertFalse("${stand.name} is no fresh install", store.isFresh())
            stand.delete()
        }
        temp.writeText(json("a/1"))
        assertTrue("a cut-off write of a first save is still a fresh install", store.isFresh())
    }

    @Test
    fun `a directory where verso json should be is treated as absent`() {
        primary.mkdirs()
        assertEquals(VersoConfig(), store.load())
    }

    /** Hard rule 4: it loads as empty, and the next write must not land on it. */
    @Test
    fun `a torn verso json is null, logged, and set aside whole`() {
        primary.writeText("{ torn")
        assertNull(store.load())
        assertTrue(logs.any { "unparseable" in it })
        assertEquals(listOf("verso.json.unreadable"), files())
        assertEquals("{ torn", aside.readText())
        assertFalse("set aside, it is no fresh install", store.isFresh())
    }

    @Test
    fun `a verso json that cannot be read is null, logged, and set aside`() {
        primary.writeText(json("a/1"))
        assertNull(storeWith(readText = { throw java.io.IOException("EIO") }).load())
        assertTrue(logs.any { "unreadable" in it })
        assertEquals(json("a/1"), aside.readText())
    }

    @Test
    fun `the write after a torn start leaves the torn file aside`() {
        primary.writeText("{ torn")
        store.load()
        assertTrue(store.write(json("new")))
        assertEquals(listOf("new"), store.load()!!.favorites)
        assertEquals("{ torn", aside.readText())
    }

    /** A read error that passed: "~restart" (a load with no verso.json) takes the file back. */
    @Test
    fun `a file set aside comes back once it reads again`() {
        primary.writeText(json("mine"))
        assertNull(storeWith(readText = { throw java.io.IOException("EIO") }).load())
        assertEquals(listOf("mine"), store.load()!!.favorites)
        assertEquals(listOf("verso.json"), files())
    }

    @Test
    fun `a file set aside that still cannot be read stays aside and is null again`() {
        primary.writeText("{ torn")
        store.load()
        assertNull(store.load())
        assertEquals(listOf("verso.json.unreadable"), files())
    }

    /** A rename the system refuses: the next write replaces it, as before 0.5.10 — and it is logged. */
    @Test
    fun `a torn verso json that cannot be set aside is logged`() {
        primary.writeText("{ torn")
        assertNull(storeWith(rename = { _, _ -> false }).load())
        assertTrue(logs.any { "could not set" in it })
    }

    @Test
    fun `a stray temp file from a cut-off write is removed, verso json loads`() {
        primary.writeText(json("old"))
        temp.writeText("{ half wri")
        assertEquals(listOf("old"), store.load()!!.favorites)
        assertEquals(listOf("verso.json"), files())
    }

    /** The parse of foreign files has limits; the app's own file has none of them. */
    @Test
    fun `an own config beyond the limits of a foreign file loads whole`() {
        val many = (0..ConfigJson.MAX_ENTRIES).map { "h/$it" }
        store.write(ConfigJson.serialize(VersoConfig(hidden = LinkedHashSet(many))))
        assertEquals(many.size, store.load()!!.hidden.size)
    }

    // ---- the .bak of versions up to 0.5.9 ------------------------------------------

    /** Their write moved verso.json to .bak first: a .bak left means that write was cut off. */
    @Test
    fun `a legacy bak is taken back over a half verso json`() {
        legacyBackup.writeText(json("old"))
        primary.writeText("{ half wri")
        assertEquals(listOf("old"), store.load()!!.favorites)
        assertEquals(listOf("verso.json"), files())
    }

    @Test
    fun `a legacy bak alone is taken back`() {
        legacyBackup.writeText(json("old"))
        assertEquals(listOf("old"), store.load()!!.favorites)
        assertEquals(listOf("verso.json"), files())
    }

    /** Rewritten as verso.json, so no later load can take an old .bak back over newer saves. */
    @Test
    fun `a legacy bak that cannot be renamed is written as verso json and removed`() {
        legacyBackup.writeText(json("old"))
        val refusing = storeWith(rename = { from, to -> if (from == legacyBackup) false else from.renameTo(to) })
        assertEquals(listOf("old"), refusing.load()!!.favorites)
        assertEquals(listOf("verso.json"), files())
        assertEquals(listOf("old"), store.load()!!.favorites)
    }

    /** Kept aside, not deleted: a read error may pass. Not left in place: it would come back over newer saves. */
    @Test
    fun `a legacy bak that cannot be read is set aside and verso json read`() {
        legacyBackup.writeText(json("old"))
        primary.writeText(json("current"))
        val failing = storeWith(readText = { if (it == legacyBackup) throw java.io.IOException("EIO") else it.readText() })
        assertEquals(listOf("current"), failing.load()!!.favorites)
        assertEquals(listOf("verso.json", "verso.json.unreadable"), files())
        assertEquals(json("old"), aside.readText())
        assertEquals(listOf("current"), store.load()!!.favorites)
    }

    /** One file aside: the rename replaces it, and a rename that fails leaves the earlier one. */
    @Test
    fun `a second unreadable file replaces the first aside, a failed rename keeps it`() {
        aside.writeText("{ first")
        primary.writeText("{ second")
        assertNull(storeWith(rename = { _, _ -> false }).load())
        assertEquals("{ first", aside.readText())
        assertNull(store.load())
        assertEquals("{ second", aside.readText())
    }

    // ---- write ---------------------------------------------------------------

    @Test
    fun `a write publishes the config, leaves no temp file, and syncs the directory`() {
        assertTrue(store.write(json("f/1", "f/2")))
        assertEquals(listOf("verso.json"), files())
        assertEquals(listOf("f/1", "f/2"), store.load()!!.favorites)
        assertEquals(1, dirSyncs)
    }

    @Test
    fun `a second write replaces the first`() {
        store.write(json("first"))
        store.write(json("second"))
        assertEquals(listOf("second"), store.load()!!.favorites)
        assertEquals(listOf("verso.json"), files())
    }

    /** The disk fills up halfway through the temp file: verso.json is never touched. */
    @Test
    fun `a write that fails while writing leaves verso json as it was and no temp file`() {
        store.write(json("old"))
        val full = storeWith(writeSynced = { file, bytes ->
            file.writeBytes(bytes.copyOf(bytes.size / 2))
            throw java.io.IOException("ENOSPC")
        })
        assertFalse(full.write(json("new")))
        assertEquals(listOf("verso.json"), files())
        assertEquals(listOf("old"), store.load()!!.favorites)
        assertTrue(logs.any { "save failed" in it })
    }

    @Test
    fun `a refused rename leaves verso json as it was and no temp file`() {
        store.write(json("old"))
        val syncsBefore = dirSyncs
        assertFalse(storeWith(rename = { _, _ -> false }).write(json("new")))
        assertEquals(listOf("verso.json"), files())
        assertEquals(listOf("old"), store.load()!!.favorites)
        assertEquals("no sync for a write that did not land", syncsBefore, dirSyncs)
    }

    /**
     * Killed at any point of the write (an Error, which nothing here catches, like a SIGKILL): the
     * next start loads the old config or the new one, never a torn file.
     */
    @Test
    fun `a write killed while it writes leaves the old config to load`() {
        store.write(json("old"))
        class Death : Error()
        val dying = storeWith(writeSynced = { file, bytes ->
            file.writeBytes(bytes.copyOf(bytes.size / 2))
            throw Death()
        })
        assertTrue(runCatching { dying.write(json("new")) }.exceptionOrNull() is Death)
        assertEquals(listOf("old"), storeWith().load()!!.favorites)
        assertEquals("the torn temp file is cleared", listOf("verso.json"), files())
    }

    @Test
    fun `a write killed after its rename leaves the new config to load`() {
        store.write(json("old"))
        class Death : Error()
        val dying = storeWith(rename = { from, to -> from.renameTo(to).also { throw Death() } })
        assertTrue(runCatching { dying.write(json("new")) }.exceptionOrNull() is Death)
        assertEquals(listOf("new"), storeWith().load()!!.favorites)
    }

    @Test
    fun `a failed write is reported rather than thrown`() {
        dir.deleteRecursively()
        assertFalse(store.write(json("x")))
    }

    @Test
    fun `a written config round-trips back through load`() {
        val original = VersoConfig(
            favorites = listOf("f/1", "f/2"),
            hidden = linkedSetOf("h/1"),
            names = linkedMapOf("f/1" to "Custom"),
            tags = linkedMapOf("f/2" to mutableListOf("work")),
        )
        store.write(ConfigJson.serialize(original))
        assertEquals(original, store.load())
    }
}
