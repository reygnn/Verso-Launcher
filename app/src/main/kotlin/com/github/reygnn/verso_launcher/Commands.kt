package com.github.reygnn.verso_launcher

/**
 * A one-shot "~" command: unlike a [Mode] it acts once, on Enter or a tap. [VERIFY]'s act is to
 * set the prompt whose rows are its report ([Commands.VERIFY]).
 */
internal enum class Command { CRASH, RESTART, VERIFY }

/** The "~" commands: the one list of them, and which of them a prompt spells or abbreviates. Pure. */
internal object Commands {

    /**
     * Every "~" command, the one list the overview, the abbreviations and the parser share. The
     * overview is bottom-anchored: "~restart", the reload of the config file, nearest the prompt.
     */
    val ALL: List<Pair<String, Command>> = listOfNotNull(
        ("~crash" to Command.CRASH).takeIf { DailyDriver.ENABLED },
        VERIFY to Command.VERIFY,
        "~restart" to Command.RESTART,
    )

    /**
     * The prompt that shows what "~verify" found: running it sets this. Unlike the other commands
     * it is also a view, so the report stays while the list reloads and goes with the prompt.
     */
    const val VERIFY = "~verify"

    /** The command spelled exactly by [trimmed] (folded), or null; a bare "~" is none. */
    fun parseCommand(trimmed: String): Command? {
        // The fold drops U+0307, so "\u0307~restart" would fold to a command its mode never showed.
        if (!trimmed.startsWith("~")) return null
        val q = Labels.foldLabel(trimmed)
        return ALL.firstOrNull { it.first == q }?.second
    }

    fun commandsMatching(trimmed: String): List<Pair<String, Command>> {
        val q = Labels.foldLabel(trimmed)
        return ALL.filter { it.first.startsWith(q) }
    }

    /**
     * An exact match, else an unambiguous abbreviation ("~r"). Exact must win should one spelling
     * ever prefix another. A bare "~" is no abbreviation and resolves to null, never a guess.
     * "~crash" is never abbreviated: "~c" means nothing in every build.
     *
     * Why: should a build ever list one command alone that may be abbreviated, every prompt
     *   starting with "~" would be "unambiguous". Enter on a bare "~" must still not run it.
     */
    fun resolveCommand(trimmed: String): Command? {
        // As in parseCommand: commandsMatching folds the U+0307 away.
        if (!trimmed.startsWith("~")) return null
        parseCommand(trimmed)?.let { return it }
        val abbreviated = commandsMatching(trimmed).filter { it.second != Command.CRASH }.singleOrNull()?.second
        // Folded, like the match: "~" + U+0307 is a bare "~" too.
        return abbreviated.takeIf { Labels.foldLabel(trimmed).length > 1 }
    }
}
