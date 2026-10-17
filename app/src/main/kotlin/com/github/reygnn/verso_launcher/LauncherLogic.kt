package com.github.reygnn.verso_launcher

import java.text.BreakIterator
import java.text.Collator
import java.text.Normalizer
import java.util.Locale

/**
 * The launcher's pure decision logic: no framework, disk or shared state, so it is
 * JVM-testable. The Activity owns the UI wiring and the mutable config.
 */

/** The command-line mode, chosen by the prompt's leading sigil. */
internal enum class Mode {
    NORMAL, HIDDEN_EDIT, FAV_EDIT, FAV_REORDER, RECENTS, TAG_FILTER, TAG_EDIT, COMMAND
}

internal enum class EmptyHint { NO_MATCH, NO_APPS, NO_TAGS, NO_TAG_MATCH, NO_FAVORITES, NO_RECENTS, NO_COMMAND }

/** A one-shot "~" command: unlike a [Mode] it renders nothing, it acts once on Enter. */
internal enum class Command { RESTART, BACKUP, RESTORE, RESTORE_SAF, CLEANUP }

/** The config entries of uninstalled apps ([LauncherLogic.orphans]) and their count per section. */
internal data class Orphans(val keys: Set<String>, val favorites: Int, val hidden: Int, val names: Int, val tags: Int) {
    val isEmpty: Boolean get() = keys.isEmpty()
}

/** What the ordering/search logic needs from a row; tests fake it instead of building AppEntries. */
internal interface Ordered {
    val key: String
    val labelLower: String
}

/** A rendered list row. Rows without an app are `Row<Nothing>`, so they fit a `Row<T>` list for any T. */
internal sealed interface Row<out T : Ordered>
internal data class AppRow<out T : Ordered>(val entry: T) : Row<T>
internal data class TagRow(val name: String) : Row<Nothing>
internal data class CommandRow(val name: String, val command: Command) : Row<Nothing>

/** What an Enter on the command line does ([LauncherLogic.enterAction]); the Activity performs it. */
internal sealed interface EnterAction<out T : Ordered> {
    data class Run(val command: Command) : EnterAction<Nothing>
    data class LaunchApp<out T : Ordered>(val entry: T) : EnterAction<T>
    /** Drives the TextWatcher exactly as typing would. */
    data class SetPrompt(val text: String) : EnterAction<Nothing>
    data object DismissKeyboard : EnterAction<Nothing>
    data object None : EnterAction<Nothing>
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

/** What a tap on a list row does ([LauncherLogic.tapAction]); the Activity performs it. */
internal sealed interface TapAction<out T : Ordered> {
    data class Launch<out T : Ordered>(val entry: T) : TapAction<T>
    data class ToggleHidden<out T : Ordered>(val entry: T) : TapAction<T>
    data class ToggleFavorite<out T : Ordered>(val entry: T) : TapAction<T>
    data class ReorderPick<out T : Ordered>(val entry: T) : TapAction<T>
    data class ToggleTag<out T : Ordered>(val entry: T) : TapAction<T>
    data class Run(val command: Command) : TapAction<Nothing>
    data class SetPrompt(val text: String) : TapAction<Nothing>
    data object None : TapAction<Nothing>
}

internal object LauncherLogic {

    /** Doubled sigils are tested before their single form ("!!" before "!", "##" before "#"). */
    fun parseMode(trimmed: String): Mode = when {
        trimmed.startsWith("!!") -> Mode.FAV_REORDER
        trimmed.startsWith("##") -> Mode.TAG_EDIT
        trimmed.startsWith("-") -> Mode.HIDDEN_EDIT
        trimmed.startsWith("!") -> Mode.FAV_EDIT
        trimmed.startsWith("?") -> Mode.RECENTS
        trimmed.startsWith("#") -> Mode.TAG_FILTER
        trimmed.startsWith("~") -> Mode.COMMAND
        else -> Mode.NORMAL
    }

    /**
     * Every "~" command — the one list the overview, the abbreviations and the parser share.
     * Harmless ones first: the overview is bottom-anchored, so the config-changing ones sit
     * nearest the prompt, each guarded by an undo point (and a picker or a confirmation).
     */
    val COMMANDS: List<Pair<String, Command>> = listOf(
        "~restart" to Command.RESTART,
        "~backup" to Command.BACKUP,
        "~restore" to Command.RESTORE,
        "~restore-saf" to Command.RESTORE_SAF,
        "~cleanup" to Command.CLEANUP,
    )

    /**
     * The entries of [config] whose key is neither in [installed] nor [kept]. [installed] must
     * come from a COMPLETE enumeration, or a profile that failed to load would look uninstalled.
     */
    fun orphans(config: VersoConfig, installed: Set<String>, kept: (String) -> Boolean = { false }): Orphans {
        val verdict = HashMap<String, Boolean>()
        val gone = { key: String -> verdict.getOrPut(key) { key !in installed && !kept(key) } }
        val favorites = config.favorites.filter(gone)
        val hidden = config.hidden.filter(gone)
        val names = config.names.keys.filter(gone)
        val tags = config.tags.keys.filter(gone)
        return Orphans(
            keys = (favorites + hidden + names + tags).toSet(),
            favorites = favorites.size, hidden = hidden.size, names = names.size, tags = tags.size,
        )
    }

    /** The command spelled exactly by [trimmed] (folded), or null; a bare "~" is none. */
    fun parseCommand(trimmed: String): Command? {
        // The fold drops U+0307, so "\u0307~restore" would fold to a command its mode never showed.
        if (!trimmed.startsWith("~")) return null
        val q = foldLabel(trimmed)
        return COMMANDS.firstOrNull { it.first == q }?.second
    }

    fun commandsMatching(trimmed: String): List<Pair<String, Command>> {
        val q = foldLabel(trimmed)
        return COMMANDS.filter { it.first.startsWith(q) }
    }

    /**
     * An exact match, else an unambiguous abbreviation ("~b"). Exact must win: "~restore" also
     * prefixes "~restore-saf". An ambiguous abbreviation ("~r") resolves to null, never a guess.
     */
    fun resolveCommand(trimmed: String): Command? {
        // As in parseCommand: commandsMatching folds the U+0307 away.
        if (!trimmed.startsWith("~")) return null
        parseCommand(trimmed)?.let { return it }
        return commandsMatching(trimmed).singleOrNull()?.second
    }

    /**
     * The only fold (hard rule 9) for labels, needles and tags. Locale.ROOT, never the device
     * locale: a Turkish phone would fold "Instagram" to a dotless ı and no "i" would find it.
     *  - NFC first, so a decomposed "café" matches a typed one.
     *  - ROOT lowercases İ to "i" + U+0307; the dot is dropped (in NFC a lone U+0307 only comes from İ).
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

    /** [apps] under their custom names from [names], re-sorted — a name can move an app alphabetically. */
    /** [apps] arrive sorted (the enumeration sorts on the IO thread): only a changed label re-sorts. */
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

    /** Substring search by folded label; an empty needle returns [all]. */
    fun <T : Ordered> search(all: List<T>, needle: String): List<T> {
        val n = foldLabel(needle)
        return if (n.isEmpty()) all else all.filter { it.labelLower.contains(n) }
    }

    fun <T : Ordered> favoritesInDisplayOrder(all: List<T>, favorites: Collection<String>): List<T> {
        val rank = favorites.withIndex().associate { (i, p) -> p to i }
        return all.filter { it.key in rank }.sortedBy { rank[it.key] }
    }

    /**
     * Per slot (phone, messenger, …: candidate keys in preference order) the first candidate in
     * [all]; empty slots are skipped, an app is not picked twice, slot order is kept.
     */
    fun <T : Ordered> pickDefaultFavorites(all: List<T>, slots: List<List<String>>): List<String> {
        val launchable = all.mapTo(HashSet()) { it.key }
        val picked = LinkedHashSet<String>()
        for (candidates in slots) {
            candidates.firstOrNull { it in launchable }?.let { picked += it }
        }
        return picked.toList()
    }

    /** Everything not hidden, plus every favorite: favoriting overrides hiding. */
    fun <T : Ordered> drawer(all: List<T>, hidden: Set<String>, favorites: Set<String>): List<T> =
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
        favorites: Set<String>,
        prefix: String,
    ): List<T> {
        val p = foldLabel(prefix)
        val base = drawer(all, hidden, favorites)
        val narrowed = if (p.isEmpty()) base else base.filter { it.labelLower.startsWith(p) }
        return orderWithFavorites(narrowed, favorites)
    }

    /** Favorites last, in rank order — under isStackFromBottom the first favorite sits nearest the prompt. */
    fun <T : Ordered> orderWithFavorites(apps: List<T>, favorites: Collection<String>): List<T> {
        if (favorites.isEmpty()) return apps
        val rank = favorites.withIndex().associate { (i, p) -> p to i }
        val (favs, rest) = apps.partition { it.key in rank }
        return rest + favs.sortedBy { rank[it.key] }
    }

    /** [pickedKey] moved onto [targetKey]'s slot, or null when either is absent or both are the same. */
    fun reorder(order: List<String>, pickedKey: String, targetKey: String): List<String>? {
        val from = order.indexOf(pickedKey)
        val to = order.indexOf(targetKey)
        if (from < 0 || to < 0 || from == to) return null
        val out = order.toMutableList()
        out.removeAt(from)
        val dest = out.indexOf(targetKey)
        out.add(if (from < to) dest + 1 else dest, pickedKey)
        return out
    }

    fun pushRecent(current: List<String>, key: String, limit: Int): List<String> =
        (listOf(key) + current.filter { it != key }).take(limit)

    /** Reversed: under isStackFromBottom the most recent sits nearest the prompt (the Enter target). */
    fun <T : Ordered> recentsInDisplayOrder(all: List<T>, recentKeys: List<String>): List<T> {
        val byKey = all.associateBy { it.key }
        return recentKeys.asReversed().mapNotNull { byKey[it] }
    }

    fun parseTags(raw: String): List<String> =
        raw.split(',').map(::canonicalTag).filter { it.isNotEmpty() }.distinct()
            .take(ConfigJson.MAX_TAGS_PER_APP)

    /**
     * The one definition of a tag; "" when nothing survives. Dropped silently rather than
     * rejected:
     *  - '#': the overview prompts "#" + name, and "##…" would select the bulk editor instead.
     *  - ',': the dialog joins tags with ", " and splits on ',' again.
     *  - control and [isSpoofChar] characters.
     */
    fun canonicalTag(raw: String): String =
        // NFC again (a dropped character can let its neighbours combine), cut, and only then
        // trimmed: a cut can expose a space, and "##" prompts are trimmed.
        truncate(nfc(foldLabel(raw).filter { it != '#' && it != ',' && !it.isISOControl() && !isSpoofChar(it) }), MAX_TAG_LENGTH)
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
        truncate(nfc(raw.filter { !it.isISOControl() && !isSpoofChar(it) }), MAX_NAME_LENGTH).trim()

    /**
     * [tag] (canonicalised) added at the end or removed. May return an empty list, which the
     * caller must store as a removed key: the file never holds an empty tag list.
     */
    fun toggleTag(current: List<String>?, tag: String): List<String> {
        val folded = canonicalTag(tag)
        val list = current.orEmpty()
        if (folded.isEmpty()) return list
        // minus removes only the first occurrence — why every write path de-duplicates.
        if (folded in list) return list - folded
        // One past ConfigJson's limit and every backup of this config would be refused.
        return if (list.size >= ConfigJson.MAX_TAGS_PER_APP) list else list + folded
    }

    /** Which hint an empty list shows in [mode] (mirrors [rowsFor]). */
    fun emptyHint(mode: Mode, trimmed: String, tagEditTag: String?): EmptyHint = when (mode) {
        Mode.TAG_EDIT -> if (tagEditTag == null) EmptyHint.NO_TAGS else EmptyHint.NO_APPS
        Mode.COMMAND -> EmptyHint.NO_COMMAND
        Mode.TAG_FILTER -> if (trimmed.substring(1).isBlank()) EmptyHint.NO_TAGS else EmptyHint.NO_TAG_MATCH
        Mode.FAV_REORDER -> EmptyHint.NO_FAVORITES
        Mode.RECENTS -> if (trimmed.substring(1).isBlank()) EmptyHint.NO_RECENTS else EmptyHint.NO_MATCH
        Mode.HIDDEN_EDIT, Mode.FAV_EDIT -> if (trimmed.substring(1).isBlank()) EmptyHint.NO_APPS else EmptyHint.NO_MATCH
        Mode.NORMAL -> if (trimmed.isEmpty()) EmptyHint.NO_APPS else EmptyHint.NO_MATCH
    }

    /** The edit modes: Enter there means "done" (it leaves the mode), not "go". */
    fun isEditMode(mode: Mode): Boolean =
        mode == Mode.HIDDEN_EDIT || mode == Mode.FAV_EDIT || mode == Mode.FAV_REORDER || mode == Mode.TAG_EDIT

    /**
     * The name to store, or null to clear the override. Compared EXACTLY with [systemLabel]:
     * "gmail" over "Gmail" changes what the row shows, so it is a real override.
     */
    fun resolveNameOverride(name: String, systemLabel: String): String? =
        if (name.isEmpty() || name == systemLabel) null else name

    /** Every tag defined, uninstalled apps' included — the autocomplete pool and the "##" overview. */
    fun allTags(tags: Map<String, List<String>>, locale: Locale = Locale.getDefault()): List<String> =
        sortedTags(tags.values.flatten().distinct(), locale)

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
     */
    fun <T : Ordered> tagged(all: List<T>, tags: Map<String, List<String>>, needle: String): List<T> {
        val n = canonicalTag(needle)
        return all.filter { entry ->
            val ts = tags[entry.key].orEmpty()
            if (n.isEmpty()) ts.isNotEmpty() else ts.any { it.startsWith(n) }
        }
    }

    /** The canonical tag behind a "##tag" prompt, or null while the bare-"##" overview shows ("##", "###", "##,"). */
    fun tagEditTagFor(mode: Mode, trimmed: String): String? =
        if (mode == Mode.TAG_EDIT) canonicalTag(trimmed.substring(2)).takeIf { it.isNotEmpty() }
        else null

    /** The rows the list shows for the prompt. Every substring index is safe: the mode's sigil starts [trimmed]. */
    fun <T : Ordered> rowsFor(
        mode: Mode,
        trimmed: String,
        allApps: List<T>,
        hidden: Set<String>,
        favorites: Set<String>,
        tags: Map<String, List<String>>,
        recentKeys: List<String>,
        tagEditTag: String?,
    ): List<Row<T>> {
        // allTags, not tagsInUse: a tag whose apps are all gone is the one to re-assign.
        if (mode == Mode.TAG_EDIT && tagEditTag == null) {
            return allTags(tags).map { TagRow(it) }
        }
        if (mode == Mode.COMMAND) {
            return commandsMatching(trimmed).map { (name, cmd) -> CommandRow(name, cmd) }
        }
        if (mode == Mode.TAG_FILTER && trimmed.substring(1).isBlank()) {
            return tagsInUse(allApps, tags).map { TagRow(it) }
        }
        val apps: List<T> = when (mode) {
            // Text after "!!" is ignored: filtering would scramble the positions the reorder acts on.
            Mode.FAV_REORDER -> favoritesInDisplayOrder(allApps, favorites)
            Mode.RECENTS -> search(recentsInDisplayOrder(allApps, recentKeys), trimmed.substring(1).trim())
            Mode.TAG_FILTER -> tagged(allApps, tags, trimmed.substring(1).trim())
            Mode.HIDDEN_EDIT, Mode.FAV_EDIT -> search(allApps, trimmed.substring(1).trim())
            // Not reordered tagged-first: rows would jump under the finger.
            Mode.TAG_EDIT -> allApps
            Mode.COMMAND -> emptyList()
            Mode.NORMAL -> {
                val wildcard = starPrefix(trimmed)
                when {
                    // Strictly alphabetical (no favorites-last): the Activity anchors this view at the top.
                    trimmed.isEmpty() -> drawer(allApps, hidden, favorites)
                    wildcard != null -> drawerStartingWith(allApps, hidden, favorites, wildcard)
                    // Plain search spans ALL apps, so a hidden app stays reachable by name.
                    else -> search(allApps, trimmed)
                }
            }
        }
        return apps.map { AppRow(it) }
    }

    /** The edit-mode marker; each pair of markers has the same width, so labels stay aligned. */
    fun rowPrefix(
        mode: Mode,
        isHidden: Boolean,
        isFavorite: Boolean,
        isPicked: Boolean,
        isTagged: Boolean,
    ): String = when (mode) {
        Mode.HIDDEN_EDIT -> if (isHidden) "[x] " else "[ ] "
        Mode.FAV_EDIT -> if (isFavorite) "[x] " else "[ ] "
        Mode.FAV_REORDER -> if (isPicked) "\u00BB " else "  "
        Mode.TAG_EDIT -> if (isTagged) "[x] " else "[ ] "
        Mode.NORMAL, Mode.RECENTS, Mode.TAG_FILTER, Mode.COMMAND -> ""
    }

    /**
     * What Enter does; [lastRow] is the row nearest the prompt. The branch order is load-bearing:
     * a resolved command beats COMMAND mode's "still ambiguous, do nothing"; TAG_EDIT never
     * launches; a blank prompt only drops the keyboard and must come before the launch branch.
     */
    fun <T : Ordered> enterAction(
        mode: Mode,
        command: Command?,
        promptBlank: Boolean,
        lastRow: Row<T>?,
    ): EnterAction<T> = when {
        command != null -> EnterAction.Run(command)
        mode == Mode.COMMAND -> EnterAction.None
        mode == Mode.TAG_EDIT -> when (lastRow) {
            is TagRow -> EnterAction.SetPrompt("##${lastRow.name}")
            else -> EnterAction.SetPrompt("")
        }
        promptBlank -> EnterAction.DismissKeyboard
        mode == Mode.NORMAL || mode == Mode.RECENTS || mode == Mode.TAG_FILTER -> when (lastRow) {
            is AppRow -> EnterAction.LaunchApp(lastRow.entry)
            is TagRow -> EnterAction.SetPrompt("#${lastRow.name}")
            else -> EnterAction.None
        }
        else -> EnterAction.SetPrompt("")
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
    fun <T : Ordered> tapAction(mode: Mode, row: Row<T>?): TapAction<T> = when (row) {
        null -> TapAction.None
        is TagRow -> TapAction.SetPrompt(if (mode == Mode.TAG_EDIT) "##${row.name}" else "#${row.name}")
        is CommandRow -> TapAction.Run(row.command)
        is AppRow -> when (mode) {
            Mode.NORMAL, Mode.RECENTS, Mode.TAG_FILTER -> TapAction.Launch(row.entry)
            Mode.HIDDEN_EDIT -> TapAction.ToggleHidden(row.entry)
            Mode.FAV_EDIT -> TapAction.ToggleFavorite(row.entry)
            Mode.FAV_REORDER -> TapAction.ReorderPick(row.entry)
            Mode.TAG_EDIT -> TapAction.ToggleTag(row.entry)
            Mode.COMMAND -> TapAction.None
        }
    }
}
