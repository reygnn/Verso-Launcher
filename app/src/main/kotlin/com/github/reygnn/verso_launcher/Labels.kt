package com.github.reygnn.verso_launcher

import java.text.BreakIterator
import java.text.Normalizer
import java.util.Locale

/**
 * The text of a label, a custom name and a tag: the one fold (hard rule 9), the form search
 * compares, and what of a name or a tag may be stored. Pure: no framework, disk or shared state.
 */
internal object Labels {

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
     * A folded label or needle without Latin, Greek and Cyrillic accents (U+0300–U+036F after
     * NFD), so "uber" finds "Über". Only for matching: tags and names keep [foldLabel]'s form.
     * Other scripts' marks stay — a Japanese dakuten or a Devanagari vowel sign makes another word.
     * Latin letters that NFD does not take apart are spelled as on a plain keyboard
     * ([PLAIN_LETTERS]), so "dien" finds "Điện" and "lodz" finds "Łódź".
     */
    fun matchForm(folded: String): String {
        // Composed again: decomposed, "\u304B" would be a substring of "\u304C" (+ U+3099).
        val bare = nfc(Normalizer.normalize(folded, Normalizer.Form.NFD).filterNot { it in '\u0300'..'\u036F' })
        return if (bare.none { it in PLAIN_LETTERS }) bare else buildString {
            for (ch in bare) append(PLAIN_LETTERS[ch] ?: ch)
        }
    }

    /**
     * Lower-case only: [matchForm] gets [foldLabel]'s form. đ ð ħ ı ł ø ŧ ß æ œ þ.
     * Why: they are letters of their own to NFD, not a letter with a mark, so the accent filter
     *   leaves them and a plain keyboard cannot type them.
     */
    private val PLAIN_LETTERS = mapOf(
        '\u0111' to "d", '\u00F0' to "d", '\u0127' to "h", '\u0131' to "i", '\u0142' to "l",
        '\u00F8' to "o", '\u0167' to "t", '\u00DF' to "ss", '\u00E6' to "ae", '\u0153' to "oe",
        '\u00FE' to "th",
    )

    fun parseTags(raw: String): List<String> =
        raw.split(',').map(::canonicalTag).filter { it.isNotEmpty() }.distinct()
            .take(MAX_TAGS_PER_APP)

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

    const val MAX_TAGS_PER_APP = 100

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
     * [s] without a lone UTF-16 surrogate, which no font draws. A string from the platform, such as
     * an app's label, may hold one; the config file cannot, because its UTF-8 decoding replaces
     * one. After the other filters, so a pair they separated is whole again.
     */
    private fun pairedOnly(s: String): String = buildString(s.length) {
        s.codePoints().forEach { if (Character.getType(it) != Character.SURROGATE.toInt()) appendCodePoint(it) }
    }
}
