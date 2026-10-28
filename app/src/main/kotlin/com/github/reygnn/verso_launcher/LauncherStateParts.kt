package com.github.reygnn.verso_launcher

/** A value that outlives a [LauncherState], and whether its file was read into it yet. Main thread only. */
internal class Kept<T>(var value: T, var loaded: Boolean = false)

/**
 * What outlives a recreated [LauncherState]: held once per process (VersoApp), so the next
 * instance takes over instead of starting from nothing.
 *
 * @property recents the "?" list, newest first, and whether recents.txt was read into it.
 * @property usage the "%" launches, and whether usage.txt was read into them.
 */
internal class ProcessState(
    val recents: Kept<List<String>> = Kept(emptyList()),
    val usage: Kept<Map<String, Usage>> = Kept(emptyMap()),
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
