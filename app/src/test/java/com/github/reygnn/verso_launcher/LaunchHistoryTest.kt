package com.github.reygnn.verso_launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The launches on their own, without a LauncherState around them: what is read, what a merge
 * brings and when a file is written. How a refresh carries the files is LauncherStateTest's.
 */
class LaunchHistoryTest {

    private val io = ArrayDeque<Runnable>()
    private var recentsOnDisk: List<String>? = emptyList()
    private var usageOnDisk: Map<String, Usage>? = emptyMap()
    private var reads = 0
    private val recentsWrites = mutableListOf<List<String>>()
    private val usageWrites = mutableListOf<Pair<Map<String, Usage>, Long>>()
    private var clock = 1000L

    private val history = testLaunchHistory(
        io = { io.addLast(it) },
        loadRecents = { reads++; recentsOnDisk },
        writeRecents = { recentsWrites += it },
        loadUsage = { reads++; usageOnDisk },
        writeUsage = { log, at -> usageWrites += log to at },
        now = { clock },
    )

    private fun runIo() { while (io.isNotEmpty()) io.removeFirst().run() }

    /** One refresh's worth: asked, read, merged. */
    private fun load(): Boolean = history.merge(history.read(history.unread()))

    @Test fun `both files are unread at first, and a read asks only for those`() {
        assertTrue(history.unread().recents)
        assertTrue(history.unread().usage)
        load()
        assertEquals(2, reads)
        val after = history.unread()
        assertFalse(after.recents)
        assertFalse(after.usage)
        val read = history.read(after)
        assertEquals("nothing left to read", 2, reads)
        assertNull(read.recents)
        assertNull(read.usage)
    }

    @Test fun `a merge without launches takes the files as they are and writes nothing`() {
        recentsOnDisk = listOf("a", "b")
        usageOnDisk = mapOf("a" to Usage(2.0, 1000L))
        assertTrue("the ? list changed", load())
        assertEquals(listOf("a", "b"), history.recents)
        assertEquals(mapOf("a" to Usage(2.0, 1000L)), history.usage)
        assertTrue(io.isEmpty())
    }

    @Test fun `an empty file changes no list`() {
        assertFalse(load())
        assertTrue(history.recentsLoaded)
        assertTrue(history.usageLoaded)
    }

    @Test fun `a launch before the read is kept in memory, then merged in and written`() {
        recentsOnDisk = listOf("a")
        usageOnDisk = mapOf("b" to Usage(1.0, 1000L))
        history.record("b")
        assertEquals(listOf("b"), history.recents)
        assertTrue("no write over a file not read yet", io.isEmpty())
        assertTrue(load())
        runIo()
        assertEquals(listOf("b", "a"), history.recents)
        assertEquals(listOf(listOf("b", "a")), recentsWrites)
        assertEquals(listOf(mapOf("b" to Usage(2.0, 1000L)) to 1000L), usageWrites)
    }

    @Test fun `once a file is read every launch is written, with the clock of the launch`() {
        load()
        clock = 5000L
        history.record("a")
        runIo()
        assertEquals(listOf(listOf("a")), recentsWrites)
        assertEquals(listOf(mapOf("a" to Usage(1.0, 5000L)) to 5000L), usageWrites)
    }

    @Test fun `an unreadable file stays unread and unwritten, each on its own`() {
        recentsOnDisk = null
        assertFalse(load())
        assertFalse(history.recentsLoaded)
        assertTrue(history.usageLoaded)
        history.record("a")
        runIo()
        assertEquals("recents.txt was not read: not written", emptyList<List<String>>(), recentsWrites)
        assertEquals("usage.txt was: written", 1, usageWrites.size)
        val again = history.unread()
        assertTrue(again.recents)
        assertFalse(again.usage)
    }

    @Test fun `a second read of a file already merged is dropped`() {
        recentsOnDisk = listOf("a")
        usageOnDisk = mapOf("a" to Usage(1.0, 1000L))
        val unread = history.unread()
        val first = history.read(unread)
        val second = history.read(unread)
        assertTrue(history.merge(first))
        assertFalse(history.merge(second))
        assertEquals(listOf("a"), history.recents)
        assertEquals(Usage(1.0, 1000L), history.usage["a"])
    }

    @Test fun `the list keeps RECENTS_KEPT launches, newest first`() {
        load()
        for (i in 1..LaunchHistory.RECENTS_KEPT + 5) history.record("k$i")
        assertEquals(LaunchHistory.RECENTS_KEPT, history.recents.size)
        assertEquals("k${LaunchHistory.RECENTS_KEPT + 5}", history.recents.first())
    }
}
