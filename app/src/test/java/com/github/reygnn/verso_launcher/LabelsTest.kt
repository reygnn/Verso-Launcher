package com.github.reygnn.verso_launcher

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.util.Locale

/** The fold, the match form, and what of a name or a tag may be stored. */
class LabelsTest {

    @get:Rule val locale = DefaultLocaleRule()

    private fun List<AppEntry>.keys(): List<String> = map { it.key }

    // ---- canonicalTag -------------------------------------------------------

    /** "#" + "#work" = "##work" would open the bulk editor instead of the tag filter. */
    @Test fun `canonicalTag strips the hash that would flip the sigil`() {
        assertEquals("work", Labels.canonicalTag("#work"))
        assertEquals("work", Labels.canonicalTag("##work"))
        assertEquals("work", Labels.canonicalTag("wo#rk"))
    }

    /** The dialog joins with ", " and re-splits on ",". */
    @Test fun `canonicalTag strips the comma that would tear a tag in two`() {
        assertEquals("foobar", Labels.canonicalTag("foo,bar"))
    }

    /** "#" + "!work" still lands in the tag filter: only position 0 picks the mode. */
    @Test fun `canonicalTag keeps the harmless sigils`() {
        assertEquals("!work", Labels.canonicalTag("!work"))
        assertEquals("-work", Labels.canonicalTag("-work"))
        assertEquals("~work", Labels.canonicalTag("~work"))
        assertEquals("?work", Labels.canonicalTag("?work"))
        assertEquals("my work", Labels.canonicalTag("my work"))
    }

    @Test fun `canonicalTag folds, trims and drops control characters`() {
        assertEquals("work", Labels.canonicalTag("  WORK  "))
        assertEquals("work", Labels.canonicalTag("work\n"))
        assertEquals("work", Labels.canonicalTag("# work"))
    }

    @Test fun `canonicalTag returns empty when nothing survives`() {
        assertEquals("", Labels.canonicalTag("###"))
        assertEquals("", Labels.canonicalTag(",,,"))
        assertEquals("", Labels.canonicalTag("   "))
        assertEquals("", Labels.canonicalTag(""))
    }

    @Test fun `canonicalTag folds with ROOT, not the device locale`() {
        Locale.setDefault(Locale.forLanguageTag("tr"))
        assertEquals("i", Labels.canonicalTag("I"))
    }

    @Test fun `parseTags still splits on commas and now canonicalises each piece`() {
        assertEquals(listOf("work", "chat"), Labels.parseTags("#work, Chat"))
        assertEquals(listOf("work"), Labels.parseTags("work, #work, WORK"))
        assertEquals(emptyList<String>(), Labels.parseTags("#, ,,"))
    }

    // ---- foldLabel ----------------------------------------------------------

    @Test fun `foldLabel folds the capital dotted I to a plain i`() {
        // ROOT lowercases İ (U+0130) to "i" + U+0307, which then never matches a typed "i".
        assertEquals("işcep", Labels.foldLabel("İşCep"))
        assertEquals("istanbulkart", Labels.foldLabel("İstanbulkart"))
        assertEquals(listOf("p/is"), Search.search(listOf(testApp("p/is", "İşCep")), "iş").keys())
        assertEquals(listOf("p/is"), Search.search(listOf(testApp("p/is", "İşCep")), "İŞ").keys())
    }

    @Test fun `foldLabel makes decomposed and composed text the same`() {
        val decomposed = "Cafe\u0301"  // e + combining acute
        assertEquals("café", Labels.foldLabel(decomposed))
        assertEquals(listOf("p/c"), Search.search(listOf(testApp("p/c", decomposed)), "café").keys())
    }

    @Test fun `foldLabel keeps letters that really carry a dot above`() {
        assertEquals("żabka ėjimas", Labels.foldLabel("Żabka Ėjimas"))
    }

    @Test fun `a tag typed with the dotted capital I is the same tag as the plain one`() {
        assertEquals("iş", Labels.canonicalTag("İş"))
    }

    // ---- names and tags: no look-alike characters, bounded length ---------

    @Test fun `cleanName drops invisible bidi and zero-width characters`() {
        // U+202E (right-to-left override) can make "lang" display reversed; U+200B is invisible.
        assertEquals("Signal", Labels.cleanName("Sig\u200Bnal"))
        assertEquals("evil", Labels.cleanName("\u202Eevil\u202C"))
        assertEquals("a b", Labels.cleanName("  a\u0000 b\n "))
    }

    @Test fun `cleanName keeps the joiners real scripts and emoji need`() {
        val family = "\uD83D\uDC68\u200D\uD83D\uDC69"  // man ZWJ woman
        assertEquals(family, Labels.cleanName(family))
        val persian = "\u0645\u06CC\u200C\u0634\u0648\u062F"  // ZWNJ inside a word
        assertEquals(persian, Labels.cleanName(persian))
    }

    /** No font draws a lone surrogate; a string from the platform, such as an app's label, can hold one. */
    @Test fun `cleanName and canonicalTag drop a lone surrogate, keep a pair`() {
        assertEquals("x", Labels.cleanName("x\uD800"))
        assertEquals("xy", Labels.cleanName("x\uDC00y"))
        assertEquals("x", Labels.canonicalTag("x\uD800"))
        assertEquals("xy", Labels.canonicalTag("\uDFFFxy"))
        val pair = "\uD83D\uDE00"  // one emoji, two chars
        assertEquals("a$pair", Labels.cleanName("a$pair"))
        assertEquals("a$pair", Labels.canonicalTag("a$pair"))
        // Halves a dropped character held apart are a pair again, not two lone halves.
        assertEquals(pair, Labels.cleanName("\uD83D\u200B\uDE00"))
    }

    @Test fun `cleanName caps the length`() {
        assertEquals(Labels.MAX_NAME_LENGTH, Labels.cleanName("x".repeat(500)).length)
    }

    @Test fun `canonicalTag drops look-alike characters and caps the length too`() {
        assertEquals("work", Labels.canonicalTag("wo\u200Brk"))
        assertEquals(Labels.MAX_TAG_LENGTH, Labels.canonicalTag("t".repeat(500)).length)
    }

    @Test fun `foldLabel lowercases with ROOT even under a Turkish default locale`() {
        // Under tr-TR, "I".lowercase() is the dotless "ı". The label side; search covers the needle.
        Locale.setDefault(Locale.of("tr", "TR"))
        assertEquals("instagram", Labels.foldLabel("Instagram"))
        assertEquals("i", Labels.foldLabel("I"))
    }

    /** The plain spelling is for matching only: a stored tag keeps its letter. */
    @Test fun `letters NFD keeps whole stay in tags`() {
        assertEquals("łódź", Labels.canonicalTag("Łódź"))
    }

    /** The match form is for matching only: a stored tag keeps its accent. */
    @Test fun `accents stay in tags`() {
        assertEquals("café", Labels.canonicalTag("Café"))
    }

    // ---- parseTags ----------------------------------------------------------

    @Test fun `parseTags folds, trims and drops empty pieces`() {
        assertEquals(listOf("work", "games"), Labels.parseTags("  Work , GAMES "))
    }

    @Test fun `parseTags de-duplicates keeping first-seen order`() {
        // "Work" and "work" fold to the same tag; the trailing empty piece is dropped.
        assertEquals(listOf("work", "fun"), Labels.parseTags("Work, fun, work, "))
    }

    @Test fun `parseTags on blank input is empty`() {
        assertEquals(emptyList<String>(), Labels.parseTags("   "))
        assertEquals(emptyList<String>(), Labels.parseTags(",, ,"))
    }

    @Test fun `foldLabel is its own fixed point, also where the dropped dot lets a letter compose`() {
        val once = Labels.foldLabel("\u0130\u0301STANBUL")  // İ + combining acute
        assertEquals("\u00EDstanbul", once)                        // í precomposed
        assertEquals(once, Labels.foldLabel(once))
    }

    @Test fun `canonicalTag composes what its filter lets combine`() {
        val tag = Labels.canonicalTag("\u03A9\u061C\u0345")  // Ω, a bidi mark, ypogegrammeni
        assertEquals(tag, Labels.canonicalTag(tag))
        assertEquals(1, tag.length)
    }

    @Test fun `a tag cut at its limit is trimmed after the cut`() {
        val tag = Labels.canonicalTag("a".repeat(49) + " b")
        assertEquals("a".repeat(49), tag)
        assertEquals(tag, Labels.canonicalTag(tag))
        assertEquals("a".repeat(99), Labels.cleanName("a".repeat(99) + " b"))
    }

    @Test fun `a cut never splits a surrogate pair or an emoji sequence`() {
        assertEquals("a".repeat(49), Labels.canonicalTag("a".repeat(49) + "\uD83D\uDE00"))
        assertEquals("a".repeat(99), Labels.cleanName("a".repeat(99) + "\uD83D\uDE00"))
        val family = "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67"  // 8 chars, one glyph
        assertEquals("a".repeat(93), Labels.cleanName("a".repeat(93) + family))
        assertEquals("a".repeat(92) + family, Labels.cleanName("a".repeat(92) + family))
    }

    @Test fun `a word-final Greek sigma folds to the sigma it is, so prefixes still match`() {
        assertEquals("\u03BF\u03B4\u03BF\u03C3", Labels.foldLabel("\u039F\u0394\u039F\u03A3"))  // ΟΔΟΣ -> οδοσ
        val road = testApp("p/road", "\u039F\u0394\u039F\u03A3\u03A4\u03A1\u03A9\u039C\u0391")
        assertEquals(listOf("p/road"), Search.search(listOf(road), "\u039F\u0394\u039F\u03A3").map { it.key })
    }

    @Test fun `no app gets more tags than the file format allows`() {
        val typed = (1..Labels.MAX_TAGS_PER_APP + 5).joinToString(",") { "t$it" }
        assertEquals(Labels.MAX_TAGS_PER_APP, Labels.parseTags(typed).size)
    }

    /** Every mark [Labels.isSpoofChar] names, each on its own. */
    @Test fun `cleanName drops each spoof character on its own`() {
        val spoof = ('\u202A'..'\u202E') + ('\u2066'..'\u2069') +
            listOf('\u200E', '\u200F', '\u061C', '\u200B', '\uFEFF', '\u2060')
        for (c in spoof) assertEquals("U+%04X".format(c.code), "ab", Labels.cleanName("a${c}b"))
    }

    /**
     * The other side of each spoof range: U+2029 and U+202F (the narrow no-break space of French
     * typography) before and after the first, U+2065 and U+206A around the second, all kept.
     */
    @Test fun `cleanName keeps the neighbours of the spoof ranges`() {
        for (c in listOf('\u2029', '\u202F', '\u2065', '\u206A')) {
            assertEquals("U+%04X".format(c.code), "a${c}b", Labels.cleanName("a${c}b"))
        }
    }
}
