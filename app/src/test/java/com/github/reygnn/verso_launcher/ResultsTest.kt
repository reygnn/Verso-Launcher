package com.github.reygnn.verso_launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue

/** The commands' results on the JVM: a fake window stands in for MainActivity (Results sees only a LauncherWindow). */
class ResultsTest {

    private val messages = ConcurrentLinkedQueue<String>()
    private var live: LauncherWindow? = null
    private val results = Results(messages) { live }

    private class FakeWindow(var front: Boolean) : LauncherWindow {
        val shown = mutableListOf<Pair<String, Boolean>>()
        override val inFront: Boolean get() = front
        override fun refreshed(from: LauncherState, configReplaced: Boolean, listChanged: Boolean) = Unit
        override fun show(text: String, long: Boolean) {
            shown += text to long
        }
        override fun offerCleanup(orphans: Orphans) = Unit
        override fun cleanupApplied() = Unit
        override fun offerRestore(restored: VersoConfig) = Unit
    }

    @Test fun `a result while no window is in front waits, then shows once, in order`() {
        val window = FakeWindow(front = false)
        live = window
        results.report("first")
        results.keep("second")                  // as an IO step queues one
        assertTrue("nothing over another app", window.shown.isEmpty())
        window.front = true
        results.flush()
        assertEquals(listOf("first" to true, "second" to true), window.shown)
        results.flush()
        assertEquals("once each", 2, window.shown.size)
    }

    @Test fun `a result with no window at all waits for the next one`() {
        live = null
        results.report("kept")
        val next = FakeWindow(front = true)
        live = next
        results.flush()
        assertEquals(listOf("kept" to true), next.shown)
    }

    @Test fun `a result while in front shows at once`() {
        val window = FakeWindow(front = true)
        live = window
        results.report("now")
        assertEquals(listOf("now" to true), window.shown)
        assertTrue(messages.isEmpty())
    }

    /** A refusal is no result: out of context later it means nothing, so it is never queued. */
    @Test fun `a refusal shows in front and is dropped while away`() {
        val window = FakeWindow(front = false)
        live = window
        results.refuse("not loaded")
        assertTrue(messages.isEmpty())
        window.front = true
        results.flush()
        assertTrue("dropped, not shown later", window.shown.isEmpty())
        results.refuse("busy")
        assertEquals(listOf("busy" to false), window.shown)
    }
}
