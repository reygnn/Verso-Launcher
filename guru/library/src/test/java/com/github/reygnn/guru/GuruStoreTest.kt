package com.github.reygnn.guru

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Locale

/** Real files; only the directory fsync is stubbed, and the rename and the clock are seams. */
class GuruStoreTest {

    @get:Rule val tmp = TemporaryFolder()
    @get:Rule val locale = GuruLocaleRule()

    private lateinit var dir: File
    private val logs = mutableListOf<String>()
    private var dirSyncs = 0
    private var clock = 1_000_000L

    private fun store(rename: (File, File) -> Boolean = File::renameTo, keep: Int = GuruStore.KEEP) = GuruStore(
        dir = dir,
        log = { msg, _ -> logs += msg },
        syncDir = { dirSyncs++ },
        rename = rename,
        now = { clock },
        keep = keep,
    )

    private fun files() = dir.list().orEmpty().sorted()

    private fun r(id: String, at: Long) = report(id = id, crashedAt = at)

    @Before
    fun setUp() {
        // Not created: the first write must make it (filesDir/guru does not exist on a fresh install).
        dir = File(tmp.root, "guru")
    }

    // ---- write / pending ------------------------------------------------------

    @Test
    fun `a written report is pending, its directory made, the rename synced`() {
        assertTrue(store().write(r("a", 1)))
        assertEquals(listOf(r("a", 1)), store().pending())
        assertEquals(1, dirSyncs)
        assertEquals(listOf("guru-0000000000001-a.txt"), files())
    }

    /** Arabic formats %d with Arabic-Indic digits: the name would no longer match and the report would vanish. */
    @Test
    fun `report names are ASCII in any default locale`() {
        Locale.setDefault(Locale.forLanguageTag("ar-EG"))
        store().write(r("a", 1))
        assertEquals(listOf("guru-0000000000001-a.txt"), files())
        assertEquals(listOf("a"), store().pending().map { it.id })
    }

    @Test
    fun `pending is newest first`() {
        val s = store()
        s.write(r("old", 1))
        s.write(r("new", 3))
        s.write(r("mid", 2))
        assertEquals(listOf("new", "mid", "old"), s.pending().map { it.id })
    }

    /** Both sides of keep: keep reports stay, one more drops the oldest. */
    @Test
    fun `a crash loop keeps only the newest`() {
        val s = store(keep = 2)
        s.write(r("a", 1))
        s.write(r("b", 2))
        assertEquals(listOf("b", "a"), s.pending().map { it.id })
        s.write(r("c", 3))
        assertEquals(listOf("c", "b"), s.pending().map { it.id })
    }

    @Test
    fun `a refused rename publishes nothing and leaves no new file`() {
        store().write(r("a", 1))
        assertFalse(store(rename = { _, _ -> false }).write(r("b", 2)))
        assertEquals(listOf("guru-0000000000001-a.txt"), files())
        assertTrue(logs.any { "failed" in it })
    }

    @Test
    fun `an id that is no file name part is refused`() {
        assertFalse(store().write(r("../evil", 1)))
        assertEquals(emptyList<String>(), files())
    }

    @Test
    fun `an unreadable report is dropped and logged`() {
        store().write(r("a", 1))
        File(dir, "guru-0000000000002-b.txt").writeText("guru 1\nid=b\n")
        assertEquals(listOf("a"), store().pending().map { it.id })
        assertEquals(listOf("guru-0000000000001-a.txt"), files())
        assertTrue(logs.any { "guru-0000000000002-b.txt" in it })
    }

    @Test
    fun `a file that is no report name is left alone`() {
        store().write(r("a", 1))
        File(dir, "notes.txt").writeText("mine")
        assertEquals(1, store().pending().size)
        assertTrue(File(dir, "notes.txt").exists())
    }

    /** Both sides of the leftover age: a young .new may be a write running right now. */
    @Test
    fun `a leftover new file is swept only once it is old`() {
        dir.mkdirs()
        val young = File(dir, "guru-0000000000001-a.txt.new").apply { writeText("x"); setLastModified(clock - GuruStore.LEFTOVER_AGE_MS) }
        val old = File(dir, "guru-0000000000002-b.txt.new").apply { writeText("x"); setLastModified(clock - GuruStore.LEFTOVER_AGE_MS - 1_000) }
        store().pending()
        assertTrue(young.exists())
        assertFalse(old.exists())
    }

    @Test
    fun `remove takes exactly the given reports`() {
        val s = store()
        s.write(r("a", 1))
        s.write(r("b", 2))
        s.write(r("c", 3))
        s.remove(listOf("a", "c", "unknown"))
        assertEquals(listOf("b"), s.pending().map { it.id })
    }

    /** By the whole id, never a part of it: "", a prefix or a suffix removes nothing. */
    @Test
    fun `remove matches whole ids only`() {
        val s = store()
        s.write(r("abc-1", 1))

        s.remove(listOf("", "a", "abc", "1", "-1", "abc-"))

        assertEquals(listOf("abc-1"), s.pending().map { it.id })
    }

    @Test
    fun `nothing written, nothing pending`() {
        assertEquals(emptyList<GuruReport>(), store().pending())
    }

    // ---- exit mark --------------------------------------------------------------

    @Test
    fun `the exit mark is null until set, then survives a new store`() {
        assertNull(store().exitsSeen())
        assertTrue(store().markExitsSeen(1_761_200_000_123))
        assertEquals(1_761_200_000_123L, store().exitsSeen())
    }

    @Test
    fun `an unreadable exit mark counts as none`() {
        dir.mkdirs()
        File(dir, GuruStore.EXITS_SEEN).writeText("soon")
        assertNull(store().exitsSeen())
    }

    // ---- alert mark ---------------------------------------------------------------

    @Test
    fun `an alert mark is kept until its own token clears it`() {
        val s = store()
        assertTrue(s.markAlertUp("t1"))
        assertTrue(s.markAlertUp("t2"))

        s.clearAlertUp("t1")
        assertEquals("t2", store().alertUp())

        s.clearAlertUp("t2")
        assertNull(store().alertUp())
    }

    @Test
    fun `taking the alert mark tells once`() {
        val s = store()
        assertFalse(s.takeAlertUp())
        s.markAlertUp("t1")

        assertTrue(store().takeAlertUp())
        assertFalse(store().takeAlertUp())
    }

    // ---- installation id -----------------------------------------------------------

    @Test
    fun `the installation id is made once and kept`() {
        var made = 0
        val first = store().installationId { made++; "inst-1" }
        val again = store().installationId { made++; "inst-2" }
        assertEquals("inst-1", first)
        assertEquals("inst-1", again)
        assertEquals(1, made)
    }

    @Test
    fun `a damaged installation id is made anew`() {
        dir.mkdirs()
        File(dir, GuruStore.INSTALLATION).writeText("not/an id")
        assertEquals("inst-2", store().installationId { "inst-2" })
        assertEquals("inst-2", File(dir, GuruStore.INSTALLATION).readText())
    }

    /** It cannot be stored: still an id, so the report still goes through. */
    @Test
    fun `an installation id that cannot be stored is still given`() {
        assertEquals("inst-3", store(rename = { _, _ -> false }).installationId { "inst-3" })
        assertFalse(File(dir, GuruStore.INSTALLATION).exists())
    }

    @Test
    fun `the saved crash times keep the newest, distinct`() {
        val s = store(keep = 3)
        listOf(5L, 1L, 4L, 4L, 2L).forEach { s.markCrashSaved(it) }

        assertEquals(listOf(5L, 4L, 2L), store(keep = 3).crashesSaved())
    }

    @Test
    fun `no saved crash times, or unreadable ones, count as none`() {
        assertTrue(store().crashesSaved().isEmpty())
        dir.mkdirs()
        File(dir, GuruStore.CRASHES_SAVED).writeText("x\n\n42")

        assertEquals(listOf(42L), store().crashesSaved())
    }
}
