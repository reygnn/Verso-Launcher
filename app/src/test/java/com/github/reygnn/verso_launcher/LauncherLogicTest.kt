package com.github.reygnn.verso_launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import java.util.Locale

/** The rows are a trivial [Ordered] fake, so no test ever builds a ComponentName. */
class LauncherLogicTest {

    @get:Rule val locale = DefaultLocaleRule()


    /** Rows whose key doubles as the (already ROOT-folded) label, for ordering tests. */
    private fun rows(vararg keys: String): List<OrderedFake> = keys.map { OrderedFake(it, it) }

    private fun List<Ordered>.keys(): List<String> = map { it.key }

    // ---- typedChar ----------------------------------------------------------

    /** android.view.KeyCharacterMap.COMBINING_ACCENT: set on a dead key's unicodeChar. */
    private val combiningAccent = 0x80000000.toInt()

    @Test fun `a plain key types its character`() {
        assertEquals("a", LauncherLogic.typedChar('a'.code, chord = false))
        assertEquals("ß", LauncherLogic.typedChar('ß'.code, chord = false))
        assertEquals("~", LauncherLogic.typedChar('~'.code, chord = false))
    }

    /** A dead key carries the COMBINING_ACCENT bit: a negative Int that Character.toChars throws on. */
    @Test fun `a dead key types nothing and does not throw`() {
        assertNull(LauncherLogic.typedChar(combiningAccent or '^'.code, chord = false))
        assertNull(LauncherLogic.typedChar(combiningAccent or 0xB4, chord = false))
        assertNull(LauncherLogic.typedChar(combiningAccent or '`'.code, chord = false))
    }

    @Test fun `zero, control characters and invalid code points type nothing`() {
        assertNull(LauncherLogic.typedChar(0, chord = false))
        assertNull(LauncherLogic.typedChar('\n'.code, chord = false))
        assertNull(LauncherLogic.typedChar('\t'.code, chord = false))
        assertNull(LauncherLogic.typedChar(0x7F, chord = false))
        assertNull(LauncherLogic.typedChar(Character.MAX_CODE_POINT + 1, chord = false))
    }

    @Test fun `a supplementary code point types as a surrogate pair`() {
        assertEquals(String(Character.toChars(0x1F600)), LauncherLogic.typedChar(0x1F600, chord = false))
        assertEquals(String(Character.toChars(Character.MAX_CODE_POINT)), LauncherLogic.typedChar(Character.MAX_CODE_POINT, chord = false))
    }

    /** AltGr is no chord (the Activity leaves it out), so "@" and "€" on a German layout still type. */
    @Test fun `a chord types nothing`() {
        assertNull(LauncherLogic.typedChar('a'.code, chord = true))
        assertNull(LauncherLogic.typedChar(combiningAccent or '^'.code, chord = true))
    }

    // ---- parseMode ----------------------------------------------------------

    @Test fun `empty and plain text are NORMAL`() {
        assertEquals(Mode.NORMAL, LauncherLogic.parseMode(""))
        assertEquals(Mode.NORMAL, LauncherLogic.parseMode("gmail"))
        assertEquals(Mode.NORMAL, LauncherLogic.parseMode("*"))
    }

    @Test fun `percent is the order toggle, whatever follows it`() {
        assertEquals(Mode.ORDER, LauncherLogic.parseMode("%"))
        assertEquals(Mode.ORDER, LauncherLogic.parseMode("%abc"))
        assertEquals(Mode.NORMAL, LauncherLogic.parseMode("a%"))
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

    /** "##" must be checked before "#" (as "!!" before "!"), or it becomes a filter for the tag "#". */
    @Test fun `double hash is TAG_EDIT, single hash stays TAG_FILTER`() {
        assertEquals(Mode.TAG_EDIT, LauncherLogic.parseMode("##"))
        assertEquals(Mode.TAG_EDIT, LauncherLogic.parseMode("##work"))
        assertEquals(Mode.TAG_FILTER, LauncherLogic.parseMode("#"))
        assertEquals(Mode.TAG_FILTER, LauncherLogic.parseMode("#work"))
    }

    // ---- canonicalTag -------------------------------------------------------

    /** "#" + "#work" = "##work" would open the bulk editor instead of the tag filter. */
    @Test fun `canonicalTag strips the hash that would flip the sigil`() {
        assertEquals("work", LauncherLogic.canonicalTag("#work"))
        assertEquals("work", LauncherLogic.canonicalTag("##work"))
        assertEquals("work", LauncherLogic.canonicalTag("wo#rk"))
    }

    /** The dialog joins with ", " and re-splits on ",". */
    @Test fun `canonicalTag strips the comma that would tear a tag in two`() {
        assertEquals("foobar", LauncherLogic.canonicalTag("foo,bar"))
    }

    /** "#" + "!work" still lands in the tag filter: only position 0 picks the mode. */
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

    @Test fun `canonicalTag folds with ROOT, not the device locale`() {
        Locale.setDefault(Locale.forLanguageTag("tr"))
        assertEquals("i", LauncherLogic.canonicalTag("I"))
    }

    @Test fun `parseTags still splits on commas and now canonicalises each piece`() {
        assertEquals(listOf("work", "chat"), LauncherLogic.parseTags("#work, Chat"))
        assertEquals(listOf("work"), LauncherLogic.parseTags("work, #work, WORK"))
        assertEquals(emptyList<String>(), LauncherLogic.parseTags("#, ,,"))
    }

    /** Otherwise a tag is reachable one way and not the other. */
    @Test fun `the bulk editor and the dialog canonicalise identically`() {
        for (raw in listOf("#work", "WORK", "  work  ", "wo#rk", "foo,bar")) {
            val viaDialog = LauncherLogic.parseTags(raw).firstOrNull().orEmpty()
            val viaBulk = LauncherLogic.toggleTag(null, raw).firstOrNull().orEmpty()
            if (raw == "foo,bar") continue  // the dialog splits this one
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

    /** The caller stores this as a removed key: ConfigJson drops empty tag lists. */
    @Test fun `toggleTag can empty an app's tag list`() {
        assertEquals(emptyList<String>(), LauncherLogic.toggleTag(listOf("work"), "work"))
    }

    /** Stored tags are canonical, so an unfolded needle must not create a near-duplicate. */
    @Test fun `toggleTag folds the incoming tag`() {
        assertEquals(emptyList<String>(), LauncherLogic.toggleTag(listOf("work"), "WORK"))
        assertEquals(listOf("work"), LauncherLogic.toggleTag(null, "Work"))
    }

    /** Under tr, a typed "##WORK" must not become a dotless-i tag. */
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
        // Otherwise clearing an override back to the original would be impossible.
        assertNull(LauncherLogic.resolveNameOverride("Gmail", "Gmail"))
    }

    @Test fun `resolveNameOverride stores a genuinely different name`() {
        assertEquals("Mail", LauncherLogic.resolveNameOverride("Mail", "Gmail"))
    }

    /** "gmail" over "Gmail" changes what the row shows. */
    @Test fun `resolveNameOverride treats a case-only difference as a real override`() {
        assertEquals("gmail", LauncherLogic.resolveNameOverride("gmail", "Gmail"))
    }

    /** The dialog trims, so a trimmed name over a padded label is a real request. */
    @Test fun `resolveNameOverride stores a trimmed name over a padded system label`() {
        assertEquals("Gmail", LauncherLogic.resolveNameOverride("Gmail", " Gmail "))
    }

    // ---- parseCommand -------------------------------------------------------

    @Test fun `every command parses, a bare tilde and the dropped ones do not`() {
        assertNull(LauncherLogic.parseCommand("~"))
        assertNull(LauncherLogic.parseCommand("~load"))
        assertNull(LauncherLogic.parseCommand("~save"))
        assertEquals(Command.RESTART, LauncherLogic.parseCommand("~restart"))
        assertNull(LauncherLogic.parseCommand("~reset"))
        assertEquals(Command.BACKUP, LauncherLogic.parseCommand("~backup"))
        assertEquals(Command.RESTORE, LauncherLogic.parseCommand("~restore"))
        assertEquals(Command.RESTORE_SAF, LauncherLogic.parseCommand("~restore-saf"))
    }

    /** "~restore" is a strict prefix of "~restore-saf"; only exact matching keeps them apart. */
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

    /** Under tr the "I" of "~RESTORE" would fold to a dotless i. */
    @Test fun `commands still parse under a Turkish default locale`() {
        Locale.setDefault(Locale.forLanguageTag("tr"))
        assertEquals(Command.RESTORE, LauncherLogic.parseCommand("~RESTORE"))
    }

    /** Exact matching is what spares the "~" sigil an escape. */
    @Test fun `a prefix or a partial is not a command`() {
        assertNull(LauncherLogic.parseCommand("~backups"))
        assertNull(LauncherLogic.parseCommand("~ backup"))
        assertNull(LauncherLogic.parseCommand("~backu"))
        assertNull(LauncherLogic.parseCommand("~backup now"))
        assertNull(LauncherLogic.parseCommand("backup"))
        assertNull(LauncherLogic.parseCommand(""))
    }

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
            listOf("~restart", "~restore", "~restore-saf"),
            LauncherLogic.commandsMatching("~r").map { it.first },
        )
        assertEquals(listOf("~restore-saf"), LauncherLogic.commandsMatching("~restore-").map { it.first })
        assertEquals(emptyList<String>(), LauncherLogic.commandsMatching("~zzz").map { it.first })
    }

    /** "~crash": only in a daily-driver build (unit tests compile it in) and only spelled out. */
    @Test fun `~crash only in a daily-driver build, never abbreviated`() {
        val crash = Command.CRASH.takeIf { DailyDriver.ENABLED }
        assertEquals(crash, LauncherLogic.parseCommand("~crash"))
        assertEquals(crash, LauncherLogic.resolveCommand("~crash"))
        assertEquals(null, LauncherLogic.resolveCommand("~cr"))
        assertEquals(Command.CLEANUP, LauncherLogic.resolveCommand("~c"))
        assertEquals(DailyDriver.ENABLED, "~crash" in LauncherLogic.commandsMatching("~").map { it.first })
    }

    @Test fun `an unambiguous abbreviation resolves`() {
        assertEquals(Command.CLEANUP, LauncherLogic.resolveCommand("~c"))
        assertEquals(Command.RESTART, LauncherLogic.resolveCommand("~resta"))
        assertEquals(Command.BACKUP, LauncherLogic.resolveCommand("~b"))
        assertEquals(Command.RESTORE_SAF, LauncherLogic.resolveCommand("~restore-"))
    }

    /** "~restore" both names a command and prefixes "~restore-saf". */
    @Test fun `an exact match beats a prefix`() {
        assertEquals(Command.RESTORE, LauncherLogic.resolveCommand("~restore"))
        assertEquals(Command.RESTORE_SAF, LauncherLogic.resolveCommand("~restore-saf"))
    }

    @Test fun `a bare tilde resolves to nothing`() {
        assertNull(LauncherLogic.resolveCommand("~"))
    }

    @Test fun `an ambiguous abbreviation resolves to nothing`() {
        assertNull(LauncherLogic.resolveCommand("~r"))
        assertNull(LauncherLogic.resolveCommand("~re"))
        assertNull(LauncherLogic.resolveCommand("~restor"))
        assertNull(LauncherLogic.resolveCommand("~rest"))  // ~restart or ~restore
    }

    /** Otherwise a command added to COMMANDS without a line here would look covered. */
    @Test fun `parseCommand covers every entry in COMMANDS`() {
        for ((spelling, command) in LauncherLogic.COMMANDS) {
            assertEquals(spelling, command, LauncherLogic.parseCommand(spelling))
        }
    }

    /** These fold apart from parseCommand. Not a ROOT test: no spelling has an "i" yet (see the next test). */
    @Test fun `commandsMatching and resolveCommand are case-insensitive too`() {
        assertEquals(listOf("~backup"), LauncherLogic.commandsMatching("~B").map { it.first })
        assertEquals(Command.BACKUP, LauncherLogic.resolveCommand("~B"))
        assertEquals(Command.RESTORE_SAF, LauncherLogic.resolveCommand("~RESTORE-"))
        assertEquals(Command.CLEANUP, LauncherLogic.resolveCommand("~CLEANUP"))
    }

    /** Forward guard: a spelling with an "i" (say "~import") would stop resolving on a Turkish phone. */
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
        // Up-move inserting AT dest 0: an off-by-one would land the row at index 1.
        assertEquals(listOf("c", "a", "b"), LauncherLogic.reorder(listOf("a", "b", "c"), "c", "a"))
    }

    @Test fun `reorder moves a row onto the very last slot`() {
        // Down-move inserting after the final element.
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
        // ROOT lowercases İ (U+0130) to "i" + U+0307, which then never matches a typed "i".
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

    /** UTF-8 has no form for a lone surrogate: the file would say "?" where memory holds the half. */
    @Test fun `cleanName and canonicalTag drop a lone surrogate, keep a pair`() {
        assertEquals("x", LauncherLogic.cleanName("x\uD800"))
        assertEquals("xy", LauncherLogic.cleanName("x\uDC00y"))
        assertEquals("x", LauncherLogic.canonicalTag("x\uD800"))
        assertEquals("xy", LauncherLogic.canonicalTag("\uDFFFxy"))
        val pair = "\uD83D\uDE00"  // one emoji, two chars
        assertEquals("a$pair", LauncherLogic.cleanName("a$pair"))
        assertEquals("a$pair", LauncherLogic.canonicalTag("a$pair"))
        // Halves a dropped character held apart are a pair again, not two lone halves.
        assertEquals(pair, LauncherLogic.cleanName("\uD83D\u200B\uDE00"))
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
    private fun sys(label: String) = AppEntry(label, "p.${label.lowercase()}", "C", label)

    @Test fun `named shows a custom name and keeps the system label beside it`() {
        val shown = LauncherLogic.named(listOf(sys("Alpha")), mapOf(sys("Alpha").key to "Zulu"), Locale.GERMANY)
        assertEquals("Zulu", shown.single().label)
        assertEquals("zulu", shown.single().labelLower)  // search sees what is on screen
        assertEquals("Alpha", shown.single().systemLabel)
    }

    @Test fun `named falls back to the system label for an entry that carries a stale name`() {
        val stale = sys("Alpha").withLabel("Old")
        val shown = LauncherLogic.named(listOf(stale), emptyMap(), Locale.GERMANY)
        assertEquals("Alpha", shown.single().label)
        assertEquals("alpha", shown.single().labelLower)
    }

    @Test fun `named keeps an entry whose label already fits as it is`() {
        val alpha = sys("Alpha")
        assertSame(alpha, LauncherLogic.named(listOf(alpha), emptyMap(), Locale.GERMANY).single())
    }

    /** The enumeration sorts already: with no label changed, named must not sort again. */
    @Test fun `named keeps the order it gets when no label changes`() {
        val apps = listOf(sys("Bravo"), sys("Alpha"))
        assertEquals(listOf("Bravo", "Alpha"), LauncherLogic.named(apps, emptyMap(), Locale.GERMANY).map { it.label })
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
        val personal = AppEntry("Gmail", "p.gmail", "C", "Gmail")
        val work = AppEntry("Gmail (Work)", "p.gmail", "C", "Gmail (Work)", profile = 10)
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
        // "ma\u0001il" collates equal to "mail" but is the smaller string; key order alone would put p/a first.
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
        // Under tr-TR, "I".lowercase() is the dotless "ı". The label side; search covers the needle.
        Locale.setDefault(Locale.of("tr", "TR"))
        assertEquals("instagram", LauncherLogic.foldLabel("Instagram"))
        assertEquals("i", LauncherLogic.foldLabel("I"))
    }

    @Test fun `search matches a foldLabel-built row under Turkish`() {
        Locale.setDefault(Locale.of("tr", "TR"))
        // The row is built via foldLabel; search gets the raw "I", so this guards the needle fold in search itself.
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
        // Under tr-TR, "I".lowercase() would be the dotless "ı" and miss the row.
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
        // Not swapped for a second candidate the user never chose: one app covering two jobs shows once.
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

    /** rowsFor relies on this identity for a bare "*". */
    @Test fun `drawerStartingWith with an empty prefix equals the ordered drawer`() {
        val all = rows("a", "b", "c")
        val hidden = setOf("b")
        val favorites = listOf("c")
        val expected = LauncherLogic.orderWithFavorites(
            LauncherLogic.drawer(all, hidden, favorites), favorites,
        ).keys()
        val actual = LauncherLogic.drawerStartingWith(all, hidden, favorites, "").keys()
        assertEquals(expected, actual)
    }

    /** The only narrowed case with >1 app: the ordering must run after the filtering. */
    @Test fun `drawerStartingWith orders favorites last within the narrowed subset`() {
        // apple, applet, apricot share "ap"; banana is filtered out. applet is the favorite.
        val all = rows("apple", "applet", "apricot", "banana")
        val result = LauncherLogic.drawerStartingWith(all, emptySet(), linkedSetOf("applet"), "ap")
        assertEquals(listOf("apple", "apricot", "applet"), result.keys())
    }

    @Test fun `drawerStartingWith drops a hidden non-favorite but keeps a hidden favorite`() {
        val all = rows("bat", "ball", "bay")
        assertEquals(listOf("bat", "bay"), LauncherLogic.drawerStartingWith(all, setOf("ball"), emptySet(), "ba").keys())
        assertEquals(
            listOf("bat", "bay", "ball"),
            LauncherLogic.drawerStartingWith(all, setOf("ball"), linkedSetOf("ball"), "ba").keys(),
        )
    }

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

    /** Typing "*" then a prefix gives "*ab"; a prefix typed in front of the star gives "ab*". */
    @Test fun `starPrefix reads the star at either end`() {
        assertEquals("ab", LauncherLogic.starPrefix("ab*"))
        assertEquals("ab", LauncherLogic.starPrefix("*ab"))
    }

    /** No label starts with a space. (Before a trailing star it counts: see "a *".) */
    @Test fun `starPrefix drops spaces after a leading star`() {
        assertEquals("gm", LauncherLogic.starPrefix("* gm"))
        assertEquals("", LauncherLogic.starPrefix("* "))
    }

    /** "" is not null: the difference is drawer vs. search. */
    @Test fun `starPrefix maps a bare star to the empty prefix`() {
        assertEquals("", LauncherLogic.starPrefix("*"))
        assertEquals("", LauncherLogic.starPrefix("**"))
    }

    /** Null falls through to the search over ALL apps, which keeps a hidden app reachable. */
    @Test fun `starPrefix returns null without a star`() {
        assertNull(LauncherLogic.starPrefix(""))
        assertNull(LauncherLogic.starPrefix("ab"))
    }

    /** An app whose label really contains "*" stays findable. */
    @Test fun `starPrefix ignores a star in the middle`() {
        assertNull(LauncherLogic.starPrefix("a*b"))
    }

    /** "a *" means the word "a" ("A Better Camera"), not every label starting with a. */
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
        // Unreachable (RECENTS_LIMIT is 8); a zero limit degrades to "no recents".
        assertEquals(emptyList<String>(), LauncherLogic.pushRecent(listOf("a", "b"), "c", 0))
    }

    @Test fun `pushRecent with a negative limit throws, as take does`() {
        // A negative limit is not safe: take(n) rejects it.
        try {
            LauncherLogic.pushRecent(listOf("a", "b"), "c", -1)
            throw AssertionError("expected an exception for a negative limit")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
    }

    // ---- recentsInDisplayOrder ----------------------------------------------

    @Test fun `recentsInDisplayOrder reverses so the newest sits last`() {
        val all = rows("a", "b", "c")
        // c was launched most recently; the newest lands nearest the prompt.
        val result = LauncherLogic.recentsInDisplayOrder(all, listOf("c", "b", "a"))
        assertEquals(listOf("a", "b", "c"), result.keys())
    }

    @Test fun `recentsInDisplayOrder drops a recent whose app is gone`() {
        val all = rows("a", "b")
        val result = LauncherLogic.recentsInDisplayOrder(all, listOf("gone", "b", "a"))
        assertEquals(listOf("a", "b"), result.keys())
    }

    @Test fun `recentsInDisplayOrder - a gone app takes no place among the shown`() {
        val all = rows("a", "b", "c")
        val result = LauncherLogic.recentsInDisplayOrder(all, listOf("c", "gone", "b", "a"), limit = 2)
        assertEquals(listOf("b", "c"), result.keys())
    }

    @Test fun `recentsInDisplayOrder shows the newest eight`() {
        val keys = (1..10).map { "k$it" }
        val result = LauncherLogic.recentsInDisplayOrder(rows(*keys.toTypedArray()), keys.reversed())
        assertEquals((3..10).map { "k$it" }, result.keys())
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
        val result = LauncherLogic.tagged(rows("a", "b", "c"), taggedFixture, "")
        assertEquals(listOf("a", "b"), result.keys())
    }

    @Test fun `tagged canonicalises its needle like a stored tag`() {
        // Stored tags never hold "#", "," or edge spaces, so the needle is stripped the same way.
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
        assertEquals(listOf("a"), LauncherLogic.tagged(rows("a", "b", "c"), taggedFixture, "WORK").keys())
    }

    @Test fun `tagged returns empty when nothing matches`() {
        assertEquals(emptyList<String>(), LauncherLogic.tagged(rows("a", "b", "c"), taggedFixture, "zzz").keys())
    }

    @Test fun `tagged drops a tag pointing at an uninstalled app`() {
        val tags = mapOf("gone" to listOf("work"), "a" to listOf("work"))
        assertEquals(listOf("a"), LauncherLogic.tagged(rows("a", "b"), tags, "work").keys())
    }

    @Test fun `tagged prefix-match includes tags sharing the prefix`() {
        val tags = mapOf("a" to listOf("work"), "b" to listOf("workout"))
        // Intentional: "#gam" surfaces every app under a gam-ish tag.
        assertEquals(listOf("a", "b"), LauncherLogic.tagged(rows("a", "b"), tags, "work").keys())
        assertEquals(listOf("b"), LauncherLogic.tagged(rows("a", "b"), tags, "worko").keys())
    }

    @Test fun `tagged falls back to the package when no tag matches`() {
        val apps = listOf(
            OrderedFake("gm", packageName = "com.google.android.gm"),
            OrderedFake("cam", packageName = "com.samsung.android.camera"),
            OrderedFake("verso", packageName = "com.github.reygnn.verso_launcher"),
        )
        assertEquals(listOf("gm"), LauncherLogic.tagged(apps, emptyMap(), "google").keys())
        assertEquals(listOf("gm"), LauncherLogic.tagged(apps, emptyMap(), "GOO").keys())
        assertEquals(listOf("verso"), LauncherLogic.tagged(apps, emptyMap(), "reygnn").keys())
        // A matching tag wins: no package rows mixed in.
        assertEquals(listOf("cam"), LauncherLogic.tagged(apps, mapOf("cam" to listOf("google")), "google").keys())
    }

    @Test fun `the package fallback skips the first segment and an empty needle`() {
        val apps = listOf(OrderedFake("gm", packageName = "com.google.android.gm"))
        assertEquals(emptyList<String>(), LauncherLogic.tagged(apps, emptyMap(), "com").keys())
        assertEquals(emptyList<String>(), LauncherLogic.tagged(apps, emptyMap(), "").keys())
    }

    // ---- fixed points and safe cuts ------------------------------------------

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
            favorites = listOf("here/B", "gone/A", "gone/C#10"),
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
            VersoConfig(favorites = listOf("gone/A")),
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
