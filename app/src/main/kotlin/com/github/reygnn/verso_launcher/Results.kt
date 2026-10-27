package com.github.reygnn.verso_launcher

import java.util.Queue

/**
 * The "~" commands' results, owned by the process (VersoApp.results). A result is never shown over
 * another app and never lost: it waits in ProcessState.messages until a window is in front —
 * shown at once, at onStart (it finished after onStop), or at the first refresh of the window a
 * recreation made. A refusal is not a result: shown while in front, else dropped. Main thread only.
 *
 * Pure: the queue (ProcessState.messages) and the live window come in as values, so the rules are
 * JVM-testable.
 */
internal class Results(
    private val queue: Queue<String>,
    private val liveWindow: () -> LauncherWindow?,
) {

    /** A result decided on the main thread: queued, and shown at once if a window is in front. */
    fun report(text: String) {
        queue.add(text)
        flush()
    }

    /** Every queued result, oldest first, once each, if a window is in front. Main thread. */
    fun flush() {
        val window = liveWindow()?.takeIf { it.inFront } ?: return
        var text = queue.poll()
        while (text != null) {
            window.show(text, long = true)
            text = queue.poll()
        }
    }

    /** "Not loaded" hours later, out of context, would mean nothing: in front or not at all. */
    fun refuse(text: String) {
        liveWindow()?.takeIf { it.inFront }?.show(text, long = false)
    }
}
