package com.github.reygnn.verso_launcher

/**
 * When a change to the apps becomes a refresh. The system reports a package or a profile that
 * changed ([AppWatch]); the window reports when it is in front. Pure: the timer and the refresh
 * are the caller's ([Core]).
 *
 *  - In front, a burst of changes is collected for [SETTLE_MS] after the last of them into one
 *    refresh.
 *  - Behind another app, a change only marks the list stale, so a store updating 20 apps costs
 *    one reload at the next resume.
 *  - A resume refreshes a stale list, and one whose last load failed or came back incomplete. It
 *    does not refresh otherwise: a full enumeration of every profile would compete with the
 *    return animation.
 *
 * Thread: main.
 *
 * @param schedule starts the one timer anew; [settled] is called when it runs out.
 * @param cancel stops that timer.
 * @param refresh enumerates the apps again.
 */
internal class AppChanges(
    private val schedule: (delayMs: Long) -> Unit,
    private val cancel: () -> Unit,
    private val refresh: () -> Unit,
) {
    private var inFront = false

    // True from the start: the first resume brings the first list.
    private var stale = true

    // Whether the timer runs. A View or Handler cannot be asked, so this says it.
    private var settling = false

    /** A package or a profile changed. */
    fun changed() {
        if (inFront) {
            schedule(SETTLE_MS)
            settling = true
        } else {
            stale = true
        }
    }

    /** The timer ran out: the burst is over. */
    fun settled() {
        settling = false
        // Behind another app by now only if the timer outlived [paused]'s cancel.
        if (inFront) refresh() else stale = true
    }

    /**
     * What is loaded may no longer hold without any package having changed: a new window (a
     * language change relabels every app, and recreates the window) or a new state ("~restart").
     */
    fun markStale() {
        stale = true
    }

    /**
     * The window came to the front.
     *
     * @param incomplete the last load failed or missed a profile or an app. No package event
     *   retries it, so every resume does until one load is complete.
     */
    fun resumed(incomplete: Boolean) {
        inFront = true
        if (stale || incomplete) {
            stale = false
            refresh()
        }
    }

    /** The window left the front. A burst still settling is collected by the next resume instead. */
    fun paused() {
        inFront = false
        if (settling) {
            cancel()
            settling = false
            stale = true
        }
    }

    companion object {
        /** How long package changes in front are collected into one refresh. */
        const val SETTLE_MS = 500L
    }
}
