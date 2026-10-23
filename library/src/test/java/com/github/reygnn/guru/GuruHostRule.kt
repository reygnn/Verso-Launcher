package com.github.reygnn.guru

import android.app.Activity
import android.app.Application
import android.os.Looper
import org.junit.rules.ExternalResource
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import java.io.File

/**
 * A host for Robolectric tests: the Application, its `io` as a manual FIFO, the store Guru
 * writes to, and the default uncaught-exception handler put back afterwards — Guru.install
 * replaces it for the whole JVM, and a later test must install onto a clean slate.
 */
class GuruHostRule : ExternalResource() {

    private var savedHandler: Thread.UncaughtExceptionHandler? = null
    private val ioQueue = ArrayDeque<Runnable>()

    lateinit var app: Application
        private set

    val io: (Runnable) -> Unit = { ioQueue.addLast(it) }

    /** The same directory Guru.install opens; a second store on it sees what Guru wrote. */
    internal val store by lazy { GuruStore(File(app.filesDir, "guru"), log = { _, _ -> }, syncDir = {}) }

    override fun before() {
        savedHandler = Thread.getDefaultUncaughtExceptionHandler()
        app = RuntimeEnvironment.getApplication()
    }

    override fun after() {
        Thread.setDefaultUncaughtExceptionHandler(savedHandler)
    }

    fun queuedIo(): Int = ioQueue.size

    /** Runs the queued IO tasks, and those they queue, in submission order. */
    fun runIo() {
        while (ioQueue.isNotEmpty()) ioQueue.removeFirst().run()
    }

    fun idleMain() = shadowOf(Looper.getMainLooper()).idle()

    fun activity(): ActivityController<Activity> = Robolectric.buildActivity(Activity::class.java).setup()

    /** A report written as the handler would have, before this "start". */
    internal fun crashed(id: String, at: Long = System.currentTimeMillis() - MINUTE_MS) = report(id = id, crashedAt = at).also { store.write(it) }

    companion object {
        const val MINUTE_MS = 60_000L
    }
}
