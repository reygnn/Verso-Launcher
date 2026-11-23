package com.github.reygnn.verso_launcher

/**
 * The launches Verso made: the "?" list and the counts behind "%", with the two disposable files
 * that keep them past the process ([RecentsLog], [UsageLog]). One per process ([Core]), so it
 * outlives a [LauncherState]: "~restart" builds a new state around the same history.
 *
 * A file is merged in once per process. Until it is, launches are kept in memory and never
 * written, since a write would replace a file that was not read yet; [merge] then adds them to
 * what the file held and writes the sum back. A file that is there but cannot be read stays
 * unread, so the next [read] tries it again.
 *
 * Thread: main. [read] alone runs on the IO thread; it touches the files and nothing of this
 *   object.
 *
 * @param io the IO thread, for the writes.
 * @param now the clock of a launch and of the decay behind "%".
 */
internal class LaunchHistory(
    private val io: (Runnable) -> Unit,
    private val recentsFile: RecentsFile,
    private val usageFile: UsageFile,
    private val now: () -> Long,
) {
    /** The "?" list, newest first. */
    var recents: List<String> = emptyList()
        private set

    /** The launches behind the "%" order. */
    var usage: Map<String, Usage> = emptyMap()
        private set

    /** Whether recents.txt was merged into [recents]: only then is it written. */
    var recentsLoaded = false
        private set

    /** Whether usage.txt was merged into [usage]: only then is it written. */
    var usageLoaded = false
        private set

    /** The files a refresh has yet to read, asked as it starts. */
    fun unread(): Unread = Unread(recents = !recentsLoaded, usage = !usageLoaded)

    /**
     * The files [unread] names, as they are on disk.
     *
     * Thread: the IO thread.
     */
    fun read(unread: Unread): Read = Read(
        recents = if (unread.recents) recentsFile.read() else null,
        usage = if (unread.usage) usageFile.read() else null,
    )

    /**
     * [read] merged with this process's launches, and written back when there were any. True if
     * that changed the "?" list.
     *
     * Invariant: a file is merged once per process. A later read of it is dropped, since what
     *   this object holds already has the file in it.
     */
    fun merge(read: Read): Boolean {
        if (read.usage != null && !usageLoaded) {
            val counted = usage
            usage = UsageLog.merged(read.usage, counted, now())
            usageLoaded = true
            if (counted.isNotEmpty()) persistUsage()
        }
        if (read.recents == null || recentsLoaded) return false
        val launched = recents
        recents = RecentsLog.merged(read.recents, launched, RECENTS_KEPT)
        recentsLoaded = true
        if (launched.isNotEmpty()) persistRecents()
        return recents != launched
    }

    /** A launch of the app with [key] that started. */
    fun record(key: String) {
        recents = RecentsLog.pushRecent(recents, key, RECENTS_KEPT)
        if (recentsLoaded) persistRecents()
        usage = UsageLog.recorded(usage, key, now())
        if (usageLoaded) persistUsage()
    }

    private fun persistRecents() {
        val keys = recents
        io(Runnable { recentsFile.write(keys) })
    }

    private fun persistUsage() {
        val log = usage
        val at = now()
        io(Runnable { usageFile.write(log, at) })
    }

    /** Which of the two files are not merged in yet. */
    class Unread(val recents: Boolean, val usage: Boolean)

    /**
     * What [read] found on the IO thread, handed to main as it is.
     *
     * @property recents recents.txt, null if it was not asked for or is unreadable.
     * @property usage usage.txt, the same.
     */
    class Read(val recents: List<String>?, val usage: Map<String, Usage>?)

    companion object {
        /** How many "?" shows. */
        const val RECENTS_LIMIT = 8

        /** How many recents.txt keeps: an app that is gone takes no place among the shown. */
        const val RECENTS_KEPT = 4 * RECENTS_LIMIT
    }
}

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
