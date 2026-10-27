package com.github.reygnn.verso_launcher

/**
 * What the "~restore" dialog says. [words] resolves a string resource, with its count or
 * without one.
 */
internal object CommandDialogText {

    /** All four counts, zeros too: a planted file that empties the favorites or hides apps must show it. */
    fun restore(restored: VersoConfig, words: (id: Int, count: Int?) -> String): String = listOf(
        words(R.string.restore_intro, null),
        "",
        words(R.string.count_favorites, restored.favorites.size),
        words(R.string.count_hidden, restored.hidden.size),
        words(R.string.count_names, restored.names.size),
        words(R.string.count_tags, restored.tags.size),
        "",
        words(R.string.restore_undo_note, null),
    ).joinToString("\n")
}
