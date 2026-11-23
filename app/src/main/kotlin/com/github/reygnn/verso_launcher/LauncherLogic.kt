package com.github.reygnn.verso_launcher

/** The command-line mode, chosen by the prompt's leading sigil. */
internal enum class Mode {
    NORMAL, RECENTS, TAG_FILTER, COMMAND, ORDER
}

internal enum class EmptyHint { NO_MATCH, NO_APPS, NO_TAGS, NO_TAG_MATCH, NO_RECENTS, NO_COMMAND }

/** A rendered list row. */
internal sealed interface Row
internal data class AppRow(val entry: AppEntry) : Row
internal data class TagRow(val name: String) : Row
internal data class CommandRow(val name: String, val command: Command) : Row
/** A row of "~verify": one thing it says about the user's file. A tap does nothing. */
internal data class ReportRow(val finding: ConfigFinding) : Row
/** The one "%" row: what Enter or a tap turns the plain drawer's order into. */
internal data class OrderRow(val toUsage: Boolean) : Row

/**
 * The launcher's data as one filter reads it, so that what the drawer shows is a function of the
 * prompt and this alone ([LauncherLogic.drawerView]). Nothing is defaulted: a field a caller
 * forgot would be a drawer that silently ignores it.
 *
 * @property loaded false until the first list is in: an empty [apps] then means "not yet".
 * @property now the clock of the decay behind "%".
 * @property report what "~verify" lists, asked only when its rows are shown.
 */
internal class DrawerData(
    val apps: List<AppEntry>,
    val loaded: Boolean,
    val hidden: Set<String>,
    val favorites: Collection<String>,
    val tags: Map<String, List<String>>,
    val recentKeys: List<String>,
    val byUsage: Boolean,
    val usage: Map<String, Usage>,
    val now: Long,
    val report: () -> List<ConfigFinding>,
)

/**
 * What the drawer shows for a prompt, and what an Enter or a tap there does: the whole of one
 * filter, so the views only draw it.
 *
 * @property trimmed the prompt without the blanks around it; the mode's sigil starts it.
 * @property topAnchored the bare drawer reads top-down; everything else sits above the prompt,
 *   where Enter acts.
 * @property emptyHint what an empty list says; null before the apps are loaded, when "no apps"
 *   would be false.
 */
internal class DrawerView(
    val mode: Mode,
    val trimmed: String,
    val rows: List<Row>,
    val topAnchored: Boolean,
    val emptyHint: EmptyHint?,
) {
    /** What Enter does: the last row sits right above the prompt and is its target. */
    fun enter(): DrawerAction =
        LauncherLogic.enterAction(mode, Commands.resolveCommand(trimmed), trimmed.isEmpty(), rows.lastOrNull())

    /** What a tap on the row at [position] does; a position past the list is a stale one. */
    fun tap(position: Int): DrawerAction = LauncherLogic.tapAction(mode, rows.getOrNull(position))
}

/** What an Enter or a tap does; the Activity performs it. */
internal sealed interface DrawerAction {
    data class Launch(val entry: AppEntry) : DrawerAction
    data class Run(val command: Command) : DrawerAction
    /** Drives the TextWatcher exactly as typing would. */
    data class SetPrompt(val text: String) : DrawerAction
    data object ToggleOrder : DrawerAction
    /** Enter on a blank prompt. */
    data object DismissKeyboard : DrawerAction
    /** A stale position, or a row or prompt this mode ignores. */
    data object None : DrawerAction
}

/**
 * What the drawer shows for a prompt and what an Enter or a tap there does: the mode, the rows and
 * the action, from the launcher's data alone ([drawerView]). Pure: no framework, disk or shared
 * state.
 */
internal object LauncherLogic {

    fun parseMode(trimmed: String): Mode = when {
        trimmed.startsWith("?") -> Mode.RECENTS
        trimmed.startsWith("#") -> Mode.TAG_FILTER
        trimmed.startsWith("~") -> Mode.COMMAND
        trimmed.startsWith("%") -> Mode.ORDER
        else -> Mode.NORMAL
    }

    /** Which hint an empty list shows in [mode] (mirrors [rowsFor]; "~verify" is never empty). */
    fun emptyHint(mode: Mode, trimmed: String): EmptyHint = when (mode) {
        Mode.COMMAND -> EmptyHint.NO_COMMAND
        Mode.TAG_FILTER -> if (trimmed.substring(1).isBlank()) EmptyHint.NO_TAGS else EmptyHint.NO_TAG_MATCH
        Mode.ORDER -> EmptyHint.NO_MATCH  // never empty: the one OrderRow
        Mode.RECENTS -> if (trimmed.substring(1).isBlank()) EmptyHint.NO_RECENTS else EmptyHint.NO_MATCH
        Mode.NORMAL -> if (trimmed.isEmpty()) EmptyHint.NO_APPS else EmptyHint.NO_MATCH
    }

    /**
     * Everything the drawer shows for [prompt] as the field holds it: the one function between the
     * launcher's data and the list.
     */
    fun drawerView(prompt: String, data: DrawerData): DrawerView {
        val trimmed = prompt.trim()
        val mode = parseMode(trimmed)
        return DrawerView(
            mode = mode,
            trimmed = trimmed,
            rows = rowsFor(mode, trimmed, data),
            topAnchored = mode == Mode.NORMAL && trimmed.isEmpty(),
            emptyHint = if (data.loaded) emptyHint(mode, trimmed) else null,
        )
    }

    /** The rows the list shows for the prompt. Every substring index is safe: the mode's sigil starts [trimmed]. */
    fun rowsFor(mode: Mode, trimmed: String, data: DrawerData): List<Row> = when (mode) {
        // Spelled out, "~verify" is its report; "~v" still lists the command, for Enter or a tap.
        Mode.COMMAND -> if (Commands.parseCommand(trimmed) == Command.VERIFY) {
            data.report().map { ReportRow(it) }
        } else {
            Commands.commandsMatching(trimmed).map { (name, cmd) -> CommandRow(name, cmd) }
        }
        // Only "%" itself: "%abc" is a typo, not a switch.
        Mode.ORDER -> if (trimmed == "%") listOf(OrderRow(toUsage = !data.byUsage)) else emptyList()
        Mode.TAG_FILTER -> if (trimmed.substring(1).isBlank()) {
            AppLists.tagsInUse(data.apps, data.tags).map { TagRow(it) }
        } else {
            Search.tagged(data.apps, data.tags, trimmed.substring(1).trim()).map { AppRow(it) }
        }
        Mode.RECENTS ->
            Search.search(AppLists.recentsInDisplayOrder(data.apps, data.recentKeys), trimmed.substring(1).trim()).map { AppRow(it) }
        Mode.NORMAL -> plainApps(trimmed, data).map { AppRow(it) }
    }

    /** [rowsFor]'s apps for a prompt with no sigil: the drawer, a star prefix or a search. */
    private fun plainApps(trimmed: String, data: DrawerData): List<AppEntry> {
        val wildcard = Search.starPrefix(trimmed)
        return when {
            // A to Z (or most used first, "%"), no favorites-last: the list anchors this view at the top.
            trimmed.isEmpty() -> AppLists.drawer(data.apps, data.hidden, data.favorites)
                .let { if (data.byUsage) UsageLog.byUsage(it, data.usage, data.now) else it }
            wildcard != null -> Search.drawerStartingWith(data.apps, data.hidden, data.favorites, wildcard)
            // Plain search spans ALL apps, so a hidden app stays reachable by name.
            else -> Search.ranked(Search.search(data.apps, trimmed), trimmed)
        }
    }

    /**
     * What Enter does; [lastRow] is the row nearest the prompt. The branch order is load-bearing:
     * a resolved command beats COMMAND mode's "still ambiguous, do nothing"; a blank prompt only
     * drops the keyboard and must come before the launch branch.
     */
    fun enterAction(
        mode: Mode,
        command: Command?,
        promptBlank: Boolean,
        lastRow: Row?,
    ): DrawerAction = when {
        command != null -> DrawerAction.Run(command)
        mode == Mode.COMMAND -> DrawerAction.None
        mode == Mode.ORDER -> if (lastRow is OrderRow) DrawerAction.ToggleOrder else DrawerAction.None
        promptBlank -> DrawerAction.DismissKeyboard
        else -> when (lastRow) {
            is AppRow -> DrawerAction.Launch(lastRow.entry)
            is TagRow -> DrawerAction.SetPrompt("#${lastRow.name}")
            else -> DrawerAction.None
        }
    }

    /** What a tap on [row] does; a null [row] is a stale position (the list shrank under the tap). */
    fun tapAction(mode: Mode, row: Row?): DrawerAction = when (row) {
        null -> DrawerAction.None
        is TagRow -> DrawerAction.SetPrompt("#${row.name}")
        is CommandRow -> DrawerAction.Run(row.command)
        is OrderRow -> DrawerAction.ToggleOrder
        is ReportRow -> DrawerAction.None
        is AppRow -> when (mode) {
            Mode.NORMAL, Mode.RECENTS, Mode.TAG_FILTER -> DrawerAction.Launch(row.entry)
            Mode.COMMAND, Mode.ORDER -> DrawerAction.None
        }
    }
}
