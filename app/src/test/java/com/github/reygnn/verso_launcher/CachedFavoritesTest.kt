package com.github.reygnn.verso_launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * home-cache.txt as the process keeps it, on manual queues: when it is read, what the live window
 * is handed, when it is written. MainActivityAppsTest shows the same through the real window.
 */
class CachedFavoritesTest {

    private val ioQueue = ArrayDeque<Runnable>()
    private val mainQueue = ArrayDeque<() -> Unit>()
    private var onDisk = listOf(app("Alpha"))
    private var reads = 0

    /** How many of the next reads throw, as an IO error past HomeCache.read's own catch would. */
    private var failingReads = 0
    private val writes = mutableListOf<List<AppEntry>>()

    /** What the live window was handed, read by read. */
    private val handed = mutableListOf<List<AppEntry>>()

    private val cache = CachedFavorites(
        // Behind Guard, as VersoApp.submitIo runs every IO task.
        io = { ioQueue.addLast(Guard.wrap("test IO", { _, _ -> }, it)) },
        main = { mainQueue.addLast(it) },
        read = {
            reads++
            if (failingReads > 0) {
                failingReads--
                error("unreadable")
            }
            onDisk
        },
        write = { writes += it },
        onRead = { handed += it },
    )

    private fun app(label: String, status: AppStatus = AppStatus.READY) =
        AppEntry(label, "com.${label.lowercase()}", "com.${label.lowercase()}.Main", label, status = status)

    private fun runIo() { while (ioQueue.isNotEmpty()) ioQueue.removeFirst().run() }
    private fun runMain() { while (mainQueue.isNotEmpty()) mainQueue.removeFirst()() }
    private fun runAll() { while (ioQueue.isNotEmpty() || mainQueue.isNotEmpty()) { runIo(); runMain() } }

    /** The file read and known, as after any cold start. */
    private fun loaded() {
        cache.load()
        runAll()
        handed.clear()
    }

    // ---- the read ---------------------------------------------------------------------------

    @Test fun `the first window is told nothing yet, and the read is handed back on main`() {
        assertNull(cache.load())
        assertEquals("not read on main", 0, reads)
        runIo()
        assertEquals("not handed back on the IO thread", emptyList<List<AppEntry>>(), handed)
        runMain()
        assertEquals(listOf(listOf(app("Alpha"))), handed)
    }

    @Test fun `a later window is told what the process knows, with no second read`() {
        loaded()
        assertEquals(listOf(app("Alpha")), cache.load())
        assertEquals(1, reads)
        assertTrue(ioQueue.isEmpty())
        assertEquals("nothing handed back: it was the answer", emptyList<List<AppEntry>>(), handed)
    }

    /** A recreation before the read landed: the second window starts no read of its own, and one hand-back serves the live one. */
    @Test fun `two windows asking before the read lands share it`() {
        assertNull(cache.load())
        assertNull(cache.load())
        runAll()
        assertEquals(1, reads)
        assertEquals(1, handed.size)
    }

    @Test fun `no cache is an empty list`() {
        onDisk = emptyList()
        cache.load()
        runAll()
        assertEquals(listOf(emptyList<AppEntry>()), handed)
        assertEquals(emptyList<AppEntry>(), cache.load())
    }

    // ---- the write, the file known ----------------------------------------------------------

    @Test fun `favorites that are the file's write nothing`() {
        loaded()
        cache.shown(listOf(app("Alpha")))
        runAll()
        assertEquals(emptyList<List<AppEntry>>(), writes)
    }

    @Test fun `changed favorites are written once, on the IO thread`() {
        loaded()
        val now = listOf(app("Bravo"), app("Alpha"))
        cache.shown(now)
        assertEquals("not on main", emptyList<List<AppEntry>>(), writes)
        cache.shown(now)
        runAll()
        assertEquals(listOf(now), writes)
        assertEquals("a later window is told what was written", now, cache.load())
    }

    /** The file keeps key and label; a favorite that was paused meanwhile is the same line. */
    @Test fun `only what the file keeps of a favorite counts as a change`() {
        loaded()
        cache.shown(listOf(app("Alpha", AppStatus.PAUSED)))
        cache.shown(listOf(app("Alpha").withLabel("Alpha")))
        runAll()
        assertEquals(emptyList<List<AppEntry>>(), writes)
        cache.shown(listOf(app("Alpha").withLabel("Renamed")))
        runAll()
        assertEquals(1, writes.size)
    }

    /** The order ranks the home page: the same favorites in another order are another cache. */
    @Test fun `the same favorites in another order are written`() {
        onDisk = listOf(app("Alpha"), app("Bravo"))
        loaded()
        cache.shown(listOf(app("Bravo"), app("Alpha")))
        runAll()
        assertEquals(1, writes.size)
    }

    /** No favorites is a home page too: an empty list replaces a cache that had some. */
    @Test fun `favorites that are all gone empty the cache`() {
        loaded()
        cache.shown(emptyList())
        runAll()
        assertEquals(listOf(emptyList<AppEntry>()), writes)
    }

    @Test fun `no favorites and no cache write no empty file`() {
        onDisk = emptyList()
        loaded()
        cache.shown(emptyList())
        runAll()
        assertEquals(emptyList<List<AppEntry>>(), writes)
    }

    // ---- the first list faster than the read ----------------------------------------------

    /**
     * Shown before the read is back, favorites are not written at once: the file may hold just
     * them. They are decided once it is known. Written blindly, a cold start whose list beat the
     * read would replace the file with itself, and an empty home would leave an empty file.
     */
    @Test fun `favorites shown before the read is back wait for it, and the file's own are not written`() {
        cache.load()
        cache.shown(listOf(app("Alpha")))
        assertEquals("nothing queued but the read", 1, ioQueue.size)
        runAll()
        assertEquals(emptyList<List<AppEntry>>(), writes)
    }

    @Test fun `no favorites shown before the read of no cache write no empty file`() {
        onDisk = emptyList()
        cache.load()
        cache.shown(emptyList())
        runAll()
        assertEquals(emptyList<List<AppEntry>>(), writes)
    }

    @Test fun `favorites shown before the read is back are written if the file holds others`() {
        cache.load()
        runIo()                                 // read, not yet handed back
        val shown = listOf(app("Bravo"))
        cache.shown(shown)
        runAll()
        assertEquals(listOf(shown), writes)
        assertEquals("the live window is handed what home shows, not the stale file", listOf(shown), handed)
        cache.shown(shown)
        runAll()
        assertEquals("no second write", 1, writes.size)
    }

    /** Only the newest counts: a refresh that changed the favorites again before the read came back. */
    @Test fun `of several lists shown before the read the last one is decided`() {
        cache.load()
        cache.shown(listOf(app("Bravo")))
        cache.shown(listOf(app("Alpha")))
        runAll()
        assertEquals("the last is the file's own", emptyList<List<AppEntry>>(), writes)
    }

    /** No window asked first (none does today): the favorites still reach the file, through a read of their own. */
    @Test fun `favorites shown with no read asked for start it`() {
        cache.shown(listOf(app("Bravo")))
        runAll()
        assertEquals(1, reads)
        assertEquals(listOf(listOf(app("Bravo"))), writes)
    }

    // ---- a read that fails ------------------------------------------------------------------

    /** The file stays unknown: no window is handed anything, and the next window's load reads again. */
    @Test fun `after a failed read the next load reads again`() {
        failingReads = 1
        assertNull(cache.load())
        runAll()
        assertEquals(emptyList<List<AppEntry>>(), handed)
        assertNull("still unknown", cache.load())
        runAll()
        assertEquals(2, reads)
        assertEquals(listOf(listOf(app("Alpha"))), handed)
    }

    /**
     * Favorites waiting for a read that failed are written as they are, as if no file were known,
     * and then count as what it holds: neither held back nor written again.
     */
    @Test fun `favorites waiting for a failed read are written, and then known`() {
        failingReads = 1
        cache.load()
        val shown = listOf(app("Bravo"))
        cache.shown(shown)
        runAll()
        assertEquals(listOf(shown), writes)
        assertEquals("the live window is handed what was written", listOf(shown), handed)
        assertEquals(shown, cache.load())
        cache.shown(shown)
        runAll()
        assertEquals("no second read, no second write", 1 to 1, reads to writes.size)
    }

    /** A failed read leaves nothing behind that holds back the favorites shown after it. */
    @Test fun `favorites shown after a failed read start a read of their own`() {
        failingReads = 1
        cache.load()
        runAll()
        cache.shown(listOf(app("Bravo")))
        runAll()
        assertEquals(2, reads)
        assertEquals(listOf(listOf(app("Bravo"))), writes)
    }
}
