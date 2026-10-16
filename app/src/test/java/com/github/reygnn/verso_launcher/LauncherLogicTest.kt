package com.github.reygnn.verso_launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import java.util.Locale

/**
 * JVM unit tests for the pure launcher logic. No Android runtime, no Robolectric —
 * these run on the plain JVM because [LauncherLogic] touches no framework types.
 * The rows are a trivial [Ordered] fake, so the tests never build a ComponentName.
 */
class LauncherLogicTest {

    @get:Rule val locale = DefaultLocaleRule()


    /** Rows whose key doubles as the (already ROOT-folded) label, for ordering tests. */
    private fun rows(vararg keys: String): List<OrderedFake> = keys.map { OrderedFake(it, it) }

    private fun List<Ordered>.keys(): List<String> = map { it.key }

    // ---- parseMode ----------------------------------------------------------

    @Test fun `empty and plain text are NORMAL`() {
        assertEquals(Mode.NORMAL, LauncherLogic.parseMode(""))
        assertEquals(Mode.NORMAL, LauncherLogic.parseMode("gmail"))
        assertEquals(Mode.NORMAL, LauncherLogic.parseMode("*"))
    }

    @Test fun `dash is hidden-edit`() {
        assertEquals(Mode.HIDDEN_EDIT, LauncherLogic.parseMode("-"))
        assertEquals(Mode.HIDDEN_EDIT, LauncherLogic.parseMode("-maps"))
    }

    @Test fun `hash is tag-filter`() {
        assertEquals(Mode.TAG_FILTER, LauncherLogic.parseMode("#"))
        assertEquals(Mode.TAG_FILTER, LauncherLogic.parseMode("#work"))
    }

    @Test fun `single bang is fav-edit`() {
        assertEquals(Mode.FAV_EDIT, LauncherLogic.parseMode("!"))
        assertEquals(Mode.FAV_EDIT, LauncherLogic.parseMode("!maps"))
    }

    @Test fun `double bang is reorder and wins over single bang`() {
        assertEquals(Mode.FAV_REORDER, LauncherLogic.parseMode("!!"))
        assertEquals(Mode.FAV_REORDER, LauncherLogic.parseMode("!!maps"))
    }

    @Test fun `question mark is recents`() {
        assertEquals(Mode.RECENTS, LauncherLogic.parseMode("?"))
        assertEquals(Mode.RECENTS, LauncherLogic.parseMode("?maps"))
    }

    /** "##" must be tested BEFORE "#", the same trap "!!" vs "!" sets. If the order in
     *  parseMode ever flips, "##" silently becomes a tag filter for a tag named "#". */
    @Test fun `double hash is TAG_EDIT, single hash stays TAG_FILTER`() {
        assertEquals(Mode.TAG_EDIT, LauncherLogic.parseMode("##"))
        assertEquals(Mode.TAG_EDIT, LauncherLogic.parseMode("##work"))
        assertEquals(Mode.TAG_FILTER, LauncherLogic.parseMode("#"))
        assertEquals(Mode.TAG_FILTER, LauncherLogic.parseMode("#work"))
    }

    // ---- canonicalTag -------------------------------------------------------

    /** The finding this exists for: a tag beginning with "#" made the tag overview build
     *  "#" + "#work" = "##work", which parses as the BULK EDITOR for a truncated tag —
     *  so the tag was unreachable from the very list offering it. */
    @Test fun `canonicalTag strips the hash that would flip the sigil`() {
        assertEquals("work", LauncherLogic.canonicalTag("#work"))
        assertEquals("work", LauncherLogic.canonicalTag("##work"))
        assertEquals("work", LauncherLogic.canonicalTag("wo#rk"))
    }

    /** The second finding: a comma survives storage but the long-press dialog joins with
     *  ", " and re-splits on "," — so the tag was torn in two by an unrelated rename. */
    @Test fun `canonicalTag strips the comma that would tear a tag in two`() {
        assertEquals("foobar", LauncherLogic.canonicalTag("foo,bar"))
    }

    /** Other sigils are NOT stripped: "#" + "!work" = "#!work", and since only position 0
     *  picks the mode that still lands in the tag filter. Throwing them away would lose
     *  perfectly usable tag names for no gain. */
    @Test fun `canonicalTag keeps the harmless sigils`() {
        assertEquals("!work", LauncherLogic.canonicalTag("!work"))
        assertEquals("-work", LauncherLogic.canonicalTag("-work"))
        assertEquals("~work", LauncherLogic.canonicalTag("~work"))
        assertEquals("?work", LauncherLogic.canonicalTag("?work"))
        assertEquals("my work", LauncherLogic.canonicalTag("my work"))
    }

    @Test fun `canonicalTag folds, trims and drops control characters`() {
        assertEquals("work", LauncherLogic.canonicalTag("  WORK  "))
        assertEquals("work", LauncherLogic.canonicalTag("work\n"))
        assertEquals("work", LauncherLogic.canonicalTag("# work"))
    }

    @Test fun `canonicalTag returns empty when nothing survives`() {
        assertEquals("", LauncherLogic.canonicalTag("###"))
        assertEquals("", LauncherLogic.canonicalTag(",,,"))
        assertEquals("", LauncherLogic.canonicalTag("   "))
        assertEquals("", LauncherLogic.canonicalTag(""))
    }

    /** Same ROOT-folding reason as everywhere else. */
    @Test fun `canonicalTag folds with ROOT, not the device locale`() {
        Locale.setDefault(Locale.forLanguageTag("tr"))
        assertEquals("i", LauncherLogic.canonicalTag("I"))
    }

    /** The dialog's separator must still work: canonicalTag only sees ONE tag at a time,
     *  because parseTags splits on "," first. */
    @Test fun `parseTags still splits on commas and now canonicalises each piece`() {
        assertEquals(listOf("work", "chat"), LauncherLogic.parseTags("#work, Chat"))
        assertEquals(listOf("work"), LauncherLogic.parseTags("work, #work, WORK"))
        assertEquals(emptyList<String>(), LauncherLogic.parseTags("#, ,,"))
    }

    /** "##" and the dialog must agree, or a tag is reachable one way and not the other —
     *  which is exactly how the two findings arose. */
    @Test fun `the bulk editor and the dialog canonicalise identically`() {
        for (raw in listOf("#work", "WORK", "  work  ", "wo#rk", "foo,bar")) {
            val viaDialog = LauncherLogic.parseTags(raw).firstOrNull().orEmpty()
            val viaBulk = LauncherLogic.toggleTag(null, raw).firstOrNull().orEmpty()
            if (raw == "foo,bar") continue  // the dialog SPLITS this one; see the test above
            assertEquals("disagreement on \"$raw\"", viaDialog, viaBulk)
        }
    }

    @Test fun `toggleTag ignores a tag that canonicalises to nothing`() {
        assertEquals(listOf("work"), LauncherLogic.toggleTag(listOf("work"), "###"))
        assertEquals(emptyList<String>(), LauncherLogic.toggleTag(null, "  "))
    }

    // ---- toggleTag ----------------------------------------------------------

    @Test fun `toggleTag adds a tag an app does not carry`() {
        assertEquals(listOf("work"), LauncherLogic.toggleTag(null, "work"))
        assertEquals(listOf("games", "work"), LauncherLogic.toggleTag(listOf("games"), "work"))
    }

    @Test fun `toggleTag removes a tag an app already carries`() {
        assertEquals(listOf("games"), LauncherLogic.toggleTag(listOf("games", "work"), "work"))
    }

    /** The caller must store this as a REMOVED key, never an empty list — ConfigJson
     *  drops empty tag lists on both sides, so keeping one would make the in-memory
     *  shape disagree with the file. */
    @Test fun `toggleTag can empty an app's tag list`() {
        assertEquals(emptyList<String>(), LauncherLogic.toggleTag(listOf("work"), "work"))
    }

    /** Stored tags are canonical, so an unfolded needle must not create a near-duplicate. */
    @Test fun `toggleTag folds the incoming tag`() {
        assertEquals(emptyList<String>(), LauncherLogic.toggleTag(listOf("work"), "WORK"))
        assertEquals(listOf("work"), LauncherLogic.toggleTag(null, "Work"))
    }

    /** Same ROOT-folding reason as everywhere else: a Turkish default locale must not
     *  turn a typed "##WORK" into a dotless-i tag that never matches the stored one. */
    @Test fun `toggleTag folds with ROOT, not the device locale`() {
        Locale.setDefault(Locale.forLanguageTag("tr"))
        assertEquals(emptyList<String>(), LauncherLogic.toggleTag(listOf("i"), "I"))
    }

    @Test fun `toggleTag does not mutate the list it is given`() {
        val current = listOf("work")
        LauncherLogic.toggleTag(current, "games")
        assertEquals(listOf("work"), current)
    }

    // ---- resolveNameOverride ------------------------------------------------

    @Test fun `resolveNameOverride clears on an empty name`() {
        assertNull(LauncherLogic.resolveNameOverride("", "Gmail"))
    }

    @Test fun `resolveNameOverride clears when the typed name equals the system label`() {
        // Re-typing the app's own label must NOT persist a redundant override, or clearing
        // one back to the original would be impossible.
        assertNull(LauncherLogic.resolveNameOverride("Gmail", "Gmail"))
    }

    @Test fun `resolveNameOverride stores a genuinely different name`() {
        assertEquals("Mail", LauncherLogic.resolveNameOverride("Mail", "Gmail"))
    }

    /** The compare is case-sensitive on purpose: a lowercase "gmail" over a system "Gmail"
     *  changes what the row shows, so it is a real override, not a redundant one. */
    @Test fun `resolveNameOverride treats a case-only difference as a real override`() {
        assertEquals("gmail", LauncherLogic.resolveNameOverride("gmail", "Gmail"))
    }

    /** ...and whitespace-sensitive too: the dialog hands over a trimmed name, so a trimmed
     *  "Gmail" over a system that reports " Gmail " with spaces is the user asking for the
     *  spaceless label — storing it is correct, not redundant. */
    @Test fun `resolveNameOverride stores a trimmed name over a padded system label`() {
        assertEquals("Gmail", LauncherLogic.resolveNameOverride("Gmail", " Gmail "))
    }

    // ---- parseCommand -------------------------------------------------------

    @Test fun `every command and the bare tilde alias parse`() {
        assertEquals(Command.RELOAD, LauncherLogic.parseCommand("~"))
        assertEquals(Command.RELOAD, LauncherLogic.parseCommand("~load"))
        assertEquals(Command.SAVE, LauncherLogic.parseCommand("~save"))
        assertEquals(Command.BACKUP, LauncherLogic.parseCommand("~backup"))
        assertEquals(Command.RESTORE, LauncherLogic.parseCommand("~restore"))
        assertEquals(Command.RESTORE_SAF, LauncherLogic.parseCommand("~restore-saf"))
    }

    /** "~restore" is a strict PREFIX of "~restore-saf". Because the match is exact
     *  neither can shadow the other, whichever order the branches are written in —
     *  the trap parseMode has with "!!" vs "!" simply doesn't exist here. */
    @Test fun `restore and restore-saf do not shadow each other`() {
        assertEquals(Command.RESTORE, LauncherLogic.parseCommand("~restore"))
        assertEquals(Command.RESTORE_SAF, LauncherLogic.parseCommand("~restore-saf"))
        assertNull(LauncherLogic.parseCommand("~restore-"))
        assertNull(LauncherLogic.parseCommand("~restore-safe"))
        assertNull(LauncherLogic.parseCommand("~restoresaf"))
    }

    @Test fun `commands are case-insensitive`() {
        assertEquals(Command.BACKUP, LauncherLogic.parseCommand("~BACKUP"))
        assertEquals(Command.RESTORE, LauncherLogic.parseCommand("~ReStOrE"))
        assertEquals(Command.RESTORE_SAF, LauncherLogic.parseCommand("~Restore-SAF"))
    }

    /** Folding must be ROOT, not the device locale: a Turkish default would map the
     *  "I" of a typed "~RESTORE" to a dotless i and the command would stop resolving. */
    @Test fun `commands still parse under a Turkish default locale`() {
        Locale.setDefault(Locale.forLanguageTag("tr"))
        assertEquals(Command.RESTORE, LauncherLogic.parseCommand("~RESTORE"))
    }

    /** The exact-match rule: "~" must not swallow a search that merely starts with it,
     *  which is what keeps the sigil from ever needing an escape. */
    @Test fun `a prefix or a partial is not a command`() {
        assertNull(LauncherLogic.parseCommand("~saved"))
        assertNull(LauncherLogic.parseCommand("~ save"))
        assertNull(LauncherLogic.parseCommand("~sav"))
        assertNull(LauncherLogic.parseCommand("~backup now"))
        assertNull(LauncherLogic.parseCommand("save"))
        assertNull(LauncherLogic.parseCommand(""))
    }

    /** "~" IS a mode now: it renders the command overview while being typed. It used
     *  to stay NORMAL (so "~foo" could be searched for); the overview is worth the
     *  trade, and every other sigil already makes it. */
    @Test fun `tilde text is COMMAND mode`() {
        assertEquals(Mode.COMMAND, LauncherLogic.parseMode("~"))
        assertEquals(Mode.COMMAND, LauncherLogic.parseMode("~backup"))
        assertEquals(Mode.COMMAND, LauncherLogic.parseMode("~zzz"))
    }

    // ---- commandsMatching / resolveCommand -----------------------------------

    @Test fun `a bare tilde lists every command`() {
        assertEquals(LauncherLogic.COMMANDS.size, LauncherLogic.commandsMatching("~").size)
    }

    @Test fun `typing narrows the overview`() {
        assertEquals(listOf("~backup"), LauncherLogic.commandsMatching("~b").map { it.first })
        assertEquals(
            listOf("~restore", "~restore-saf", "~reset"),
            LauncherLogic.commandsMatching("~r").map { it.first },
        )
        assertEquals(listOf("~restore", "~restore-saf"), LauncherLogic.commandsMatching("~rest").map { it.first })
        assertEquals(listOf("~restore-saf"), LauncherLogic.commandsMatching("~restore-").map { it.first })
        assertEquals(emptyList<String>(), LauncherLogic.commandsMatching("~zzz").map { it.first })
    }

    @Test fun `an unambiguous abbreviation resolves`() {
        assertEquals(Command.RELOAD, LauncherLogic.resolveCommand("~l"))
        assertEquals(Command.SAVE, LauncherLogic.resolveCommand("~s"))
        assertEquals(Command.BACKUP, LauncherLogic.resolveCommand("~b"))
        assertEquals(Command.RESTORE_SAF, LauncherLogic.resolveCommand("~restore-"))
    }

    /** The load-bearing rule: "~restore" both NAMES a command and PREFIXES
     *  "~restore-saf". Spelling one out in full must mean the one spelled, never the
     *  longer neighbour — so exact has to beat prefix. */
    @Test fun `an exact match beats a prefix`() {
        assertEquals(Command.RESTORE, LauncherLogic.resolveCommand("~restore"))
        assertEquals(Command.RESTORE_SAF, LauncherLogic.resolveCommand("~restore-saf"))
    }

    /** A bare "~" prefixes EVERY command, so only the exact-match alias keeps Enter on
     *  it doing what it always did — reload, not "ambiguous, do nothing". */
    @Test fun `a bare tilde still resolves to reload`() {
        assertEquals(Command.RELOAD, LauncherLogic.resolveCommand("~"))
    }

    @Test fun `an ambiguous abbreviation resolves to nothing`() {
        assertNull(LauncherLogic.resolveCommand("~r"))
        assertNull(LauncherLogic.resolveCommand("~re"))
        assertNull(LauncherLogic.resolveCommand("~restor"))
    }

    /** ~reset starts over: it must take more than a slip of the finger. Every abbreviation up
     *  to "~res" still fits ~restore too, so it resolves to nothing; "~rese" is the shortest. */
    @Test fun `reset needs at least four letters to run on Enter`() {
        assertNull(LauncherLogic.resolveCommand("~res"))
        assertEquals(Command.RESET, LauncherLogic.resolveCommand("~rese"))
        assertEquals(Command.RESET, LauncherLogic.resolveCommand("~reset"))
    }

    /** The list and the test must not drift: a command added to COMMANDS without a line
     *  here would otherwise look covered. */
    @Test fun `parseCommand covers every entry in COMMANDS`() {
        for ((spelling, command) in LauncherLogic.COMMANDS) {
            assertEquals(spelling, command, LauncherLogic.parseCommand(spelling))
        }
    }

    /**
     * parseCommand's case handling was pinned; these two fold separately and were not.
     * Drop either fold and "~B" shows an empty overview while "~RESTORE-" stops resolving,
     * with nothing failing.
     *
     * Deliberately NOT called a ROOT-folding test: no command spelling currently contains
     * an "i", so ROOT and a Turkish locale fold every one of them identically and no test
     * could tell the two apart. Claiming otherwise would be theatre. The next test guards
     * the day that stops being true.
     */
    @Test fun `commandsMatching and resolveCommand are case-insensitive too`() {
        assertEquals(listOf("~backup"), LauncherLogic.commandsMatching("~B").map { it.first })
        assertEquals(Command.BACKUP, LauncherLogic.resolveCommand("~B"))
        assertEquals(Command.RESTORE_SAF, LauncherLogic.resolveCommand("~RESTORE-"))
        assertEquals(Command.RELOAD, LauncherLogic.resolveCommand("~LOAD"))
    }

    /**
     * A forward guard, not a check of today's behaviour. The moment someone adds a command
     * whose spelling contains an "i" — "~import" is the obvious one — the difference
     * between ROOT and a device-locale fold becomes observable, and a Turkish phone would
     * stop resolving it. This fails then, at the commit that introduces the command,
     * instead of on a user's phone.
     */
    @Test fun `every command spelling survives a Turkish uppercase round trip`() {
        Locale.setDefault(Locale.forLanguageTag("tr"))
        for ((spelling, _) in LauncherLogic.COMMANDS) {
            assertEquals(
                "\"$spelling\" does not survive being typed in caps on a Turkish device",
                spelling,
                LauncherLogic.foldLabel(spelling.uppercase(Locale.getDefault())),
            )
        }
    }

    @Test fun `an abbreviation matching nothing resolves to nothing`() {
        assertNull(LauncherLogic.resolveCommand("~zzz"))
        assertNull(LauncherLogic.resolveCommand("~backupp"))
    }

    // ---- reorder ------------------------------------------------------------

    @Test fun `reorder moves a row down onto the target's slot`() {
        assertEquals(listOf("b", "c", "a", "d"), LauncherLogic.reorder(listOf("a", "b", "c", "d"), "a", "c"))
    }

    @Test fun `reorder moves a row up onto the target's slot`() {
        assertEquals(listOf("a", "d", "b", "c"), LauncherLogic.reorder(listOf("a", "b", "c", "d"), "d", "b"))
    }

    @Test fun `reorder handles adjacent moves both directions`() {
        assertEquals(listOf("b", "a", "c"), LauncherLogic.reorder(listOf("a", "b", "c"), "a", "b"))
        assertEquals(listOf("a", "c", "b"), LauncherLogic.reorder(listOf("a", "b", "c"), "c", "b"))
    }

    @Test fun `reorder moves a row onto the very first slot`() {
        // Target at index 0 (an up-move inserting AT dest 0) — the boundary the other
        // cases never exercise; a future off-by-one here would land the row at index 1.
        assertEquals(listOf("c", "a", "b"), LauncherLogic.reorder(listOf("a", "b", "c"), "c", "a"))
    }

    @Test fun `reorder moves a row onto the very last slot`() {
        // The first-slot boundary is covered above; the last-slot boundary — a down-move
        // inserting AFTER the final element (dest + 1 at the end) — was not.
        assertEquals(listOf("b", "c", "d", "a"), LauncherLogic.reorder(listOf("a", "b", "c", "d"), "a", "d"))
    }

    @Test fun `reorder returns null for a no-op or impossible move`() {
        assertNull(LauncherLogic.reorder(listOf("a", "b"), "a", "a"))   // same row
        assertNull(LauncherLogic.reorder(listOf("a", "b"), "x", "a"))   // picked absent
        assertNull(LauncherLogic.reorder(listOf("a", "b"), "a", "x"))   // target absent
    }

    @Test fun `reorder does not mutate its input`() {
        val order = listOf("a", "b", "c")
        LauncherLogic.reorder(order, "a", "c")
        assertEquals(listOf("a", "b", "c"), order)
    }

    @Test fun `reorder on a single-element list is always a no-op`() {
        assertNull(LauncherLogic.reorder(listOf("only"), "only", "only"))  // same row
        assertNull(LauncherLogic.reorder(listOf("only"), "only", "x"))     // target absent
    }

    // ---- foldLabel ----------------------------------------------------------

    @Test fun `foldLabel folds the capital dotted I to a plain i`() {
        // ROOT lowercases İ (U+0130) to "i" + U+0307; left in, "İşCep" never matched "iş".
        assertEquals("işcep", LauncherLogic.foldLabel("İşCep"))
        assertEquals("istanbulkart", LauncherLogic.foldLabel("İstanbulkart"))
        assertEquals(listOf("p/is"), LauncherLogic.search(listOf(OrderedFake("p/is", LauncherLogic.foldLabel("İşCep"))), "iş").keys())
        assertEquals(listOf("p/is"), LauncherLogic.search(listOf(OrderedFake("p/is", LauncherLogic.foldLabel("İşCep"))), "İŞ").keys())
    }

    @Test fun `foldLabel makes decomposed and composed text the same`() {
        val decomposed = "Cafe\u0301"  // e + combining acute
        assertEquals("café", LauncherLogic.foldLabel(decomposed))
        assertEquals(listOf("p/c"), LauncherLogic.search(listOf(OrderedFake("p/c", LauncherLogic.foldLabel(decomposed))), "café").keys())
    }

    @Test fun `foldLabel keeps letters that really carry a dot above`() {
        assertEquals("żabka ėjimas", LauncherLogic.foldLabel("Żabka Ėjimas"))
    }

    @Test fun `a tag typed with the dotted capital I is the same tag as the plain one`() {
        assertEquals("iş", LauncherLogic.canonicalTag("İş"))
    }

    // ---- names and tags: no look-alike characters, bounded length ---------

    @Test fun `cleanName drops invisible bidi and zero-width characters`() {
        // U+202E (right-to-left override) can make "lang" display reversed; U+200B is invisible.
        assertEquals("Signal", LauncherLogic.cleanName("Sig\u200Bnal"))
        assertEquals("evil", LauncherLogic.cleanName("\u202Eevil\u202C"))
        assertEquals("a b", LauncherLogic.cleanName("  a\u0000 b\n "))
    }

    @Test fun `cleanName keeps the joiners real scripts and emoji need`() {
        val family = "\uD83D\uDC68\u200D\uD83D\uDC69"  // man ZWJ woman
        assertEquals(family, LauncherLogic.cleanName(family))
        val persian = "\u0645\u06CC\u200C\u0634\u0648\u062F"  // ZWNJ inside a word
        assertEquals(persian, LauncherLogic.cleanName(persian))
    }

    @Test fun `cleanName caps the length`() {
        assertEquals(LauncherLogic.MAX_NAME_LENGTH, LauncherLogic.cleanName("x".repeat(500)).length)
    }

    @Test fun `canonicalTag drops look-alike characters and caps the length too`() {
        assertEquals("work", LauncherLogic.canonicalTag("wo\u200Brk"))
        assertEquals(LauncherLogic.MAX_TAG_LENGTH, LauncherLogic.canonicalTag("t".repeat(500)).length)
    }

    @Test fun `an empty list says why, per mode`() {
        assertEquals(EmptyHint.NO_APPS, LauncherLogic.emptyHint(Mode.NORMAL, "", null))
        assertEquals(EmptyHint.NO_MATCH, LauncherLogic.emptyHint(Mode.NORMAL, "xyz", null))
        assertEquals(EmptyHint.NO_MATCH, LauncherLogic.emptyHint(Mode.NORMAL, "x*", null))
        assertEquals(EmptyHint.NO_APPS, LauncherLogic.emptyHint(Mode.HIDDEN_EDIT, "-", null))
        assertEquals(EmptyHint.NO_MATCH, LauncherLogic.emptyHint(Mode.FAV_EDIT, "!xyz", null))
        assertEquals(EmptyHint.NO_FAVORITES, LauncherLogic.emptyHint(Mode.FAV_REORDER, "!!", null))
        assertEquals(EmptyHint.NO_RECENTS, LauncherLogic.emptyHint(Mode.RECENTS, "?", null))
        assertEquals(EmptyHint.NO_TAGS, LauncherLogic.emptyHint(Mode.TAG_FILTER, "#", null))
        assertEquals(EmptyHint.NO_TAG_MATCH, LauncherLogic.emptyHint(Mode.TAG_FILTER, "#wo", null))
        assertEquals(EmptyHint.NO_TAGS, LauncherLogic.emptyHint(Mode.TAG_EDIT, "##", null))
        assertEquals(EmptyHint.NO_APPS, LauncherLogic.emptyHint(Mode.TAG_EDIT, "##work", "work"))
        assertEquals(EmptyHint.NO_COMMAND, LauncherLogic.emptyHint(Mode.COMMAND, "~xyz", null))
    }

    @Test fun `the edit modes are exactly the ones whose Enter means done`() {
        val edit = Mode.entries.filter { LauncherLogic.isEditMode(it) }.toSet()
        assertEquals(setOf(Mode.HIDDEN_EDIT, Mode.FAV_EDIT, Mode.FAV_REORDER, Mode.TAG_EDIT), edit)
    }

    // ---- tag order ----------------------------------------------------------

    @Test fun `tags sort in the device language's alphabet, umlauts with their base letter`() {
        assertEquals(
            listOf("arbeit", "ärzte", "spiele", "zuhause"),
            LauncherLogic.sortedTags(listOf("zuhause", "ärzte", "spiele", "arbeit"), Locale.GERMANY),
        )
        assertEquals(
            listOf("arbeit", "zuhause", "ärzte"),
            LauncherLogic.sortedTags(listOf("ärzte", "zuhause", "arbeit"), Locale.of("sv", "SE")),
        )
    }

    @Test fun `the suggestion pool and the tag overview use that order`() {
        val tags = mapOf("p/a" to listOf("zuhause", "ärzte"), "p/b" to listOf("arbeit"))
        assertEquals(listOf("arbeit", "ärzte", "zuhause"), LauncherLogic.allTags(tags, Locale.GERMANY))
        assertEquals(
            listOf("arbeit", "ärzte", "zuhause"),
            LauncherLogic.tagsInUse(listOf(OrderedFake("p/a", "a"), OrderedFake("p/b", "b")), tags, Locale.GERMANY),
        )
    }

    // ---- named: custom names applied to an enumeration ------------------------------

    /** An enumerated app: label = its own system label, as an enumeration delivers it. */
    private fun sys(label: String) = AppEntry.of(label, "p.${label.lowercase()}", "C", label)

    @Test fun `named shows a custom name and keeps the system label beside it`() {
        val shown = LauncherLogic.named(listOf(sys("Alpha")), mapOf(sys("Alpha").key to "Zulu"), Locale.GERMANY)
        assertEquals("Zulu", shown.single().label)
        assertEquals("zulu", shown.single().labelLower)  // search sees what is on screen
        assertEquals("Alpha", shown.single().systemLabel)
    }

    @Test fun `named falls back to the system label for an entry that carries a stale name`() {
        val stale = sys("Alpha").withLabel("Old")       // e.g. a list relabelled before
        val shown = LauncherLogic.named(listOf(stale), emptyMap(), Locale.GERMANY)
        assertEquals("Alpha", shown.single().label)
        assertEquals("alpha", shown.single().labelLower)
    }

    @Test fun `named keeps an entry whose label already fits as it is`() {
        val alpha = sys("Alpha")
        assertSame(alpha, LauncherLogic.named(listOf(alpha), emptyMap(), Locale.GERMANY).single())
    }

    @Test fun `named sorts by the shown label in the device language`() {
        val apps = listOf(sys("Zoom"), sys("Bravo"), sys("Alpha"))
        val names = mapOf(sys("Alpha").key to "Ärzte")
        // German files Ä under A …
        assertEquals(listOf("Ärzte", "Bravo", "Zoom"), LauncherLogic.named(apps, names, Locale.GERMANY).map { it.label })
        // … Swedish after Z: the order follows the name, in the user's alphabet.
        assertEquals(listOf("Bravo", "Zoom", "Ärzte"), LauncherLogic.named(apps, names, Locale.of("sv", "SE")).map { it.label })
    }

    @Test fun `named applies names to a work copy by its own key only`() {
        val personal = AppEntry.of("Gmail", "p.gmail", "C", "Gmail")
        val work = AppEntry.of("Gmail (Work)", "p.gmail", "C", "Gmail (Work)", profile = 10)
        val shown = LauncherLogic.named(listOf(personal, work), mapOf(work.key to "Job mail"), Locale.GERMANY)
        assertEquals(listOf("Gmail", "Job mail"), shown.map { it.label })
    }

    // ---- sortedForDrawer ----------------------------------------------------

    private fun folded(vararg labels: String) = labels.map { OrderedFake("p/$it", LauncherLogic.foldLabel(it)) }

    @Test fun `German drawer files umlauts under their base letter, not after Z`() {
        val sorted = LauncherLogic.sortedForDrawer(folded("Zoom", "Übersetzer", "Bravo", "Ärzte", "Öffi", "Apotheke"), Locale.GERMANY)
        assertEquals(listOf("p/Apotheke", "p/Ärzte", "p/Bravo", "p/Öffi", "p/Übersetzer", "p/Zoom"), sorted.keys())
    }

    @Test fun `accented letters sort with their base letter`() {
        val sorted = LauncherLogic.sortedForDrawer(folded("Facebook", "Éclair", "Drive"), Locale.FRANCE)
        assertEquals(listOf("p/Drive", "p/Éclair", "p/Facebook"), sorted.keys())
    }

    @Test fun `the drawer follows the device language's alphabet`() {
        // Swedish files Ä after Z — "alphabetical" is the user's alphabet, not a fixed one.
        val sorted = LauncherLogic.sortedForDrawer(folded("Zoom", "Ärzte", "Apotheke"), Locale.of("sv", "SE"))
        assertEquals(listOf("p/Apotheke", "p/Zoom", "p/Ärzte"), sorted.keys())
    }

    @Test fun `labels the collator calls equal but that differ are ordered by the label itself`() {
        // "ma\u0001il" collates equal to "mail" (the control character is ignorable) but is the
        // smaller string; the key order alone would put p/a ("mail") first — the label
        // tie-break must decide before the key does.
        val withControl = OrderedFake("p/b", "ma\u0001il")
        val plain = OrderedFake("p/a", "mail")
        assertEquals(listOf("p/b", "p/a"), LauncherLogic.sortedForDrawer(listOf(withControl, plain), Locale.GERMANY).keys())
        assertEquals(listOf("p/b", "p/a"), LauncherLogic.sortedForDrawer(listOf(plain, withControl), Locale.GERMANY).keys())
    }

    @Test fun `tags the collator calls equal still get a stable order`() {
        assertEquals(listOf("wor\u0001k", "work"), LauncherLogic.sortedTags(listOf("wor\u0001k", "work"), Locale.GERMANY))
        assertEquals(listOf("wor\u0001k", "work"), LauncherLogic.sortedTags(listOf("work", "wor\u0001k"), Locale.GERMANY))
    }

    @Test fun `labels the collator calls equal still get a stable total order`() {
        val a = OrderedFake("p/b", "mail")
        val b = OrderedFake("p/a", "mail")
        assertEquals(listOf("p/a", "p/b"), LauncherLogic.sortedForDrawer(listOf(a, b), Locale.GERMANY).keys())
        assertEquals(listOf("p/a", "p/b"), LauncherLogic.sortedForDrawer(listOf(b, a), Locale.GERMANY).keys())
    }

    @Test fun `foldLabel lowercases with ROOT even under a Turkish default locale`() {
        // Under tr-TR, "I".lowercase() gives the dotless "ı". foldLabel must pin
        // Locale.ROOT so a label keeps the same "i" the search needle folds to —
        // this is the LABEL side of the invariant (search covers the needle side).
        Locale.setDefault(Locale.of("tr", "TR"))
        assertEquals("instagram", LauncherLogic.foldLabel("Instagram"))
        assertEquals("i", LauncherLogic.foldLabel("I"))
    }

    @Test fun `search matches a foldLabel-built row under Turkish`() {
        Locale.setDefault(Locale.of("tr", "TR"))
        // End-to-end: the row's key is built exactly as MainActivity builds it (via
        // foldLabel -> "instagram"), and search is given the raw "I". This guards
        // search's OWN needle fold — a search that stopped folding would test
        // "instagram".contains("I") and miss. (That the two sides fold the SAME way
        // is guaranteed by both using foldLabel; the ROOT-pinning itself is pinned
        // by the standalone foldLabel and search Turkish tests above/below.)
        val all = listOf(OrderedFake("p/insta", LauncherLogic.foldLabel("Instagram")))
        assertEquals(listOf("p/insta"), LauncherLogic.search(all, "I").keys())
    }

    // ---- search -------------------------------------------------------------

    @Test fun `search empty needle returns the list unchanged`() {
        val all = rows("a", "b")
        assertSame(all, LauncherLogic.search(all, ""))
    }

    @Test fun `search over an empty list is empty`() {
        assertEquals(emptyList<String>(), LauncherLogic.search(emptyList<OrderedFake>(), "x").keys())
    }

    @Test fun `search matches a case-folded substring`() {
        val all = listOf(OrderedFake("p/insta", "instagram"), OrderedFake("p/maps", "maps"))
        assertEquals(listOf("p/insta"), LauncherLogic.search(all, "INSTA").keys())
        assertEquals(emptyList<String>(), LauncherLogic.search(all, "zzz").keys())
    }

    @Test fun `search folds the needle with ROOT even under a Turkish default locale`() {
        // Under tr-TR, "I".lowercase() would give the dotless "ı" and miss the
        // row — search must fold with Locale.ROOT so "I" still becomes "i".
        Locale.setDefault(Locale.of("tr", "TR"))
        val all = listOf(OrderedFake("p/insta", "instagram"))
        assertEquals(listOf("p/insta"), LauncherLogic.search(all, "I").keys())
    }

    // ---- favoritesInDisplayOrder --------------------------------------------

    @Test fun `favoritesInDisplayOrder keeps favorites in config order and drops the rest`() {
        val all = rows("a", "b", "c")
        // Favorites config order is c-then-a, independent of the all-list order.
        val result = LauncherLogic.favoritesInDisplayOrder(all, linkedSetOf("c", "a"))
        assertEquals(listOf("c", "a"), result.keys())
    }

    @Test fun `favoritesInDisplayOrder drops a favorite whose app is not present`() {
        val all = rows("a", "b")
        val result = LauncherLogic.favoritesInDisplayOrder(all, linkedSetOf("a", "gone"))
        assertEquals(listOf("a"), result.keys())
    }

    @Test fun `favoritesInDisplayOrder with no favorites is empty`() {
        assertEquals(emptyList<String>(), LauncherLogic.favoritesInDisplayOrder(rows("a", "b"), emptySet()).keys())
    }

    // ---- pickDefaultFavorites -----------------------------------------------

    @Test fun `pickDefaultFavorites takes the first launchable candidate per slot, in slot order`() {
        val all = rows("dialer", "sms", "mail", "chrome", "firefox", "cam")
        val slots = listOf(
            listOf("dialer"),
            listOf("sms"),
            listOf("mail"),
            listOf("firefox", "chrome"),  // the user's default browser comes first
            listOf("cam"),
        )
        assertEquals(
            listOf("dialer", "sms", "mail", "firefox", "cam"),
            LauncherLogic.pickDefaultFavorites(all, slots),
        )
    }

    @Test fun `pickDefaultFavorites falls back past a candidate that is not launchable`() {
        val all = rows("chrome")
        assertEquals(listOf("chrome"), LauncherLogic.pickDefaultFavorites(all, listOf(listOf("gone", "chrome"))))
    }

    @Test fun `pickDefaultFavorites skips an empty slot and never adds an app twice`() {
        val all = rows("google", "cam")
        // One app covering phone AND messenger, a slot with no handler at all.
        val slots = listOf(listOf("google"), listOf("google"), emptyList(), listOf("cam"))
        assertEquals(listOf("google", "cam"), LauncherLogic.pickDefaultFavorites(all, slots))
    }

    @Test fun `a slot whose first choice is taken stays empty rather than falling through`() {
        // The phone app is also the messenger's default: the messenger slot does not reach
        // for its second candidate (some other SMS app the user never chose) — one app
        // covering two jobs is shown once, not swapped for a stranger.
        val all = rows("google", "othersms")
        assertEquals(listOf("google"), LauncherLogic.pickDefaultFavorites(all, listOf(listOf("google"), listOf("google", "othersms"))))
    }

    @Test fun `pickDefaultFavorites with no launchable candidate is empty`() {
        assertEquals(emptyList<String>(), LauncherLogic.pickDefaultFavorites(rows("a"), listOf(listOf("x"))))
    }

    // ---- drawer -------------------------------------------------------------

    @Test fun `drawer excludes hidden apps`() {
        val result = LauncherLogic.drawer(rows("a", "b", "c"), hidden = setOf("b"), favorites = emptySet())
        assertEquals(listOf("a", "c"), result.keys())
    }

    @Test fun `drawer keeps a favorite even when it is also hidden`() {
        // favoriting overrides hiding: b is hidden AND favorite, so it stays.
        val result = LauncherLogic.drawer(rows("a", "b", "c"), hidden = setOf("b"), favorites = setOf("b"))
        assertEquals(listOf("a", "b", "c"), result.keys())
    }

    // ---- orderWithFavorites -------------------------------------------------

    @Test fun `orderWithFavorites returns input unchanged when there are no favorites`() {
        val apps = rows("a", "b", "c")
        assertSame(apps, LauncherLogic.orderWithFavorites(apps, emptySet()))
    }

    @Test fun `orderWithFavorites puts non-favorites first then favorites in rank order`() {
        val apps = rows("a", "b", "c", "d")
        // Favorites c (rank 0) and a (rank 1); non-favorites b, d keep their order.
        val result = LauncherLogic.orderWithFavorites(apps, linkedSetOf("c", "a"))
        assertEquals(listOf("b", "d", "c", "a"), result.keys())
    }

    // ---- drawerStartingWith --------------------------------------------------

    /** An empty prefix IS the bare "*" drawer: it must equal the ordered drawer exactly,
     *  the identity the NORMAL branch relies on when "*".dropLast(1) yields "". */
    @Test fun `drawerStartingWith with an empty prefix equals the ordered drawer`() {
        val all = rows("a", "b", "c")
        val hidden = setOf("b")
        val favorites = linkedSetOf("c")
        val expected = LauncherLogic.orderWithFavorites(
            LauncherLogic.drawer(all, hidden, favorites), favorites,
        ).keys()
        val actual = LauncherLogic.drawerStartingWith(all, hidden, favorites, "").keys()
        assertEquals(expected, actual)
    }

    /** The ordering path THROUGH the prefix filter — every rowsFor star test narrows to
     *  0/1 app, so this is the only place a narrowed result with >1 element pins that
     *  orderWithFavorites still runs AFTER filtering. Dropping the ordering from the
     *  filtered path (or ordering before filtering) would slip past those tests but fail
     *  here. */
    @Test fun `drawerStartingWith orders favorites last within the narrowed subset`() {
        // apple, applet, apricot share "ap"; banana is filtered out. applet is the favorite.
        val all = rows("apple", "applet", "apricot", "banana")
        val result = LauncherLogic.drawerStartingWith(all, emptySet(), linkedSetOf("applet"), "ap")
        assertEquals(listOf("apple", "apricot", "applet"), result.keys())
    }

    /** Prefix filtering drops a hidden non-favorite (it never enters the drawer) but keeps a
     *  hidden favorite — favoriting overrides hiding, the same contract the bare drawer holds. */
    @Test fun `drawerStartingWith drops a hidden non-favorite but keeps a hidden favorite`() {
        val all = rows("bat", "ball", "bay")
        // ball hidden: without a favorite it is gone; as a favorite it survives the filter.
        assertEquals(listOf("bat", "bay"), LauncherLogic.drawerStartingWith(all, setOf("ball"), emptySet(), "ba").keys())
        assertEquals(
            listOf("bat", "bay", "ball"),
            LauncherLogic.drawerStartingWith(all, setOf("ball"), linkedSetOf("ball"), "ba").keys(),
        )
    }

    /** ROOT-fold, like every other matcher here: a typed "AP" must match a stored "apple"
     *  and a Turkish default locale must not turn the prefix's "i" into a dotless one. */
    @Test fun `drawerStartingWith folds the prefix with ROOT`() {
        val all = listOf(OrderedFake("p/insta", LauncherLogic.foldLabel("Instagram")))
        assertEquals(listOf("p/insta"), LauncherLogic.drawerStartingWith(all, emptySet(), emptySet(), "IN").keys())
        Locale.setDefault(Locale.of("tr", "TR"))
        assertEquals(listOf("p/insta"), LauncherLogic.drawerStartingWith(all, emptySet(), emptySet(), "I").keys())
    }

    @Test fun `drawerStartingWith over an empty app list is empty`() {
        assertEquals(emptyList<String>(), LauncherLogic.drawerStartingWith(emptyList<OrderedFake>(), emptySet(), emptySet(), "x").keys())
    }

    // ---- starPrefix ----------------------------------------------------------

    /** The star reads the same at EITHER end — this is the whole point of the function, and
     *  what makes the filter independent of where the IME leaves the caret. Typing "*" and
     *  then a prefix yields "*ab"; the empty-Enter shortcut plus a prefix typed in front of
     *  the star yields "ab*". Both mean "apps starting with ab". */
    @Test fun `starPrefix reads the star at either end`() {
        assertEquals("ab", LauncherLogic.starPrefix("ab*"))
        assertEquals("ab", LauncherLogic.starPrefix("*ab"))
    }

    /** A bare "*" is the EMPTY prefix, i.e. the whole drawer (see the drawerStartingWith
     *  identity test above). "" is not null: the difference is drawer-vs-search. */
    @Test fun `starPrefix maps a bare star to the empty prefix`() {
        assertEquals("", LauncherLogic.starPrefix("*"))
        assertEquals("", LauncherLogic.starPrefix("**"))
    }

    /** No star: null, so rowsFor falls through to the plain substring search over ALL apps
     *  (the path that keeps a hidden app reachable by name). */
    @Test fun `starPrefix returns null without a star`() {
        assertNull(LauncherLogic.starPrefix(""))
        assertNull(LauncherLogic.starPrefix("ab"))
    }

    /** A star INSIDE the text is not a wildcard — only the ends are. Such a prompt stays a
     *  literal search, so an app whose label really contains "*" remains findable. */
    @Test fun `starPrefix ignores a star in the middle`() {
        assertNull(LauncherLogic.starPrefix("a*b"))
    }

    /** A space before the star is part of the prefix: "a *" means labels starting with the
     *  word "a" ("A Better Camera"), not every label starting with the letter a. */
    @Test fun `a space before the star belongs to the prefix`() {
        assertEquals("a ", LauncherLogic.starPrefix("a *"))
        val all = listOf(OrderedFake("p/better", "a better camera"), OrderedFake("p/alpha", "alpha"))
        assertEquals(listOf("p/better"), LauncherLogic.drawerStartingWith(all, emptySet(), emptySet(), "a ").keys())
    }

    // ---- pushRecent ---------------------------------------------------------

    @Test fun `pushRecent puts a new key at the front`() {
        assertEquals(listOf("b", "a"), LauncherLogic.pushRecent(listOf("a"), "b", 8))
    }

    @Test fun `pushRecent moves an existing key to the front without duplicating`() {
        assertEquals(listOf("c", "a", "b"), LauncherLogic.pushRecent(listOf("a", "b", "c"), "c", 8))
    }

    @Test fun `pushRecent caps the list at the limit, dropping the oldest`() {
        // Front-inserting "d" onto a full 3-slot list evicts the oldest ("a").
        assertEquals(listOf("d", "c", "b"), LauncherLogic.pushRecent(listOf("c", "b", "a"), "d", 3))
    }

    @Test fun `pushRecent re-promoting a key never grows past the limit`() {
        assertEquals(listOf("a", "c", "b"), LauncherLogic.pushRecent(listOf("c", "b", "a"), "a", 3))
    }

    @Test fun `pushRecent does not mutate its input`() {
        val current = listOf("a", "b")
        LauncherLogic.pushRecent(current, "c", 8)
        assertEquals(listOf("a", "b"), current)
    }

    @Test fun `pushRecent with a zero limit yields an empty list`() {
        // Not reachable in production (RECENTS_LIMIT is 8), but pin take(0)'s behaviour: a
        // zero limit degrades to "no recents" rather than throwing. (A NEGATIVE limit is a
        // different story — take() throws on it — but that too is unreachable and, as the
        // next test pins, deliberately not relied on here.)
        assertEquals(emptyList<String>(), LauncherLogic.pushRecent(listOf("a", "b"), "c", 0))
    }

    @Test fun `pushRecent with a negative limit throws, as take does`() {
        // Documenting the real boundary rather than pretending it degrades: kotlin's take(n)
        // rejects a negative count, so a negative limit propagates that exception. RECENTS_LIMIT
        // is a positive constant, so this can't happen in production — the test exists so the
        // "zero degrades" note above can't be misread as "any bad limit is safe".
        try {
            LauncherLogic.pushRecent(listOf("a", "b"), "c", -1)
            throw AssertionError("expected an exception for a negative limit")
        } catch (e: IllegalArgumentException) {
            // expected: take(-1) rejects the count
        }
    }

    // ---- recentsInDisplayOrder ----------------------------------------------

    @Test fun `recentsInDisplayOrder reverses so the newest sits last`() {
        val all = rows("a", "b", "c")
        // recentKeys is newest-first (c launched most recently); display is reversed
        // so the newest lands nearest the prompt (last row).
        val result = LauncherLogic.recentsInDisplayOrder(all, listOf("c", "b", "a"))
        assertEquals(listOf("a", "b", "c"), result.keys())
    }

    @Test fun `recentsInDisplayOrder drops a recent whose app is gone`() {
        val all = rows("a", "b")
        val result = LauncherLogic.recentsInDisplayOrder(all, listOf("gone", "b", "a"))
        assertEquals(listOf("a", "b"), result.keys())
    }

    @Test fun `recentsInDisplayOrder with no recents is empty`() {
        assertEquals(emptyList<String>(), LauncherLogic.recentsInDisplayOrder(rows("a", "b"), emptyList()).keys())
    }

    // ---- parseTags ----------------------------------------------------------

    @Test fun `parseTags folds, trims and drops empty pieces`() {
        assertEquals(listOf("work", "games"), LauncherLogic.parseTags("  Work , GAMES "))
    }

    @Test fun `parseTags de-duplicates keeping first-seen order`() {
        // "Work" and "work" fold to the same tag; the trailing empty piece is dropped.
        assertEquals(listOf("work", "fun"), LauncherLogic.parseTags("Work, fun, work, "))
    }

    @Test fun `parseTags on blank input is empty`() {
        assertEquals(emptyList<String>(), LauncherLogic.parseTags("   "))
        assertEquals(emptyList<String>(), LauncherLogic.parseTags(",, ,"))
    }

    // ---- allTags ------------------------------------------------------------

    @Test fun `allTags flattens, de-duplicates and sorts across apps`() {
        val tags = mapOf(
            "a" to listOf("work", "fun"),
            "b" to listOf("games", "work"),   // "work" shared with a
            "c" to emptyList(),
        )
        assertEquals(listOf("fun", "games", "work"), LauncherLogic.allTags(tags))
    }

    @Test fun `allTags on no tags is empty`() {
        assertEquals(emptyList<String>(), LauncherLogic.allTags(emptyMap()))
    }

    // ---- tagsInUse ----------------------------------------------------------

    @Test fun `tagsInUse lists only tags borne by an installed app, sorted`() {
        val tags = mapOf(
            "a" to listOf("work", "fun"),
            "b" to listOf("games"),
            "gone" to listOf("ghost"),   // app not in the installed list
        )
        // only a and b are installed; "ghost" is dropped, result sorted + distinct.
        assertEquals(listOf("fun", "games", "work"), LauncherLogic.tagsInUse(rows("a", "b"), tags))
    }

    @Test fun `tagsInUse is empty when no installed app is tagged`() {
        val tags = mapOf("gone" to listOf("ghost"))
        assertEquals(emptyList<String>(), LauncherLogic.tagsInUse(rows("a", "b"), tags))
    }

    // ---- tagged -------------------------------------------------------------

    private val taggedFixture = mapOf(
        "a" to listOf("work"),
        "b" to listOf("games", "fun"),
        "c" to emptyList(),                 // present but untagged
    )

    @Test fun `tagged with an empty needle lists every tagged app`() {
        // c has no tags, so it drops out; a and b keep their incoming order.
        val result = LauncherLogic.tagged(rows("a", "b", "c"), taggedFixture, "")
        assertEquals(listOf("a", "b"), result.keys())
    }

    @Test fun `tagged canonicalises its needle like a stored tag`() {
        // Stored tags never hold "#", "," or edge spaces (canonicalTag strips them), so a
        // needle carrying them must be stripped the same way to match at all.
        assertEquals(listOf("b"), LauncherLogic.tagged(rows("a", "b", "c"), taggedFixture, "g#a").keys())
        assertEquals(listOf("b"), LauncherLogic.tagged(rows("a", "b", "c"), taggedFixture, " GA ").keys())
    }

    @Test fun `tagged folds its needle with ROOT, not the Turkish default`() {
        // Under tr-TR a default-locale lowercase turns "WIFI" into "wıfı" (dotless) and would miss.
        Locale.setDefault(Locale.of("tr", "TR"))
        val tags = mapOf("a" to listOf("wifi"))
        assertEquals(listOf("a"), LauncherLogic.tagged(rows("a", "b"), tags, "WIFI").keys())
    }

    @Test fun `tagged prefix-matches a tag`() {
        assertEquals(listOf("b"), LauncherLogic.tagged(rows("a", "b", "c"), taggedFixture, "ga").keys())
        assertEquals(listOf("a"), LauncherLogic.tagged(rows("a", "b", "c"), taggedFixture, "work").keys())
    }

    @Test fun `tagged folds the needle with ROOT`() {
        // "WORK" must still match the stored "work" tag.
        assertEquals(listOf("a"), LauncherLogic.tagged(rows("a", "b", "c"), taggedFixture, "WORK").keys())
    }

    @Test fun `tagged returns empty when nothing matches`() {
        assertEquals(emptyList<String>(), LauncherLogic.tagged(rows("a", "b", "c"), taggedFixture, "zzz").keys())
    }

    @Test fun `tagged drops a tag pointing at an uninstalled app`() {
        // "gone" is tagged but not in the app list, so it never appears.
        val tags = mapOf("gone" to listOf("work"), "a" to listOf("work"))
        assertEquals(listOf("a"), LauncherLogic.tagged(rows("a", "b"), tags, "work").keys())
    }

    @Test fun `tagged prefix-match includes tags sharing the prefix`() {
        val tags = mapOf("a" to listOf("work"), "b" to listOf("workout"))
        // "work" prefixes both "work" and "workout" -> both; "worko" only "workout".
        // This is intentional: "#gam" should surface every app under a gam-ish tag.
        assertEquals(listOf("a", "b"), LauncherLogic.tagged(rows("a", "b"), tags, "work").keys())
        assertEquals(listOf("b"), LauncherLogic.tagged(rows("a", "b"), tags, "worko").keys())
    }

    // ---- round 5: fixed points and safe cuts ---------------------------------

    @Test fun `a command needs the tilde in the raw prompt, not just after folding`() {
        assertNull(LauncherLogic.parseCommand("\u0307~restore"))
        assertNull(LauncherLogic.resolveCommand("\u0307~restore"))
        assertEquals(Command.RESTORE, LauncherLogic.parseCommand("~restore"))
    }

    @Test fun `foldLabel is its own fixed point, also where the dropped dot lets a letter compose`() {
        val once = LauncherLogic.foldLabel("\u0130\u0301STANBUL")  // İ + combining acute
        assertEquals("\u00EDstanbul", once)                        // í precomposed
        assertEquals(once, LauncherLogic.foldLabel(once))
    }

    @Test fun `canonicalTag composes what its filter lets combine`() {
        val tag = LauncherLogic.canonicalTag("\u03A9\u061C\u0345")  // Ω, a bidi mark, ypogegrammeni
        assertEquals(tag, LauncherLogic.canonicalTag(tag))
        assertEquals(1, tag.length)
    }

    @Test fun `a tag cut at its limit is trimmed after the cut`() {
        val tag = LauncherLogic.canonicalTag("a".repeat(49) + " b")
        assertEquals("a".repeat(49), tag)
        assertEquals(tag, LauncherLogic.canonicalTag(tag))
        assertEquals("a".repeat(99), LauncherLogic.cleanName("a".repeat(99) + " b"))
    }

    @Test fun `a cut never splits a surrogate pair or an emoji sequence`() {
        assertEquals("a".repeat(49), LauncherLogic.canonicalTag("a".repeat(49) + "\uD83D\uDE00"))
        assertEquals("a".repeat(99), LauncherLogic.cleanName("a".repeat(99) + "\uD83D\uDE00"))
        val family = "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67"  // 8 chars, one glyph
        assertEquals("a".repeat(93), LauncherLogic.cleanName("a".repeat(93) + family))
        assertEquals("a".repeat(92) + family, LauncherLogic.cleanName("a".repeat(92) + family))
    }

    @Test fun `a word-final Greek sigma folds to the sigma it is, so prefixes still match`() {
        assertEquals("\u03BF\u03B4\u03BF\u03C3", LauncherLogic.foldLabel("\u039F\u0394\u039F\u03A3"))  // ΟΔΟΣ -> οδοσ
        val road = OrderedFake("road", LauncherLogic.foldLabel("\u039F\u0394\u039F\u03A3\u03A4\u03A1\u03A9\u039C\u0391"))
        assertEquals(listOf("road"), LauncherLogic.search(listOf(road), "\u039F\u0394\u039F\u03A3").map { it.key })
    }

    @Test fun `an abbreviated command needs the tilde in the raw prompt too`() {
        assertNull(LauncherLogic.resolveCommand("\u0307~b"))
        assertEquals(Command.BACKUP, LauncherLogic.resolveCommand("~b"))
    }

    @Test fun `no app gets more tags than a restore accepts`() {
        val full = (1..ConfigJson.MAX_TAGS_PER_APP).map { "t$it" }
        assertEquals(full, LauncherLogic.toggleTag(full, "one-more"))
        assertEquals(full.dropLast(1), LauncherLogic.toggleTag(full, "t${ConfigJson.MAX_TAGS_PER_APP}"))  // removing still works
        val typed = (1..ConfigJson.MAX_TAGS_PER_APP + 5).joinToString(",") { "t$it" }
        assertEquals(ConfigJson.MAX_TAGS_PER_APP, LauncherLogic.parseTags(typed).size)
    }

    // ---- ~cleanup -----------------------------------------------------------------

    @Test fun `orphans are the entries of apps not installed, counted per section`() {
        val config = VersoConfig(
            hidden = linkedSetOf("gone/A", "here/B"),
            favorites = linkedSetOf("here/B", "gone/A", "gone/C#10"),
            names = linkedMapOf("gone/D" to "Dee", "here/B" to "Bee"),
            tags = linkedMapOf("gone/A" to mutableListOf("x"), "here/B" to mutableListOf("y")),
        )
        val o = LauncherLogic.orphans(config, installed = setOf("here/B"))
        assertEquals(setOf("gone/A", "gone/C#10", "gone/D"), o.keys)
        assertEquals(listOf(2, 1, 1, 1), listOf(o.favorites, o.hidden, o.names, o.tags))
        assertEquals(true, LauncherLogic.orphans(config, setOf("here/B", "gone/A", "gone/C#10", "gone/D")).isEmpty)
    }

    @Test fun `cleanup is a command of its own, also abbreviated`() {
        assertEquals(Command.CLEANUP, LauncherLogic.resolveCommand("~cleanup"))
        assertEquals(Command.CLEANUP, LauncherLogic.resolveCommand("~c"))
    }

    @Test fun `a key orphaned in one section only is still offered`() {
        for (config in listOf(
            VersoConfig(favorites = linkedSetOf("gone/A")),
            VersoConfig(hidden = linkedSetOf("gone/A")),
            VersoConfig(names = linkedMapOf("gone/A" to "Old")),
            VersoConfig(tags = linkedMapOf("gone/A" to mutableListOf("x"))),
        )) {
            val o = LauncherLogic.orphans(config, installed = setOf("here/B"))
            assertEquals(setOf("gone/A"), o.keys)
            assertEquals(1, o.favorites + o.hidden + o.names + o.tags)
        }
    }
}
