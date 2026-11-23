package com.github.reygnn.verso_launcher

/**
 * The favorites as home-cache.txt holds them ([HomeCache]): read so that a cold start draws home
 * before the first list is in, written when what home shows has changed. One per process ([Core]),
 * so a recreated window neither reads the file again nor writes over it what it already holds.
 *
 * Thread: main. [read] and [write] alone run on the IO thread.
 *
 * @param main hands back on the main thread for the process, never dropped.
 * @param read the file's favorites; none when there is no cache.
 * @param write replaces the file.
 */
internal class CachedFavorites(
    private val io: (Runnable) -> Unit,
    private val main: (() -> Unit) -> Unit,
    private val read: () -> List<AppEntry>,
    private val write: (List<AppEntry>) -> Unit,
) {
    // What the file holds as far as this process knows: last read or last written. Null until either.
    private var held: List<AppEntry>? = null

    // The windows that asked while the one read still runs.
    private val waiting = ArrayList<(List<AppEntry>) -> Unit>()

    /**
     * Hands [onCached] what the file holds: at once when this process knows it, else after the one
     * read. A window draws it only while its state has no list yet, which it checks itself, as it
     * checks that it still exists.
     */
    fun load(onCached: (List<AppEntry>) -> Unit) {
        val known = held
        if (known != null) {
            onCached(known)
            return
        }
        waiting += onCached
        if (waiting.size > 1) return
        io(Runnable {
            val cached = read()
            main {
                // Home may have shown a first list meanwhile: what was written then is newer than this read.
                val current = held ?: cached.also { held = it }
                val asked = waiting.toList()
                waiting.clear()
                asked.forEach { it(current) }
            }
        })
    }

    /**
     * Home shows [favorites] from a loaded list. The file is written only when they differ from
     * what it holds, by key and label: most refreshes change neither.
     */
    fun shown(favorites: List<AppEntry>) {
        if (held?.let(::asShown) == asShown(favorites)) return
        held = favorites
        io(Runnable { write(favorites) })
    }

    /** What the file keeps of a favorite ([HomeCache.format]): two lists equal in this are one cache. */
    private fun asShown(favorites: List<AppEntry>): List<Pair<String, String>> = favorites.map { it.key to it.label }
}
