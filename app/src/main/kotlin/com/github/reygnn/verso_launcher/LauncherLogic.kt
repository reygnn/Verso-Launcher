package com.github.reygnn.verso_launcher

import java.text.BreakIterator
import java.text.Collator
import java.text.Normalizer
import java.util.Locale

/** The launcher's pure decisions: no framework, disk or shared state. */

/** The command-line mode, chosen by the prompt's leading sigil. */
internal enum class Mode {
    NORMAL, RECENTS, TAG_FILTER, COMMAND, ORDER
}

internal enum class EmptyHint { NO_MATCH, NO_APPS, NO_TAGS, NO_TAG_MATCH, NO_RECENTS, NO_COMMAND }

/** A one-shot "~" command: unlike a [Mode] it renders nothing, it acts once on Enter. */
internal enum class Command { CRASH, RESTART, OPEN, EDIT }

/** What the ordering/search logic needs from a row; tests fake it instead of building AppEntries. */
internal interface Ordered {
    val key: String
    val labelLower: String
    val packageName: String
}

/** A rendered list row. Rows without an app are `Row<Nothing>`, so they fit a `Row<T>` list for any T. */
internal sealed interface Row<out T : Ordered>
internal data class AppRow<out T : Ordered>(val entry: T) : Row<T>
internal data class TagRow(val name: String) : Row<Nothing>
internal data class CommandRow(val name: String, val command: Command) : Row<Nothing>
/** The one "%" row: what Enter or a tap turns the plain drawer's order into. */
internal data class OrderRow(val toUsage: Boolean) : Row<Nothing>

/** What an Enter or a tap does; the Activity performs it. */
internal sealed interface DrawerAction<out T : Ordered> {
    data class Launch<out T : Ordered>(val entry: T) : DrawerAction<T>
    data class Run(val command: Command) : DrawerAction<Nothing>
    /** Drives the TextWatcher exactly as typing would. */
    data class SetPrompt(val text: String) : DrawerAction<Nothing>
    data object ToggleOrder : DrawerAction<Nothing>
    /** Enter on a blank prompt. */
    data object DismissKeyboard : DrawerAction<Nothing>
    /** A stale position, or a row or prompt this mode ignores. */
    data object None : DrawerAction<Nothing>
}

/** One key event in plain values, so [LauncherLogic.enterEdge] stays free of android.view.KeyEvent. */
internal data class EnterKey(val down: Boolean, val repeatCount: Int, val isEnterKey: Boolean)

internal enum class EnterEdge {
    /** The one call per Enter that acts. */
    ACT,
    /** The rest of an Enter already acted on (its key-up, a held key's repeats): swallowed. */
    CONSUME,
    /** Not an Enter at all: left to the TextView. */
    PASS,
}

internal object LauncherLogic {

    fun parseMode(trimmed: String): Mode = when {
        trimmed.startsWith("?") -> Mode.RECENTS
        trimmed.startsWith("#") -> Mode.TAG_FILTER
        trimmed.startsWith("~") -> Mode.COMMAND
        trimmed.startsWith("%") -> Mode.ORDER
        else -> Mode.NORMAL
    }

    /**
     * Every "~" command, the one list the overview, the abbreviations and the parser share. The
     * overview is bottom-anchored: the rare ones first, "~edit", the everyday one, nearest the prompt.
     */
    val COMMANDS: List<Pair<String, Command>> = listOfNotNull(
        ("~crash" to Command.CRASH).takeIf { DailyDriver.ENABLED },
        "~restart" to Command.RESTART,
        "~open" to Command.OPEN,
        "~edit" to Command.EDIT,
    )

    /** The command spelled exactly by [trimmed] (folded), or null; a bare "~" is none. */
    fun parseCommand(trimmed: String): Command? {
        // The fold drops U+0307, so "\u0307~open" would fold to a command its mode never showed.
        if (!trimmed.startsWith("~")) return null
        val q = foldLabel(trimmed)
        return COMMANDS.firstOrNull { it.first == q }?.second
    }

    fun commandsMatching(trimmed: String): List<Pair<String, Command>> {
        val q = foldLabel(trimmed)
        return COMMANDS.filter { it.first.startsWith(q) }
    }

    /**
     * An exact match, else an unambiguous abbreviation ("~e"). Exact must win should one spelling
     * ever prefix another. An ambiguous abbreviation (a bare "~") resolves to null, never a guess.
     * "~crash" is never abbreviated: "~c" means nothing in every build.
     */
    fun resolveCommand(trimmed: String): Command? {
        // As in parseCommand: commandsMatching folds the U+0307 away.
        if (!trimmed.startsWith("~")) return null
        parseCommand(trimmed)?.let { return it }
        return commandsMatching(trimmed).filter { it.second != Command.CRASH }.singleOrNull()?.second
    }

    /**
     * The only fold (hard rule 9) for labels, needles and tags. Locale.ROOT, never the device
     * locale: a Turkish phone would fold "Instagram" to a dotless ı and no "i" would find it.
     *  - NFC first, so a decomposed "café" matches a typed one.
     *  - ROOT lowercases İ to "i" + U+0307, and the dot is dropped — also after a letter with no
     *    precomposed dot form (q + U+0307 folds to "q"), which only makes the match looser.
     *  - Final ς becomes σ, so "ΟΔΟΣ" still finds "οδοστρωμα".
     *  - NFC again at the end: the fold must be its own fixed point, or stored tags change on every load.
     */
    fun foldLabel(label: String): String = nfc(
        nfc(label)
            .lowercase(Locale.ROOT)
            .replace(COMBINING_DOT_ABOVE, "")
            .replace('\u03C2', '\u03C3')
    )

    private fun nfc(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFC)

    /** [s] cut to at most [max] chars on a grapheme boundary — never half a surrogate pair or emoji sequence. */
    fun truncate(s: String, max: Int): String {
        if (s.length <= max) return s
        val it = BreakIterator.getCharacterInstance(Locale.ROOT).apply { setText(s) }
        val cut = it.preceding(max + 1).takeIf { b -> b != BreakIterator.DONE } ?: 0
        return s.substring(0, cut)
    }

    private const val COMBINING_DOT_ABOVE = "\u0307"

    /**
     * [apps] under their custom names from [names]. They arrive sorted (the enumeration sorts on the
     * IO thread), so only a changed label re-sorts — a name can move an app alphabetically.
     */
    fun named(apps: List<AppEntry>, names: Map<String, String>, locale: Locale = Locale.getDefault()): List<AppEntry> {
        var relabelled = false
        val shown = apps.map { e ->
            val label = names[e.key] ?: e.systemLabel
            if (label == e.label) e else e.withLabel(label).also { relabelled = true }
        }
        return if (relabelled) sortedForDrawer(shown, locale) else shown
    }

    /**
     * Alphabetical by the device language's collation (German files Ä under A, Swedish after Z),
     * then folded label and key, so the order is total. A Collator per call: it is not
     * thread-safe, and this runs on the IO thread too.
     */
    fun <T : Ordered> sortedForDrawer(all: List<T>, locale: Locale = Locale.getDefault()): List<T> {
        val collator = Collator.getInstance(locale)
        return all.sortedWith(
            compareBy<T, String>(collator) { it.labelLower }
                .thenBy { it.labelLower }
                .thenBy { it.key },
        )
    }

    fun <T : Ordered> search(all: List<T>, needle: String): List<T> {
        val n = matchForm(foldLabel(needle))
        return if (n.isEmpty()) all else all.filter { matchForm(it.labelLower).contains(n) }
    }

    /**
     * [found] (a [search]'s result, in drawer order) ranked by where [needle] matches: at the
     * label's start last, nearest the prompt where Enter launches it; at a later word's start above
     * it; inside a word on top. Each group keeps the drawer order.
     */
    fun <T : Ordered> ranked(found: List<T>, needle: String): List<T> {
        val n = matchForm(foldLabel(needle))
        if (n.isEmpty()) return found
        return found.sortedBy { entry ->
            val label = matchForm(entry.labelLower)
            when {
                label.startsWith(n) -> 2
                WORD_START.findAll(label).any { label.startsWith(n, it.range.first) } -> 1
                else -> 0
            }
        }
    }

    /**
     * A folded label or needle without Latin, Greek and Cyrillic accents (U+0300–U+036F after
     * NFD), so "uber" finds "Über". Only for matching: tags and names keep [foldLabel]'s form.
     * Other scripts' marks stay — a Japanese dakuten or a Devanagari vowel sign makes another word.
     */
    private fun matchForm(folded: String): String =
        // Composed again: decomposed, "\u304B" would be a substring of "\u304C" (+ U+3099).
        nfc(Normalizer.normalize(folded, Normalizer.Form.NFD).filterNot { it in '\u0300'..'\u036F' })

    /** A letter or digit after anything else: where a later word starts. */
    private val WORD_START = Regex("""(?<=[^\p{L}\p{N}])[\p{L}\p{N}]""")

    fun <T : Ordered> favoritesInDisplayOrder(all: List<T>, favorites: Collection<String>): List<T> {
        val rank = favorites.withIndex().associate { (i, p) -> p to i }
        return all.filter { it.key in rank }.sortedBy { rank[it.key] }
    }

    /** [slots]: per slot (phone, messenger, …) the candidate keys, preferred first. An app is picked once. */
    fun <T : Ordered> pickDefaultFavorites(all: List<T>, slots: List<List<String>>): List<String> {
        val launchable = all.mapTo(HashSet()) { it.key }
        val picked = LinkedHashSet<String>()
        for (candidates in slots) {
            candidates.firstOrNull { it in launchable }?.let { picked += it }
        }
        return picked.toList()
    }

    /** Everything not hidden, plus every favorite: favoriting overrides hiding. */
    fun <T : Ordered> drawer(all: List<T>, hidden: Set<String>, favorites: Collection<String>): List<T> =
        all.filter { it.key !in hidden || it.key in favorites }

    /**
     * The prefix of a star prompt, or null if [trimmed] is none. The star counts at either end
     * ("a*" == "*a"): where the IME leaves the caret after a programmatic setText is not ours to pin.
     */
    fun starPrefix(trimmed: String): String? =
        if (trimmed.startsWith("*") || trimmed.endsWith("*")) {
            // "* gm" is "*gm": no label starts with a space. "a *" keeps it (the word "a").
            trimmed.removePrefix("*").trimStart().removeSuffix("*")
        } else {
            null
        }

    /** The [drawer] narrowed to labels starting with [prefix], favorites last. Hidden apps stay out. */
    fun <T : Ordered> drawerStartingWith(
        all: List<T>,
        hidden: Set<String>,
        favorites: Collection<String>,
        prefix: String,
    ): List<T> {
        val p = matchForm(foldLabel(prefix))
        val base = drawer(all, hidden, favorites)
        val narrowed = if (p.isEmpty()) base else base.filter { matchForm(it.labelLower).startsWith(p) }
        return orderWithFavorites(narrowed, favorites)
    }

    /** Favorites last, in rank order — under isStackFromBottom the first favorite sits nearest the prompt. */
    fun <T : Ordered> orderWithFavorites(apps: List<T>, favorites: Collection<String>): List<T> {
        if (favorites.isEmpty()) return apps
        val rank = favorites.withIndex().associate { (i, p) -> p to i }
        val (favs, rest) = apps.partition { it.key in rank }
        return rest + favs.sortedBy { rank[it.key] }
    }

    fun pushRecent(current: List<String>, key: String, limit: Int): List<String> =
        (listOf(key) + current.filter { it != key }).take(limit)

    /** The newest [limit] of the apps still there, reversed: under isStackFromBottom the most
     *  recent sits nearest the prompt (the Enter target). */
    fun <T : Ordered> recentsInDisplayOrder(
        all: List<T>, recentKeys: List<String>, limit: Int = LauncherState.RECENTS_LIMIT,
    ): List<T> {
        val byKey = all.associateBy { it.key }
        return recentKeys.mapNotNull { byKey[it] }.take(limit).asReversed()
    }

    fun parseTags(raw: String): List<String> =
        raw.split(',').map(::canonicalTag).filter { it.isNotEmpty() }.distinct()
            .take(ConfigJson.MAX_TAGS_PER_APP)

    /**
     * The one definition of a tag; "" when nothing survives. Dropped silently rather than
     * rejected:
     *  - '#': the overview prompts "#" + name.
     *  - ',': a tag line lists them with ", " and is split on ',' again.
     *  - control and [isSpoofChar] characters.
     */
    fun canonicalTag(raw: String): String =
        // NFC again (a dropped character can let its neighbours combine), cut, and only then
        // trimmed: a cut can expose a space, and "##" prompts are trimmed.
        truncate(nfc(pairedOnly(foldLabel(raw).filter { it != '#' && it != ',' && !it.isISOControl() && !isSpoofChar(it) })), MAX_TAG_LENGTH)
            .trim()

    const val MAX_NAME_LENGTH = 100

    const val MAX_TAG_LENGTH = 50

    /**
     * Invisible characters that only make text look different from what it is (bidi controls,
     * directional marks, zero-width space/no-break space/word joiner) — a name could impersonate
     * another app. ZWJ/ZWNJ stay: emoji sequences, Persian and Hindi need them.
     */
    fun isSpoofChar(c: Char): Boolean =
        c in '\u202A'..'\u202E' || c in '\u2066'..'\u2069' ||
            c == '\u200E' || c == '\u200F' || c == '\u061C' ||
            c == '\u200B' || c == '\uFEFF' || c == '\u2060'

    /** A custom name as it may be stored (cut, then trimmed — see [canonicalTag]). */
    fun cleanName(raw: String): String =
        truncate(nfc(pairedOnly(raw.filter { !it.isISOControl() && !isSpoofChar(it) })), MAX_NAME_LENGTH).trim()

    /**
     * A run pasted or typed into the one-line prompt, as it may stand there: line breaks and tabs
     * are one space (a name copied over two lines still matches, its words apart; the field would
     * only show them as a space), any other control character goes. Null when the run needs
     * nothing — the keyboard's own typing passes untouched, a rewritten run would drop its
     * composing span.
     */
    fun promptRun(run: CharSequence): String? {
        if (run.none { it.isISOControl() }) return null
        return run.toString().replace(LINE_BREAKS, " ").filterNot { it.isISOControl() }
    }

    private val LINE_BREAKS = Regex("[\r\n\t]+")

    /**
     * [s] without a lone UTF-16 surrogate, which a JSON backup can carry as an escape ("\uD800")
     * and no font draws. After the other filters, so a pair they separated is whole again.
     */
    private fun pairedOnly(s: String): String = buildString(s.length) {
        s.codePoints().forEach { if (Character.getType(it) != Character.SURROGATE.toInt()) appendCodePoint(it) }
    }

    /** Which hint an empty list shows in [mode] (mirrors [rowsFor]). */
    fun emptyHint(mode: Mode, trimmed: String): EmptyHint = when (mode) {
        Mode.COMMAND -> EmptyHint.NO_COMMAND
        Mode.TAG_FILTER -> if (trimmed.substring(1).isBlank()) EmptyHint.NO_TAGS else EmptyHint.NO_TAG_MATCH
        Mode.ORDER -> EmptyHint.NO_MATCH  // never empty: the one OrderRow
        Mode.RECENTS -> if (trimmed.substring(1).isBlank()) EmptyHint.NO_RECENTS else EmptyHint.NO_MATCH
        Mode.NORMAL -> if (trimmed.isEmpty()) EmptyHint.NO_APPS else EmptyHint.NO_MATCH
    }

    /** Only tags of installed apps, so every tag in the "#" overview drills into a non-empty list. */
    fun <T : Ordered> tagsInUse(
        all: List<T>,
        tags: Map<String, List<String>>,
        locale: Locale = Locale.getDefault(),
    ): List<String> = sortedTags(all.flatMap { tags[it.key].orEmpty() }.distinct(), locale)

    /** The same collation as [sortedForDrawer], then plain string order, so the order is total. */
    fun sortedTags(tags: Collection<String>, locale: Locale = Locale.getDefault()): List<String> {
        val collator = Collator.getInstance(locale)
        return tags.sortedWith(compareBy<String, String>(collator) { it }.thenBy { it })
    }

    /**
     * Apps with a tag starting with [needle] (canonicalised like a stored tag, or it could hold
     * characters no tag can); an empty needle gives every tagged app. Hidden apps are included.
     * When no tag matches, the apps whose package matches ([packageMatches]): "#google", "#samsung".
     */
    fun <T : Ordered> tagged(all: List<T>, tags: Map<String, List<String>>, needle: String): List<T> {
        val n = canonicalTag(needle)
        val byTag = all.filter { entry ->
            val ts = tags[entry.key].orEmpty()
            if (n.isEmpty()) ts.isNotEmpty() else ts.any { it.startsWith(n) }
        }
        if (byTag.isNotEmpty() || n.isEmpty()) return byTag
        return all.filter { packageMatches(it.packageName, n) }
    }

    /** A package segment after the first starts with [needle]; the first ("com", "de") would match nearly everything. */
    fun packageMatches(packageName: String, needle: String): Boolean =
        packageName.lowercase(Locale.ROOT).split('.').drop(1).any { it.startsWith(needle) }

    /** The rows the list shows for the prompt. Every substring index is safe: the mode's sigil starts [trimmed]. */
    fun <T : Ordered> rowsFor(
        mode: Mode,
        trimmed: String,
        allApps: List<T>,
        hidden: Set<String>,
        favorites: Collection<String>,
        tags: Map<String, List<String>>,
        recentKeys: List<String>,
        byUsage: Boolean = false,
        usage: Map<String, Usage> = emptyMap(),
        now: Long = 0L,
    ): List<Row<T>> {
        if (mode == Mode.COMMAND) {
            return commandsMatching(trimmed).map { (name, cmd) -> CommandRow(name, cmd) }
        }
        // Only "%" itself: "%abc" is a typo, not a switch.
        if (mode == Mode.ORDER) return if (trimmed == "%") listOf(OrderRow(toUsage = !byUsage)) else emptyList()
        if (mode == Mode.TAG_FILTER && trimmed.substring(1).isBlank()) {
            return tagsInUse(allApps, tags).map { TagRow(it) }
        }
        val apps: List<T> = when (mode) {
            Mode.RECENTS -> search(recentsInDisplayOrder(allApps, recentKeys), trimmed.substring(1).trim())
            Mode.TAG_FILTER -> tagged(allApps, tags, trimmed.substring(1).trim())
            Mode.COMMAND, Mode.ORDER -> emptyList()
            Mode.NORMAL -> {
                val wildcard = starPrefix(trimmed)
                when {
                    // A to Z (or most used first, "%"), no favorites-last: the Activity anchors this view at the top.
                    trimmed.isEmpty() -> drawer(allApps, hidden, favorites)
                        .let { if (byUsage) UsageLog.byUsage(it, usage, now) else it }
                    wildcard != null -> drawerStartingWith(allApps, hidden, favorites, wildcard)
                    // Plain search spans ALL apps, so a hidden app stays reachable by name.
                    else -> ranked(search(allApps, trimmed), trimmed)
                }
            }
        }
        return apps.map { AppRow(it) }
    }

    /**
     * What Enter does; [lastRow] is the row nearest the prompt. The branch order is load-bearing:
     * a resolved command beats COMMAND mode's "still ambiguous, do nothing"; a blank prompt only
     * drops the keyboard and must come before the launch branch.
     */
    fun <T : Ordered> enterAction(
        mode: Mode,
        command: Command?,
        promptBlank: Boolean,
        lastRow: Row<T>?,
    ): DrawerAction<T> = when {
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

    /**
     * The character a key press types, or null. [chord] is Ctrl, Meta or LEFT Alt; right Alt is
     * AltGr ("@", "€") and types. A dead key's unicodeChar is negative (COMBINING_ACCENT) and
     * Character.toChars throws on it — isValidCodePoint already rejects it.
     */
    fun typedChar(unicodeChar: Int, chord: Boolean): String? {
        if (chord) return null
        if (unicodeChar == 0 || !Character.isValidCodePoint(unicodeChar) || Character.isISOControl(unicodeChar)) {
            return null
        }
        return String(Character.toChars(unicodeChar))
    }

    /**
     * Which editor-action call is THE Enter. A soft Enter arrives once with no event; a key
     * Enter arrives per key event (down, repeats, up), with the GO action (One UI) or none.
     * Only the first down acts; the rest is consumed — passed back, the TextView's handling of
     * the up drops the keyboard.
     */
    fun enterEdge(imeAction: Boolean, key: EnterKey?): EnterEdge = when {
        key == null -> if (imeAction) EnterEdge.ACT else EnterEdge.PASS
        !imeAction && !key.isEnterKey -> EnterEdge.PASS
        key.down && key.repeatCount == 0 -> EnterEdge.ACT
        else -> EnterEdge.CONSUME
    }

    /** What a tap on [row] does; a null [row] is a stale position (the list shrank under the tap). */
    fun <T : Ordered> tapAction(mode: Mode, row: Row<T>?): DrawerAction<T> = when (row) {
        null -> DrawerAction.None
        is TagRow -> DrawerAction.SetPrompt("#${row.name}")
        is CommandRow -> DrawerAction.Run(row.command)
        is OrderRow -> DrawerAction.ToggleOrder
        is AppRow -> when (mode) {
            Mode.NORMAL, Mode.RECENTS, Mode.TAG_FILTER -> DrawerAction.Launch(row.entry)
            Mode.COMMAND, Mode.ORDER -> DrawerAction.None
        }
    }
}
