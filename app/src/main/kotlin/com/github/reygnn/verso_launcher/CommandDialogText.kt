package com.github.reygnn.verso_launcher

/**
 * What the "~cleanup" and "~restore-saf" dialogs say. [words] resolves a string resource, with
 * its count or without one.
 */
internal object CommandDialogText {

    /** One line per section that has entries — no "0" lines, and no plural to get wrong. */
    fun cleanup(orphans: Orphans, words: (id: Int, count: Int?) -> String): String = buildList {
        add(words(R.string.cleanup_apps, orphans.keys.size))
        add("")
        if (orphans.favorites > 0) add(words(R.string.cleanup_favorites, orphans.favorites))
        if (orphans.hidden > 0) add(words(R.string.cleanup_hidden, orphans.hidden))
        if (orphans.names > 0) add(words(R.string.cleanup_names, orphans.names))
        if (orphans.tags > 0) add(words(R.string.cleanup_tags, orphans.tags))
        add("")
        add(words(R.string.cleanup_undo_note, null))
    }.joinToString("\n")

    /**
     * All four counts, zeros too: a planted file that empties the favorites or hides apps must
     * show it. The same labels as the cleanup dialog's.
     */
    fun restore(restored: VersoConfig, words: (id: Int, count: Int?) -> String): String = listOf(
        words(R.string.restore_intro, null),
        "",
        words(R.string.cleanup_favorites, restored.favorites.size),
        words(R.string.cleanup_hidden, restored.hidden.size),
        words(R.string.cleanup_names, restored.names.size),
        words(R.string.cleanup_tags, restored.tags.size),
        "",
        words(R.string.restore_undo_note, null),
    ).joinToString("\n")
}
