package com.github.reygnn.guru

import java.util.concurrent.atomic.AtomicBoolean

/**
 * The default uncaught-exception handler: [capture] writes the report, then the crash goes on
 * to [previous] — on Android the runtime's own, which shows the dialog and kills the process.
 * Guru never decides how the process dies, it only takes notes first.
 *
 * One capture per process: a second crash meanwhile (another thread, or the capture itself
 * failing into the handler) goes straight on. Whatever [capture] throws is dropped — the real
 * crash must reach [previous] unchanged. Without a [previous] (only on a bare JVM), [die].
 */
internal class GuruHandler(
    private val previous: Thread.UncaughtExceptionHandler?,
    private val capture: (Thread, Throwable) -> Unit,
    private val die: () -> Unit = { Runtime.getRuntime().halt(DEATH_STATUS) },
) : Thread.UncaughtExceptionHandler {

    private val captured = AtomicBoolean(false)

    override fun uncaughtException(thread: Thread, error: Throwable) {
        if (captured.compareAndSet(false, true)) {
            // The process is dying: a failed report (an OOM, a full disk) must not replace the crash.
            @Suppress("TooGenericExceptionCaught", "SwallowedException")
            try {
                capture(thread, error)
            } catch (ignored: Throwable) {
                Unit
            }
        }
        previous?.uncaughtException(thread, error) ?: die()
    }

    companion object {
        /** What the JVM's own handler exits with after an uncaught exception. */
        const val DEATH_STATUS = 1
    }
}
