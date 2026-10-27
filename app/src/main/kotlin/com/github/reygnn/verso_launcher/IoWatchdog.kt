package com.github.reygnn.verso_launcher

import java.io.File
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** The one IO thread (hard rule 3), its tasks seen by [watchdog]. */
internal class WatchedIo(private val watchdog: IoWatchdog) :
    ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, LinkedBlockingQueue(), { task -> Thread(task, "verso-io") }) {

    override fun beforeExecute(t: Thread, r: Runnable) = watchdog.started(t)

    override fun afterExecute(r: Runnable?, t: Throwable?) = watchdog.ended()
}

/**
 * One of hard rule 3's exceptions, a thread of its own: a task on the IO thread still running after [limit] ticks hands
 * that thread to [onStall] — a system call that never returns would otherwise hold the "~"
 * commands, or on a cold start the drawer, until a force stop. It counts its own ticks, not the
 * clock: the freezer stops both threads, and a task frozen mid-run must not count as stuck. Idle,
 * it waits without waking.
 */
internal class IoWatchdog(
    private val tickMs: Long = TICK_MS,
    private val limit: Int = STALL_TICKS,
    private val onStall: (Thread) -> Unit,
) : Thread("verso-io-watchdog") {

    init {
        isDaemon = true
    }

    private val lock = ReentrantLock()
    private val busy = lock.newCondition()
    private var task = 0L
    private var worker: Thread? = null
    private var seen = -1L
    private var ticks = 0

    fun started(t: Thread) = lock.withLock {
        task++
        worker = t
        busy.signal()
    }

    fun ended() = lock.withLock { worker = null }

    /** One tick: the IO thread once its current task has run [limit] ticks, then never again for it. */
    fun tick(): Thread? = lock.withLock {
        val io = worker ?: return null
        if (task != seen) {
            seen = task
            ticks = 0
            return null
        }
        ticks++
        if (ticks == limit) io else null
    }

    override fun run() {
        while (true) {
            lock.withLock { while (worker == null) busy.await() }
            sleep(tickMs)
            tick()?.let(onStall)
        }
    }

    companion object {
        const val TICK_MS = 1_000L

        /** 30 to 31 s: an enumeration, a MediaStore write or Guru's save takes seconds at most. */
        const val STALL_TICKS = 30
    }
}

/** The IO thread's stack at the stall: the stuck call is its first line, not the watchdog's. */
internal class IoStallException(ioStack: Array<StackTraceElement>) :
    RuntimeException("verso-io stuck for ${IoWatchdog.STALL_TICKS} s") {
    init {
        stackTrace = ioStack
    }
}

/**
 * The watchdog's loop guard, a disposable file beside verso.json: a call stuck on every start must
 * not crash HOME over and over. One crash in [WINDOW_MS]: a second within a minute brings Android's
 * "keeps stopping" dialog, holds the process until it is answered and drops Verso as the default
 * HOME (A17, One UI 8). Watchdog thread only.
 */
internal class StallTrips(private val file: File, private val now: () -> Long = System::currentTimeMillis) {

    /** Records this stall and says whether it may crash: no record, no crash. */
    fun allow(): Boolean {
        val t = now()
        // hard rule 2: breaks a crash loop — a file that cannot be read or written stops the crashes
        @Suppress("SwallowedException")
        val recent = try {
            if (file.exists()) file.readLines().mapNotNull { it.toLongOrNull() }.filter { t - it in 0 until WINDOW_MS } else emptyList()
        } catch (e: IOException) {
            return false
        }
        if (recent.size >= MAX_TRIPS) return false
        // hard rule 2: as above
        @Suppress("SwallowedException")
        return try {
            file.writeText((recent + t).joinToString("\n"))
            true
        } catch (e: IOException) {
            false
        }
    }

    companion object {
        const val FILE = "stalls.txt"
        const val MAX_TRIPS = 1
        const val WINDOW_MS = 10 * 60 * 1_000L
    }
}
