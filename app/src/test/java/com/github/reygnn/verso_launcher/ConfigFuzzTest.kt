package com.github.reygnn.verso_launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.Locale
import java.util.Random

/**
 * The user's file and other apps' labels, at random, through what handles them on main at every
 * start. [ConfigText.parse] runs inside [ConfigFile.read]'s catch; the state's refresh
 * ([ConfigText.resolve], [LauncherLogic.named]) and the drawer's rows ([LauncherLogic.rowsFor])
 * run without one (hard rule 2). A throw there comes back at every start for as long as the file
 * or the app is there: a crash loop that only a pushed file ends.
 *
 * One run per seed, the default locale changing with it: a failure names the seed, the locale and
 * the file, and the same seed builds the same apps, file and prompts again.
 */
class ConfigFuzzTest {

    @get:Rule val locale = DefaultLocaleRule()

    @Test fun `no random file or label throws, and every line is an entry or reported`() = everySeed { fuzz ->
        val text = fuzz.file()
        val parsed = ConfigText.parse(text)
        assertEquals(meaningfulLines(text), entries(parsed) + parsed.badLines.size)
        val state = fuzz.state(parsed)
        for (prompt in fuzz.prompts(state)) rowsAndActions(state, prompt)
    }

    @Test fun `every tag the overview shows drills into apps`() = everySeed { fuzz ->
        val state = fuzz.state(ConfigText.parse(fuzz.file()))
        for (tag in rows(state, "#").filterIsInstance<TagRow>()) {
            assertTrue("#${tag.name}", rows(state, "#${tag.name}").isNotEmpty())
        }
    }

    @Test fun `every app is found by the name it shows`() = everySeed { fuzz ->
        val state = fuzz.state(ConfigText.parse(fuzz.file()))
        for (app in state.apps) {
            // A name that starts with a sigil or a star is typed as one: that is no search.
            val typed = app.label.trim()
            if (LauncherLogic.parseMode(typed) != Mode.NORMAL || LauncherLogic.starPrefix(typed) != null) continue
            val found = rows(state, typed).mapNotNull { (it as? AppRow<*>)?.entry?.key }
            assertTrue(typed, app.key in found)
        }
    }

    @Test fun `the home cache gives every favorite back`() = everySeed { fuzz ->
        val favorites = fuzz.state(ConfigText.parse(fuzz.file())).favorites()
        val cached = HomeCache.parse(HomeCache.format(favorites))
        assertEquals(favorites.map { it.key to it.label }, cached.map { it.key to it.label })
    }

    private fun everySeed(test: (Fuzz) -> Unit) {
        for (seed in 0L until SEEDS) {
            Locale.setDefault(LOCALES[(seed % LOCALES.size).toInt()])
            val fuzz = Fuzz(Random(seed))
            val failure = runCatching { test(fuzz) }.exceptionOrNull() ?: continue
            throw AssertionError("seed $seed, ${Locale.getDefault()}, file: ${escaped(fuzz.lastFile)}", failure)
        }
    }

    /** The prompt as the field holds it (its input filter) and the drawer reads it (trimmed). */
    private fun prompt(raw: String): String = (LauncherLogic.promptRun(raw) ?: raw).trim()

    private fun rows(state: LauncherState, raw: String): List<Row<AppEntry>> {
        val q = prompt(raw)
        val c = state.config
        return LauncherLogic.rowsFor(
            LauncherLogic.parseMode(q), q, state.apps, c.hidden, c.favorites, c.tags,
            state.recentKeys, c.byUsage, state.usage, NOW,
        )
    }

    /** Everything the drawer asks for one prompt: its rows, the empty hint, Enter, a tap on each row. */
    private fun rowsAndActions(state: LauncherState, raw: String) {
        val q = prompt(raw)
        val mode = LauncherLogic.parseMode(q)
        val shown = rows(state, raw)
        LauncherLogic.emptyHint(mode, q)
        LauncherLogic.enterAction(mode, LauncherLogic.resolveCommand(q), q.isBlank(), shown.lastOrNull())
        for (row in shown) LauncherLogic.tapAction(mode, row)
    }

    // The lines the parse looks at; what is checked is that each of them ends up somewhere.
    private fun meaningfulLines(text: String): Int = text.removePrefix("\uFEFF").lineSequence().count {
        val line = it.trim()
        line.isNotEmpty() && !line.startsWith('#')
    }

    private fun entries(parsed: ConfigText): Int =
        parsed.favorites.size + parsed.hidden.size + parsed.names.size + parsed.tags.size

    /** [text] in printable ASCII, so a failure shows every invisible character it holds. */
    private fun escaped(text: String): String = buildString {
        for (ch in text) if (ch in ' '..'~') append(ch) else append("\\u").append(ch.code.toString(HEX).padStart(4, '0'))
    }

    /** One seed's apps (through the real [enumerate]), file and prompts. */
    private class Fuzz(private val random: Random) {

        private val raws: List<Pair<String, RawApp>> = List(1 + random.nextInt(MAX_APPS)) { i ->
            val pkg = pick(PACKAGES)
            // The first app is a personal one: a personal profile without any is never a list.
            val profile = if (i == 0 || random.nextInt(4) != 0) PERSONAL else WORK
            profile to RawApp(pkg, "$pkg.A$i", text(LABEL_PIECES), archived = random.nextInt(8) == 0)
        }

        val enumeration: Enumeration = checkNotNull(
            enumerate(
                profiles = { listOf(PERSONAL, WORK) },
                personal = PERSONAL,
                serialOf = { WORK_SERIAL },
                paused = { random.nextBoolean() },
                activities = ::activities,
                badge = { raw, _, _ -> "$raw (Work)" },
                ownPackage = "com.github.reygnn.verso_launcher",
                log = { _, _ -> },
            ),
        )

        var lastFile = ""
            private set

        private fun activities(profile: String): List<() -> RawApp> =
            raws.filter { it.first == profile }.map { (_, raw) -> { raw } }

        fun file(): String {
            val lines = List(random.nextInt(MAX_LINES)) { line() }
            val bom = if (random.nextInt(4) == 0) "\uFEFF" else ""
            lastFile = bom + lines.joinToString(pick(LINE_ENDS))
            return lastFile
        }

        /** A state built from [text] whose first refresh is in, with some launches before it. */
        fun state(text: ConfigText): LauncherState {
            val state = testLauncherState(
                io = { it.run() },
                main = { it.run() },
                enumerateApps = { enumeration },
                onRefreshed = {},
                text = text,
                byUsage = random.nextBoolean(),
                now = { NOW },
            )
            repeat(random.nextInt(MAX_LAUNCHES)) { state.recordLaunch(pick(enumeration.apps).key) }
            state.refresh()
            return state
        }

        fun prompts(state: LauncherState): List<String> {
            val fromApps = state.apps.shuffled(random).take(MAX_PROMPTS).flatMap { app ->
                listOf(app.label, "*" + app.label.take(2), "?" + app.label.take(3), "#" + app.packageName.substringAfterLast('.'))
            }
            val fromTags = state.config.tags.values.flatten().map { "#$it" }
            return FIXED_PROMPTS + fromApps + fromTags + List(MAX_PROMPTS) { pick(SIGILS) + text(PROMPT_PIECES) }
        }

        private fun line(): String {
            val keyword = pick(KEYWORDS)
            val rest = when (keyword.lowercase(Locale.ROOT)) {
                "name" -> ref() + pick(EQUALS) + text(NAME_PIECES)
                "tag" -> ref() + pick(EQUALS) + List(random.nextInt(MAX_TAGS)) { text(TAG_PIECES) }.joinToString(",")
                else -> ref()
            }
            return pick(GAPS) + keyword + pick(GAPS) + rest + pick(GAPS)
        }

        /** An app as the file may name it: key, package, label, a gone or broken key, or noise. */
        private fun ref(): String {
            val app = pick(enumeration.apps)
            return when (random.nextInt(REF_KINDS)) {
                0 -> app.key
                1 -> app.packageName
                2 -> app.systemLabel
                3 -> app.systemLabel.uppercase(Locale.ROOT)
                4 -> "${app.packageName}/.Gone#${random.nextInt(100)}"
                5 -> "${app.key}#${random.nextInt(3) - 1}"
                else -> text(PROMPT_PIECES)
            }
        }

        private fun text(maxPieces: Int): String = buildString {
            repeat(random.nextInt(maxPieces + 1)) { append(pick(PIECES)) }
        }

        private fun <T> pick(list: List<T>): T = list[random.nextInt(list.size)]
    }

    private companion object {
        const val SEEDS = 500L
        const val NOW = 1_700_000_000_000L
        const val HEX = 16
        const val PERSONAL = "me"
        const val WORK = "work"
        const val WORK_SERIAL = 10L
        const val MAX_APPS = 30
        const val MAX_LINES = 30
        const val MAX_LAUNCHES = 10
        const val MAX_PROMPTS = 8
        const val MAX_TAGS = 5
        const val REF_KINDS = 8
        const val LABEL_PIECES = 6
        const val PROMPT_PIECES = 6
        const val TAG_PIECES = 4
        // Enough to pass the name's cap now and then (the 60-character piece).
        const val NAME_PIECES = 40

        val LOCALES: List<Locale> = listOf("en", "tr", "de", "sv", "ja", "ar").map(Locale::forLanguageTag)
        val PACKAGES = listOf("com.whatsapp", "org.mozilla.firefox", "com.google.android.gm", "de.post", "com.a", "x.y")
        val KEYWORDS = listOf("fav", "hide", "name", "tag", "FAV", "Tag", "favorite", "nam", "#", "")
        val GAPS = listOf("", " ", "  ", "\t", "\u00A0", "\u3000")
        val EQUALS = listOf(" = ", "=", "==", " =", "= ", "", " = = ")
        val LINE_ENDS = listOf("\n", "\r\n", "\r")
        val SIGILS = listOf("", "?", "#", "~", "%", "*", " ")
        val FIXED_PROMPTS = listOf("", "?", "#", "~", "%", "*", "~r", "~restart", "~crash", "% ", "**", " # ", "?#", "#?")

        // Pieces that broke or nearly broke a fold, a cut or a key somewhere: Turkish and Greek
        // case, combining marks with and without a base, lone and paired surrogates, ZWJ
        // sequences, bidi and zero-width controls, the line separators the parse does not split
        // on, and the characters the file's syntax and the prompt's sigils give a meaning.
        val PIECES = listOf(
            "a", "Z", "Gmail", "Maps", " ", "0", "Ä", "ß", "İ", "ı", "I", "Σ", "ς", "é", "e\u0301",
            "\u0307", "\u3099", "か", "漢", "مرحبا", "हिन्दी",
            "😀", "👨\u200D👩\u200D👧", "\uD83D", "\uDE00",
            "\u202E", "\u2066", "\u200B", "\uFEFF", "\u200D", "\u200C", "\u0000", "\u0085", "\u2028", "\t", "\r", "\n",
            "=", ",", "#", "/", ".", "*", "?", "%", "~", "(Work)", "x".repeat(60),
        )
    }
}
