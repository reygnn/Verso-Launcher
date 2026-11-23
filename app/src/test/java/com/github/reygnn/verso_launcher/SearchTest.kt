package com.github.reygnn.verso_launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import java.util.Locale

/** What a prompt finds: by name, by the start of a name and by tag. The apps are [testApp]s, so no test ever builds a ComponentName. */
class SearchTest {

    @get:Rule val locale = DefaultLocaleRule()

    /** Apps named by their (already ROOT-folded) label, for ordering tests: "a" has the key "p/a". */
    private fun rows(vararg names: String): List<AppEntry> = testApps(*names)

    private fun List<AppEntry>.keys(): List<String> = map { it.key }

    // ---- search -------------------------------------------------------------

    @Test fun `search matches a foldLabel-built row under Turkish`() {
        Locale.setDefault(Locale.of("tr", "TR"))
        // The row is built via foldLabel; search gets the raw "I", so this guards the needle fold in search itself.
        val all = listOf(testApp("p/insta", "Instagram"))
        assertEquals(listOf("p/insta"), Search.search(all, "I").keys())
    }

    @Test fun `search empty needle returns the list unchanged`() {
        val all = rows("a", "b")
        assertSame(all, Search.search(all, ""))
    }

    @Test fun `search over an empty list is empty`() {
        assertEquals(emptyList<String>(), Search.search(emptyList(), "x").keys())
    }

    @Test fun `search matches a case-folded substring`() {
        val all = listOf(testApp("p/insta", "instagram"), testApp("p/maps", "maps"))
        assertEquals(listOf("p/insta"), Search.search(all, "INSTA").keys())
        assertEquals(emptyList<String>(), Search.search(all, "zzz").keys())
    }

    @Test fun `search folds the needle with ROOT even under a Turkish default locale`() {
        // Under tr-TR, "I".lowercase() would be the dotless "ı" and miss the row.
        Locale.setDefault(Locale.of("tr", "TR"))
        val all = listOf(testApp("p/insta", "instagram"))
        assertEquals(listOf("p/insta"), Search.search(all, "I").keys())
    }

    @Test fun `search ignores Latin, Greek and Cyrillic accents both ways`() {
        val all = listOf(testApp("p/uber", "Über"), testApp("p/cafe", "Café"), testApp("p/athens", "Αθήνα"))
        assertEquals(listOf("p/uber"), Search.search(all, "uber").keys())
        assertEquals(listOf("p/cafe"), Search.search(all, "CAFE").keys())
        assertEquals(listOf("p/athens"), Search.search(all, "αθηνα").keys())
        val plain = listOf(testApp("p/uber", "Uber"))
        assertEquals("a typed accent finds the plain name too", listOf("p/uber"), Search.search(plain, "über").keys())
    }

    /** đ, ł, ø, ß and kin are no letter plus a mark to NFD: they are spelled plainly instead. */
    @Test fun `search spells letters NFD keeps whole as a plain keyboard does, both ways`() {
        val all = listOf(
            testApp("p/phone", "Điện thoại"), testApp("p/lodz", "Łódź"), testApp("p/oslo", "Øst"),
            testApp("p/street", "Straße"), testApp("p/aero", "Ærø"), testApp("p/kapi", "Kapı"),
        )
        assertEquals(listOf("p/phone"), Search.search(all, "dien").keys())
        assertEquals(listOf("p/lodz"), Search.search(all, "LODZ").keys())
        assertEquals(listOf("p/oslo"), Search.search(all, "ost").keys())
        assertEquals(listOf("p/street"), Search.search(all, "strasse").keys())
        assertEquals(listOf("p/aero"), Search.search(all, "aero").keys())
        assertEquals(listOf("p/kapi"), Search.search(all, "kapi").keys())
        val plain = listOf(testApp("p/street", "Strasse"))
        assertEquals("a typed ß finds the plain name too", listOf("p/street"), Search.search(plain, "straße").keys())
    }

    /**
     * [Labels.foldLabel] lowers with ROOT, so an upper-case Turkish label holds a dotted i
     * where the word has a dotless ı: "IŞIK" folds to "işik". Typed correctly, "ışık" must still
     * find it, and the other way round.
     */
    @Test fun `search finds an upper-case Turkish label by its dotless spelling, both ways`() {
        Locale.setDefault(Locale.of("tr", "TR"))
        val upper = listOf(testApp("p/isik", "IŞIK"))
        assertEquals(listOf("p/isik"), Search.search(upper, "ışık").keys())
        val lower = listOf(testApp("p/isik", "ışık"))
        assertEquals(listOf("p/isik"), Search.search(lower, "IŞIK").keys())
    }

    /** A dakuten or a Devanagari vowel sign makes another word: those marks must still count. */
    @Test fun `search keeps the marks of other scripts`() {
        val all = listOf(testApp("p/ga", "\u304C"), testApp("p/ku", "\u0915\u0941"))
        assertEquals(emptyList<String>(), Search.search(all, "\u304B").keys())   // か does not find が
        assertEquals(emptyList<String>(), Search.search(all, "\u0915\u0947").keys())
        assertEquals(listOf("p/ga"), Search.search(all, "\u304C").keys())
    }

    // ---- ranked --------------------------------------------------------------

    /** The list is bottom-anchored: the last row sits nearest the prompt, and Enter launches it. */
    @Test fun `ranked puts a label's start last, a word's start above it, a match inside a word on top`() {
        val found = listOf(
            testApp("p/gmail", "Gmail"),
            testApp("p/google-maps", "Google Maps"),
            testApp("p/mail", "Mail"),
            testApp("p/maps", "Maps"),
            testApp("p/samsung", "Samsung Max"),
        )
        assertEquals(
            listOf("p/gmail", "p/google-maps", "p/samsung", "p/mail", "p/maps"),
            Search.ranked(Search.search(found, "ma"), "ma").keys(),
        )
    }

    @Test fun `ranked counts a word after any non-letter, and ignores accents like search`() {
        val found = listOf(testApp("p/a", "Wär-Über"), testApp("p/b", "Ubernatural"), testApp("p/c", "Rubber"))
        assertEquals(listOf("p/c", "p/a", "p/b"), Search.ranked(Search.search(found, "ub"), "ub").keys())
    }

    @Test fun `ranked with an empty needle keeps the list`() {
        val found = rows("b", "a")
        assertSame(found, Search.ranked(found, ""))
    }

    // ---- drawerStartingWith --------------------------------------------------

    /** rowsFor relies on this identity for a bare "*". */
    @Test fun `drawerStartingWith with an empty prefix equals the ordered drawer`() {
        val all = rows("a", "b", "c")
        val hidden = setOf("p/b")
        val favorites = listOf("p/c")
        val expected = AppLists.orderWithFavorites(
            AppLists.drawer(all, hidden, favorites), favorites,
        ).keys()
        val actual = Search.drawerStartingWith(all, hidden, favorites, "").keys()
        assertEquals(expected, actual)
    }

    /** The only narrowed case with >1 app: the ordering must run after the filtering. */
    @Test fun `drawerStartingWith orders favorites last within the narrowed subset`() {
        // apple, applet, apricot share "ap"; banana is filtered out. applet is the favorite.
        val all = rows("apple", "applet", "apricot", "banana")
        val result = Search.drawerStartingWith(all, emptySet(), linkedSetOf("p/applet"), "ap")
        assertEquals(listOf("p/apple", "p/apricot", "p/applet"), result.keys())
    }

    @Test fun `drawerStartingWith drops a hidden non-favorite but keeps a hidden favorite`() {
        val all = rows("bat", "ball", "bay")
        assertEquals(listOf("p/bat", "p/bay"), Search.drawerStartingWith(all, setOf("p/ball"), emptySet(), "ba").keys())
        assertEquals(
            listOf("p/bat", "p/bay", "p/ball"),
            Search.drawerStartingWith(all, setOf("p/ball"), linkedSetOf("p/ball"), "ba").keys(),
        )
    }

    @Test fun `drawerStartingWith folds the prefix with ROOT`() {
        val all = listOf(testApp("p/insta", "Instagram"))
        assertEquals(listOf("p/insta"), Search.drawerStartingWith(all, emptySet(), emptySet(), "IN").keys())
        Locale.setDefault(Locale.of("tr", "TR"))
        assertEquals(listOf("p/insta"), Search.drawerStartingWith(all, emptySet(), emptySet(), "I").keys())
    }

    @Test fun `drawerStartingWith ignores accents like search`() {
        val all = listOf(testApp("p/uber", "\u00DCber"), testApp("p/rub", "Rubber"))
        assertEquals(listOf("p/uber"), Search.drawerStartingWith(all, emptySet(), emptySet(), "ub").keys())
    }

    @Test fun `drawerStartingWith over an empty app list is empty`() {
        assertEquals(emptyList<String>(), Search.drawerStartingWith(emptyList(), emptySet(), emptySet(), "x").keys())
    }

    // ---- starPrefix ----------------------------------------------------------

    /** Typing "*" then a prefix gives "*ab"; a prefix typed in front of the star gives "ab*". */
    @Test fun `starPrefix reads the star at either end`() {
        assertEquals("ab", Search.starPrefix("ab*"))
        assertEquals("ab", Search.starPrefix("*ab"))
    }

    /** No label starts with a space. (Before a trailing star it counts: see "a *".) */
    @Test fun `starPrefix drops spaces after a leading star`() {
        assertEquals("gm", Search.starPrefix("* gm"))
        assertEquals("", Search.starPrefix("* "))
    }

    /** "" is not null: the difference is drawer vs. search. */
    @Test fun `starPrefix maps a bare star to the empty prefix`() {
        assertEquals("", Search.starPrefix("*"))
        assertEquals("", Search.starPrefix("**"))
    }

    /** Null falls through to the search over ALL apps, which keeps a hidden app reachable. */
    @Test fun `starPrefix returns null without a star`() {
        assertNull(Search.starPrefix(""))
        assertNull(Search.starPrefix("ab"))
    }

    /** An app whose label really contains "*" stays findable. */
    @Test fun `starPrefix ignores a star in the middle`() {
        assertNull(Search.starPrefix("a*b"))
    }

    /** "a *" means the word "a" ("A Better Camera"), not every label starting with a. */
    @Test fun `a space before the star belongs to the prefix`() {
        assertEquals("a ", Search.starPrefix("a *"))
        val all = listOf(testApp("p/better", "a better camera"), testApp("p/alpha", "alpha"))
        assertEquals(listOf("p/better"), Search.drawerStartingWith(all, emptySet(), emptySet(), "a ").keys())
    }

    // ---- tagged -------------------------------------------------------------

    private val taggedFixture = mapOf(
        "p/a" to listOf("work"),
        "p/b" to listOf("games", "fun"),
        "p/c" to emptyList(),                 // present but untagged
    )

    @Test fun `tagged with an empty needle lists every tagged app`() {
        val result = Search.tagged(rows("a", "b", "c"), taggedFixture, "")
        assertEquals(listOf("p/a", "p/b"), result.keys())
    }

    @Test fun `tagged canonicalises its needle like a stored tag`() {
        // Stored tags never hold "#", "," or edge spaces, so the needle is stripped the same way.
        assertEquals(listOf("p/b"), Search.tagged(rows("a", "b", "c"), taggedFixture, "g#a").keys())
        assertEquals(listOf("p/b"), Search.tagged(rows("a", "b", "c"), taggedFixture, " GA ").keys())
    }

    @Test fun `tagged folds its needle with ROOT, not the Turkish default`() {
        // Under tr-TR a default-locale lowercase turns "WIFI" into "wıfı" (dotless) and would miss.
        Locale.setDefault(Locale.of("tr", "TR"))
        val tags = mapOf("p/a" to listOf("wifi"))
        assertEquals(listOf("p/a"), Search.tagged(rows("a", "b"), tags, "WIFI").keys())
    }

    @Test fun `tagged prefix-matches a tag`() {
        assertEquals(listOf("p/b"), Search.tagged(rows("a", "b", "c"), taggedFixture, "ga").keys())
        assertEquals(listOf("p/a"), Search.tagged(rows("a", "b", "c"), taggedFixture, "work").keys())
    }

    @Test fun `tagged folds the needle with ROOT`() {
        assertEquals(listOf("p/a"), Search.tagged(rows("a", "b", "c"), taggedFixture, "WORK").keys())
    }

    @Test fun `tagged returns empty when nothing matches`() {
        assertEquals(emptyList<String>(), Search.tagged(rows("a", "b", "c"), taggedFixture, "zzz").keys())
    }

    @Test fun `tagged drops a tag pointing at an uninstalled app`() {
        val tags = mapOf("p/gone" to listOf("work"), "p/a" to listOf("work"))
        assertEquals(listOf("p/a"), Search.tagged(rows("a", "b"), tags, "work").keys())
    }

    @Test fun `tagged prefix-match includes tags sharing the prefix`() {
        val tags = mapOf("p/a" to listOf("work"), "p/b" to listOf("workout"))
        // Intentional: "#gam" surfaces every app under a gam-ish tag.
        assertEquals(listOf("p/a", "p/b"), Search.tagged(rows("a", "b"), tags, "work").keys())
        assertEquals(listOf("p/b"), Search.tagged(rows("a", "b"), tags, "worko").keys())
    }

    @Test fun `tagged falls back to the package when no tag matches`() {
        val gm = testApp("com.google.android.gm/Gm")
        val cam = testApp("com.samsung.android.camera/Cam")
        val verso = testApp("com.github.reygnn.verso_launcher/Verso")
        val apps = listOf(gm, cam, verso)
        assertEquals(listOf(gm), Search.tagged(apps, emptyMap(), "google"))
        assertEquals(listOf(gm), Search.tagged(apps, emptyMap(), "GOO"))
        assertEquals(listOf(verso), Search.tagged(apps, emptyMap(), "reygnn"))
        // A matching tag wins: no package rows mixed in.
        assertEquals(listOf(cam), Search.tagged(apps, mapOf(cam.key to listOf("google")), "google"))
    }

    @Test fun `the package fallback skips the first segment and an empty needle`() {
        val apps = listOf(testApp("com.google.android.gm/Gm"))
        assertEquals(emptyList<String>(), Search.tagged(apps, emptyMap(), "com").keys())
        assertEquals(emptyList<String>(), Search.tagged(apps, emptyMap(), "").keys())
    }

    /** "#eis" is no "reise", "oogle" no package segment "google". */
    @Test fun `a tag and a package segment match only from their start`() {
        assertEquals(emptyList<String>(), Search.tagged(rows("a"), mapOf("p/a" to listOf("reise")), "eis").keys())
        assertEquals(false, Search.packageMatches("com.google.android.gm", "oogle"))
    }
}
