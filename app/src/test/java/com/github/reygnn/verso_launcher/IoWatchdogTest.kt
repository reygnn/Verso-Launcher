package com.github.reygnn.verso_launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class IoWatchdogTest {

    @get:Rule val tmp = TemporaryFolder()

    private val io = Thread("io")
    private val watchdog = IoWatchdog(limit = 3, onStall = {})

    private fun ticks(n: Int) = List(n) { watchdog.tick() }

    @Test fun `a task still running after the limit hands its thread over, once`() {
        watchdog.started(io)
        // The first tick only sees the task: it may have started just before.
        assertEquals(listOf(null, null, null, io, null, null), ticks(6))
    }

    @Test fun `idle never trips`() {
        assertEquals(listOf<Thread?>(null, null, null, null, null), ticks(5))
    }

    @Test fun `each task counts from its own start`() {
        watchdog.started(io)
        ticks(3)
        watchdog.ended()
        watchdog.started(io)
        assertEquals(listOf(null, null, null, io), ticks(4))
    }

    @Test fun `the executor reports its thread, and its end`() {
        val running = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = WatchedIo(watchdog)
        executor.execute {
            running.countDown()
            release.await()
        }
        assertTrue(running.await(5, TimeUnit.SECONDS))
        val stuck = ticks(4).last()
        assertEquals("verso-io", stuck?.name)
        release.countDown()
        executor.submit {}.get(5, TimeUnit.SECONDS)
        assertEquals(listOf<Thread?>(null, null, null, null), ticks(4))
    }

    @Test fun `the thread ticks while a task runs and hands it to onStall`() {
        val stalled = CountDownLatch(1)
        var got: Thread? = null
        val running = IoWatchdog(tickMs = 1, limit = 3, onStall = {
            got = it
            stalled.countDown()
        })
        running.start()
        running.started(io)
        assertTrue(stalled.await(5, TimeUnit.SECONDS))
        assertSame(io, got)
    }

    @Test fun `the exception carries the IO thread's stack`() {
        val stack = arrayOf(StackTraceElement("android.os.BinderProxy", "transactNative", null, -2))
        assertEquals(stack.toList(), IoStallException(stack).stackTrace.toList())
    }

    private var now = 1_000_000L
    private val file by lazy { File(tmp.root, StallTrips.FILE) }
    private val trips by lazy { StallTrips(file) { now } }

    @Test fun `one crash in ten minutes, then none`() {
        assertTrue(trips.allow())
        now += StallTrips.WINDOW_MS - 1
        assertFalse(trips.allow())
    }

    @Test fun `a trip older than the window no longer counts`() {
        assertTrue(trips.allow())
        now += StallTrips.WINDOW_MS
        assertTrue(trips.allow())
    }

    @Test fun `a refused trip is not recorded`() {
        trips.allow()
        now += 60_000
        trips.allow()
        assertEquals(listOf("1000000"), file.readLines())
    }

    @Test fun `garbage and a clock set back count as nothing`() {
        file.writeText("x\n${now + 5_000}\n\n")
        assertTrue(trips.allow())
    }

    @Test fun `a file that cannot be read stops the crashes`() {
        file.writeText("")
        file.setReadable(false)
        assertFalse(trips.allow())
    }

    @Test fun `a file that cannot be written stops the crashes`() {
        val dir = tmp.newFolder("gone")
        val unwritable = StallTrips(File(dir, StallTrips.FILE)) { now }
        dir.delete()
        assertFalse(unwritable.allow())
        assertNull(dir.listFiles())
    }
}
