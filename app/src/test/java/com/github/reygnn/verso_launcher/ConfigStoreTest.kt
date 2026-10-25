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

/** Real files on SharedPreferences' terms; the rename, delete and read seams fail on demand. */
class ConfigStoreTest {

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var dir: File
    private lateinit var store: ConfigStore
    private val logs = mutableListOf<String>()

    private val primary get() = File(dir, "verso.json")
    private val backup get() = File(dir, "verso.json.bak")

    @Before
    fun setUp() {
        dir = tmp.newFolder("files")
        store = ConfigStore(dir = dir, log = { msg, _ -> logs += msg })
    }

    private fun storeWith(
        rename: (File, File) -> Boolean = File::renameTo,
        delete: (File) -> Boolean = File::delete,
        readText: (File) -> String = { it.readText() },
        writeSynced: (File, ByteArray) -> Unit = { file, bytes -> file.writeBytes(bytes) },
    ) = ConfigStore(dir, { msg, _ -> logs += msg }, rename, delete, readText, writeSynced)

    private fun json(vararg favorites: String) = ConfigJson.serialize(cfg(*favorites))

    private fun files() = dir.list()!!.sorted()

    // ---- load ----------------------------------------------------------------

    @Test
    fun `a fresh install loads an empty config without logging`() {
        assertEquals(VersoConfig(), store.load())
        assertEquals(emptyList<String>(), logs)
    }

    @Test
    fun `isFresh only while there is neither verso json nor its bak`() {
        assertTrue(store.isFresh())
        backup.writeText(json("a/1"))
        assertFalse("a cut-off write is no fresh install", store.isFresh())
        backup.delete()
        store.write(json("a/1"))
        assertFalse(store.isFresh())
    }

    @Test
    fun `a torn verso json is null, logged, and stays until the next write`() {
        primary.writeText("{ torn")
        assertNull(store.load())
        assertTrue(logs.any { "unparseable" in it })
        assertFalse("a torn file is no fresh install", store.isFresh())
        assertEquals("{ torn", primary.readText())
    }

    @Test
    fun `a verso json that cannot be read is null, logged`() {
        primary.writeText(json("a/1"))
        val failing = storeWith(readText = { throw java.io.IOException("EIO") })
        assertNull(failing.load())
        assertTrue(logs.any { "unreadable" in it })
    }

    @Test
    fun `a directory where verso json should be is treated as absent`() {
        primary.mkdirs()
        assertEquals(VersoConfig(), store.load())
    }

    /** A write cut off after the move to .bak: the half-written verso.json is not trusted. */
    @Test
    fun `a bak left by a cut-off write is taken back over a half verso json`() {
        backup.writeText(json("old"))
        primary.writeText("{ half wri")
        assertEquals(listOf("old"), store.load()!!.favorites)
        assertEquals(listOf("verso.json"), files())
    }

    /** Read in place: deleted after the next write, the .bak would be gone with nothing beside it. */
    @Test
    fun `a bak that cannot be taken back is read where it is`() {
        backup.writeText(json("old"))
        primary.writeText("{ half wri")
        val refusing = storeWith(rename = { _, _ -> false })
        assertEquals(listOf("old"), refusing.load()!!.favorites)
        assertTrue(logs.any { "take back" in it })
    }

    /** Read in place and torn too: nothing complete is left, and the caller must hear of it. */
    @Test
    fun `a bak that cannot be taken back and is torn is null`() {
        backup.writeText("{ half wri")
        val refusing = storeWith(rename = { _, _ -> false })
        assertNull(refusing.load())
    }

    /** The parse of foreign files has limits; the app's own file has none of them. */
    @Test
    fun `an own config beyond the limits of a foreign file loads whole`() {
        val many = (0..ConfigJson.MAX_ENTRIES).map { "h/$it" }
        store.write(ConfigJson.serialize(VersoConfig(hidden = LinkedHashSet(many))))
        assertEquals(many.size, store.load()!!.hidden.size)
    }

    @Test
    fun `a bak alone is taken back`() {
        backup.writeText(json("old"))
        assertEquals(listOf("old"), store.load()!!.favorites)
        assertEquals(listOf("verso.json"), files())
    }

    // ---- write ---------------------------------------------------------------

    @Test
    fun `a write publishes the config and leaves no bak`() {
        assertTrue(store.write(json("f/1", "f/2")))
        assertEquals(listOf("verso.json"), files())
        assertEquals(listOf("f/1", "f/2"), store.load()!!.favorites)
    }

    @Test
    fun `a second write replaces the first`() {
        store.write(json("first"))
        store.write(json("second"))
        assertEquals(listOf("second"), store.load()!!.favorites)
        assertEquals(listOf("verso.json"), files())
    }

    /** The old file waits as .bak until the new one is complete: only then is the .bak deleted. */
    @Test
    fun `the old file is kept as bak until the new one is written`() {
        store.write(json("old"))
        var atDelete: Pair<String, String>? = null
        val watching = storeWith(delete = { f ->
            if (f == backup) atDelete = backup.readText() to primary.readText()
            f.delete()
        })
        assertTrue(watching.write(json("new")))
        assertEquals(json("old") to json("new"), atDelete)
    }

    @Test
    fun `a refused move to bak writes nothing and keeps the old file`() {
        store.write(json("old"))
        val refusing = storeWith(rename = { _, _ -> false })

        assertFalse(refusing.write(json("new")))
        assertEquals(listOf("old"), store.load()!!.favorites)
        assertTrue(logs.any { "save failed" in it })
    }

    /** The write itself fails once the old file is moved aside (here: a directory in its way). */
    @Test
    fun `a failed write leaves the bak and no half file, and the next load takes it back`() {
        store.write(json("old"))
        val blocked = storeWith(rename = { from, to -> from.renameTo(to).also { from.mkdirs() } })

        assertFalse(blocked.write(json("new")))
        assertEquals(listOf("verso.json.bak"), files())
        assertEquals(listOf("old"), store.load()!!.favorites)
    }

    /** A .bak already there is the last complete file: it is kept, the half verso.json goes. */
    @Test
    fun `with a bak already there the write keeps it and replaces verso json`() {
        backup.writeText(json("old"))
        primary.writeText("{ half wri")
        var bakAtDelete: String? = null
        val watching = storeWith(delete = { f ->
            if (f == backup) bakAtDelete = backup.readText()
            f.delete()
        })
        assertTrue(watching.write(json("new")))
        assertEquals("the .bak held the last complete file throughout", json("old"), bakAtDelete)
        assertEquals(listOf("verso.json"), files())
        assertEquals(listOf("new"), store.load()!!.favorites)
    }

    /** It stands, and the next load would take it back over the newer file: at least it is logged. */
    @Test
    fun `a bak that cannot be deleted after a write is logged`() {
        store.write(json("old"))
        val keeping = storeWith(delete = { f -> if (f == backup) false else f.delete() })
        assertTrue(keeping.write(json("new")))
        assertTrue(logs.any { "could not delete" in it })
    }

    @Test
    fun `a first write with no bak to delete logs nothing`() {
        assertTrue(store.write(json("first")))
        assertEquals(emptyList<String>(), logs)
    }

    /** The disk fills up halfway: a half file is written, then the write throws. */
    @Test
    fun `a write that fails after the file was opened leaves the bak and no half file`() {
        store.write(json("old"))
        val full = storeWith(writeSynced = { file, bytes ->
            file.writeBytes(bytes.copyOf(bytes.size / 2))
            throw java.io.IOException("ENOSPC")
        })

        assertFalse(full.write(json("new")))
        assertEquals(listOf("verso.json.bak"), files())
        assertEquals(listOf("old"), store.load()!!.favorites)
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
