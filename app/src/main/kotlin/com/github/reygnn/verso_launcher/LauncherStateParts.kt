package com.github.reygnn.verso_launcher

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/** A value that outlives a [LauncherState], and whether its file was read into it yet. Main thread only. */
internal class Kept<T>(var value: T, var loaded: Boolean = false)

/**
 * What outlives a recreated [LauncherState]: held once per process (VersoApp), so the next
 * instance takes over instead of starting from nothing.
 *
 * @param startedOver an unreadable config was set aside; set on the IO thread, taken by whichever
 *   instance's hand-off lands first, so the toast is never lost (hard rule 4).
 * @param seedPending a fresh install still waiting for an enumeration with a list (and no running
 *   "~" command) to seed the default favorites (hard rule 10). Ended on the IO thread by a
 *   restore's write, hence atomic.
 * @param recents the "?" list, newest first, and whether recents.txt was read into it.
 * @param usage the "%" launches, and whether usage.txt was read into them.
 * @param messages the "~backup"/restore results not yet shown, oldest first. Added on the IO thread
 *   before the hand-back, which may land on an instance that is gone or stopped; shown once each,
 *   in order, by whichever instance is in front first. A queue, not one slot: a result never
 *   replaces another. Only results go here — a refusal ("busy", "not loaded") is shown at once or not at all, since it
 *   means nothing later. At most one result per run, and runs start from the front — the one
 *   exception, a "~restore-saf" adoption, from a pick the user made there — so it stays short
 *   without a cap.
 */
internal class ProcessState(
    val startedOver: AtomicBoolean = AtomicBoolean(),
    val seedPending: AtomicBoolean = AtomicBoolean(),
    val recents: Kept<List<String>> = Kept(emptyList()),
    val usage: Kept<Map<String, Usage>> = Kept(emptyMap()),
    val messages: ConcurrentLinkedQueue<String> = ConcurrentLinkedQueue(),
)

/**
 * verso.json as [LauncherState] sees it (ConfigStore and ConfigJson behind it).
 *
 * @param load null when the file is there but unreadable.
 * @param setAsideUnreadable moves an unreadable file out of the way (hard rule 4); false if it
 *   could not be moved.
 * @param isFresh no verso.json yet: a fresh install (hard rule 10).
 * @param write false if the atomic write failed.
 */
internal class ConfigFile(
    val load: () -> VersoConfig?,
    val setAsideUnreadable: () -> Boolean,
    val isFresh: () -> Boolean,
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
