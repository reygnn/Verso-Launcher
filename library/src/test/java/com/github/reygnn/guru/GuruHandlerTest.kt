package com.github.reygnn.guru

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class GuruHandlerTest {

    private val forwarded = mutableListOf<Pair<Thread, Throwable>>()
    private val previous = Thread.UncaughtExceptionHandler { t, e -> forwarded += t to e }
    private val captured = mutableListOf<Throwable>()
    private var deaths = 0

    private fun handler(capture: (Thread, Throwable) -> Unit = { _, e -> captured += e }, prev: Thread.UncaughtExceptionHandler? = previous) =
        GuruHandler(prev, capture, die = { deaths++ })

    @Test
    fun `the crash is captured, then goes on unchanged`() {
        val error = IllegalStateException("boom")
        val thread = Thread("worker")
        handler().uncaughtException(thread, error)
        assertEquals(listOf<Throwable>(error), captured)
        assertSame(thread, forwarded.single().first)
        assertSame(error, forwarded.single().second)
    }

    /** A full disk or an OOM while writing must not replace the real crash. */
    @Test
    fun `a failing capture still forwards the original crash`() {
        val error = IllegalStateException("boom")
        handler(capture = { _, _ -> throw OutOfMemoryError("while writing") }).uncaughtException(Thread.currentThread(), error)
        assertSame(error, forwarded.single().second)
    }

    @Test
    fun `only the first crash is captured, every one is forwarded`() {
        val h = handler()
        val first = IllegalStateException("first")
        val second = IllegalArgumentException("second")
        h.uncaughtException(Thread.currentThread(), first)
        h.uncaughtException(Thread.currentThread(), second)
        assertEquals(listOf<Throwable>(first), captured)
        assertEquals(listOf<Throwable>(first, second), forwarded.map { it.second })
    }

    /** The capture crashing back into the handler: forwarded, not captured again (no recursion). */
    @Test
    fun `a crash during the capture is forwarded, not captured`() {
        lateinit var h: GuruHandler
        val inner = IllegalStateException("inner")
        h = handler(capture = { t, _ -> h.uncaughtException(t, inner) })
        val outer = IllegalStateException("outer")
        h.uncaughtException(Thread.currentThread(), outer)
        assertEquals(listOf<Throwable>(inner, outer), forwarded.map { it.second })
    }

    @Test
    fun `without a previous handler the process dies`() {
        handler(prev = null).uncaughtException(Thread.currentThread(), IllegalStateException())
        assertEquals(1, deaths)
        assertEquals(1, captured.size)
    }
}
