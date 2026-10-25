package com.github.reygnn.verso_launcher

/**
 * The "~" commands' results, owned by the process (VersoApp.results). A result is never shown over
 * another app and never lost: it waits in ProcessState.messages until a window is in front —
 * shown at the step's hand-back, at onStart (it finished after onStop), or at the first refresh
 * of the window a recreation made. A refusal is not a result: shown while in front, else dropped.
 */
internal class Results(private val app: VersoApp) {

    /** On the IO thread, before the hand-back: queues [text] as a result and returns it. */
    fun keep(text: String): String = text.also { app.process.messages.add(it) }

    /** A result decided on the main thread: queued, and shown at once if a window is in front. */
    fun report(text: String) {
        app.process.messages.add(text)
        flush()
    }

    /** Every queued result, oldest first, once each, if a window is in front. Main thread. */
    fun flush() {
        val window = app.instance?.takeIf { it.inFront } ?: return
        val queue = app.process.messages
        var text = queue.poll()
        while (text != null) {
            window.show(text, long = true)
            text = queue.poll()
        }
    }

    /** "Not loaded" hours later, out of context, would mean nothing: in front or not at all. */
    fun refuse(text: String) {
        app.instance?.takeIf { it.inFront }?.show(text, long = false)
    }
}
