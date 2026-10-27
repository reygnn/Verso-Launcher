package com.github.reygnn.verso_launcher

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/** How [LauncherState.replace] ended. */
internal enum class Replaced { SAME, UNDO_FAILED, DONE }

/** A value that outlives a [LauncherState], and whether its file was read into it yet. Main thread only. */
internal class Kept<T>(var value: T, var loaded: Boolean = false)

/**
 * What outlives a recreated [LauncherState]: held once per process (VersoApp), so the next
 * instance takes over instead of starting from nothing.
 *
 * @property seedPending a fresh install still waiting for an enumeration with a list to seed the
 *   default favorites (hard rule 10). Main thread only; atomic as a box that outlives a state.
 * @property recents the "?" list, newest first, and whether recents.txt was read into it.
 * @property usage the "%" launches, and whether usage.txt was read into them.
 * @property messages the commands' results not yet shown, oldest first: a "~backup" written while
 *   the user is away, a save that failed. Shown once each, in order, by whichever instance is in
 *   front first. A queue, not one slot: a result never replaces another. At most one result per
 *   command, and commands start from the front, so it stays short without a cap.
 */
internal class ProcessState(
    val seedPending: AtomicBoolean = AtomicBoolean(),
    val recents: Kept<List<String>> = Kept(emptyList()),
    val usage: Kept<Map<String, Usage>> = Kept(emptyMap()),
    val messages: ConcurrentLinkedQueue<String> = ConcurrentLinkedQueue(),
)

/**
 * verso.json as [LauncherState] writes it (ConfigJson and ConfigStore behind it); Core reads it
 * before the state exists.
 *
 * @property write false if the write failed.
 */
internal class ConfigFile(
    val serialize: (VersoConfig) -> String,
    val write: (String) -> Boolean,
)

/** recents.txt: [read] null when it is there but unreadable (then it is never written over). */
internal class RecentsFile(
    val read: () -> List<String>?,
    val write: (List<String>) -> Unit,
)

/** usage.txt: [read] null when it is there but unreadable; [write] gets the clock of the change. */
internal class UsageFile(
    val read: () -> Map<String, Usage>?,
    val write: (log: Map<String, Usage>, at: Long) -> Unit,
)
