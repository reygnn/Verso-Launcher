package com.github.reygnn.verso_launcher

/**
 * The favorites as home-cache.txt holds them ([HomeCache]): read so that a cold start draws home
 * before the first list is in, written when what home shows differs from the file. One per
 * process ([Core]), so a recreated window neither reads the file again nor writes over it what it
 * already holds.
 *
 * It keeps no window (hard rule 16): a window asks what is known ([load]), and what a read brings
 * later goes to whichever window is live then ([onRead]).
 *
 * Invariant: the file is written only when what home shows differs from what it holds, in every
 *   order of the read and the first list. Favorites shown before the read is back wait for it
 *   ([shown]). A read that failed leaves the file unknown: what waited is then written as it is,
 *   and counts as what the file holds.
 *
 * Thread: main. [read] and [write] alone run on the IO thread.
 *
 * @param main hands back on the main thread for the process, never dropped.
 * @param read the file's favorites; no cache and an unreadable one are both an empty list
 *   ([HomeCache.read]). If it throws all the same, the hand-back still comes: the file stays
 *   unknown, and the next [load] reads again.
 * @param write replaces the file.
 * @param onRead what the file holds, once a read is back and it is known. For the live window, if
 *   there is one.
 */
internal class CachedFavorites(
    private val io: (Runnable) -> Unit,
    private val main: (() -> Unit) -> Unit,
    private val read: () -> List<AppEntry>,
    private val write: (List<AppEntry>) -> Unit,
    private val onRead: (List<AppEntry>) -> Unit,
) {
    // What the file holds as this process knows it: as read, then as last written. Null until a
    // read is back, or after one that failed.
    private var held: List<AppEntry>? = null

    // Whether a read is on its way.
    private var reading = false

    // What home showed before the read was back: written, or not, once the file is known.
    private var shownUnread: List<AppEntry>? = null

    /**
     * What the file holds, if this process knows it already. If not, null: the one read starts (or
     * is on its way), and [onRead] brings its answer.
     */
    fun load(): List<AppEntry>? {
        if (held == null) startRead()
        return held
    }

    /**
     * Home shows [favorites] from a loaded list. The file is written only when they differ from
     * what it holds, by key and label: most refreshes change neither.
     */
    fun shown(favorites: List<AppEntry>) {
        val known = held
        if (known == null) {
            // The file is not read yet. Writing now would replace a file that may hold just this.
            shownUnread = favorites
            startRead()
            return
        }
        if (asShown(known) == asShown(favorites)) return
        writeAsHeld(favorites)
    }

    private fun startRead() {
        if (reading) return
        reading = true
        io(Runnable {
            var cached: List<AppEntry>? = null
            // No catch: a throw goes on to Guard, which logs it (hard rule 2); the hand-back comes
            // either way, or a failed read would hold back every write after it.
            try {
                cached = read()
            } finally {
                main { landed(cached) }
            }
        })
    }

    /** The read is back with what the file holds; [cached] is null if it failed. */
    private fun landed(cached: List<AppEntry>?) {
        reading = false
        if (cached != null) held = cached
        val waited = shownUnread
        shownUnread = null
        if (waited != null) {
            // The first list was faster than the file: now it is known whether they differ.
            if (held != null) shown(waited) else writeAsHeld(waited)
        }
        held?.let(onRead)
    }

    private fun writeAsHeld(favorites: List<AppEntry>) {
        held = favorites
        io(Runnable { write(favorites) })
    }

    /** What the file keeps of a favorite ([HomeCache.format]): two lists equal in this are one cache. */
    private fun asShown(favorites: List<AppEntry>): List<Pair<String, String>> = favorites.map { it.key to it.label }
}
