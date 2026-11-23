package com.github.reygnn.verso_launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * home-cache.txt as the process keeps it, on manual queues: when it is read, what a window is
 * handed, when it is written. MainActivityAppsTest shows the same through the real window.
 */
class CachedFavoritesTest {

    private val ioQueue = ArrayDeque<Runnable>()
    private val mainQueue = ArrayDeque<() -> Unit>()
    private var onDisk = listOf(app("Alpha"))
    private var reads = 0
    private val writes = mutableListOf<List<AppEntry>>()

    private val cache = CachedFavorites(
        io = { ioQueue.addLast(it) },
        main = { mainQueue.addLast(it) },
        read = { reads++; onDisk },
        write = { writes += it },
    )

    private fun app(label: String, status: AppStatus = AppStatus.READY) =
        AppEntry(label, "com.${label.lowercase()}", "com.${label.lowercase()}.Main", label, status = status)

    private fun runIo() { while (ioQueue.isNotEmpty()) ioQueue.removeFirst().run() }
    private fun runMain() { while (mainQueue.isNotEmpty()) mainQueue.removeFirst()() }
    private fun runAll() { while (ioQueue.isNotEmpty() || mainQueue.isNotEmpty()) { runIo(); runMain() } }

    @Test fun `the file is read off main and handed back on it`() {
        val handed = mutableListOf<List<AppEntry>>()
        cache.load { handed += it }
        assertEquals("not read on main", 0, reads)
        runIo()
        assertEquals("not handed back on the IO thread", emptyList<List<AppEntry>>(), handed)
        runMain()
        assertEquals(listOf(listOf(app("Alpha"))), handed)
    }

    @Test fun `a second window is handed what the process knows, with no second read`() {
        cache.load {}
        runAll()
        val handed = mutableListOf<List<AppEntry>>()
        cache.load { handed += it }
        assertEquals("at once", listOf(listOf(app("Alpha"))), handed)
        assertEquals(1, reads)
        assertTrue(ioQueue.isEmpty())
    }

    /** A recreation before the first read landed: both windows wait for the one read. */
    @Test fun `two windows asking before the read lands share it`() {
        var first = 0
        var second = 0
        cache.load { first++ }
        cache.load { second++ }
        runAll()
        assertEquals(1, reads)
        assertEquals(1 to 1, first to second)
    }

    @Test fun `favorites that are the file's write nothing`() {
        cache.load {}
        runAll()
        cache.shown(listOf(app("Alpha")))
        runAll()
        assertEquals(emptyList<List<AppEntry>>(), writes)
    }

    @Test fun `changed favorites are written once, on the IO thread`() {
        cache.load {}
        runAll()
        val now = listOf(app("Bravo"), app("Alpha"))
        cache.shown(now)
        assertEquals("not on main", emptyList<List<AppEntry>>(), writes)
        cache.shown(now)
        runAll()
        assertEquals(listOf(now), writes)
    }

    /** The file keeps key and label; a favorite that was paused meanwhile is the same line. */
    @Test fun `only what the file keeps of a favorite counts as a change`() {
        cache.load {}
        runAll()
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
        cache.load {}
        runAll()
        cache.shown(listOf(app("Bravo"), app("Alpha")))
        runAll()
        assertEquals(1, writes.size)
    }

    /**
     * The first list came in before the read was handed back: what home showed then was written,
     * and the older read must not count as what the file holds, or the next refresh would see a
     * change that is none.
     */
    @Test fun `a read that lands after the first list does not replace what was written`() {
        val handed = mutableListOf<List<AppEntry>>()
        cache.load { handed += it }
        runIo()                                 // read, not yet handed back
        val shown = listOf(app("Bravo"))
        cache.shown(shown)
        runAll()
        assertEquals(listOf(shown), writes)
        assertEquals("the window is handed what home shows, not the stale file", listOf(shown), handed)
        cache.shown(shown)
        runAll()
        assertEquals("no second write", 1, writes.size)
    }

    @Test fun `no cache is an empty list, and the first favorites are written`() {
        onDisk = emptyList()
        val handed = mutableListOf<List<AppEntry>>()
        cache.load { handed += it }
        runAll()
        assertEquals(listOf(emptyList<AppEntry>()), handed)
        cache.shown(listOf(app("Alpha")))
        runAll()
        assertEquals(1, writes.size)
    }

    /** No favorites is a home page too: an empty list replaces a cache that had some. */
    @Test fun `favorites that are all gone empty the cache`() {
        cache.load {}
        runAll()
        cache.shown(emptyList())
        runAll()
        assertEquals(listOf(emptyList<AppEntry>()), writes)
    }
}
