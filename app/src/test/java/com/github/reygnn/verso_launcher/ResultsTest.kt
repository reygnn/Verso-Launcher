package com.github.reygnn.verso_launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The commands' results without an Activity: a fake window stands in for MainActivity (Results
 * sees only a LauncherWindow). Robolectric only for the Application that holds the queue.
 */
@RunWith(RobolectricTestRunner::class)
class ResultsTest {

    private val app = RuntimeEnvironment.getApplication() as VersoApp
    private val results = Results(app)

    private class FakeWindow(var front: Boolean) : LauncherWindow {
        val shown = mutableListOf<Pair<String, Boolean>>()
        override val inFront: Boolean get() = front
        override fun refreshed(from: LauncherState, configReplaced: Boolean, listChanged: Boolean) = Unit
        override fun show(text: String, long: Boolean) {
            shown += text to long
        }
        override fun stillAsking(ticket: Int) = front
        override fun offerCleanup(orphans: Orphans) = Unit
        override fun cleanupApplied() = Unit
    }

    @Test fun `a result while no window is in front waits, then shows once, in order`() {
        val window = FakeWindow(front = false)
        app.instance = window
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
        app.instance = null
        results.report("kept")
        val next = FakeWindow(front = true)
        app.instance = next
        results.flush()
        assertEquals(listOf("kept" to true), next.shown)
    }

    @Test fun `a result while in front shows at once`() {
        val window = FakeWindow(front = true)
        app.instance = window
        results.report("now")
        assertEquals(listOf("now" to true), window.shown)
        assertTrue(app.process.messages.isEmpty())
    }

    /** A refusal is no result: out of context later it means nothing, so it is never queued. */
    @Test fun `a refusal shows in front and is dropped while away`() {
        val window = FakeWindow(front = false)
        app.instance = window
        results.refuse("not loaded")
        assertTrue(app.process.messages.isEmpty())
        window.front = true
        results.flush()
        assertTrue("dropped, not shown later", window.shown.isEmpty())
        results.refuse("busy")
        assertEquals(listOf("busy" to false), window.shown)
    }
}
