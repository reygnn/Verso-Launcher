package com.github.reygnn.verso_launcher

/** One key event in plain values, so [PromptInput.enterEdge] stays free of android.view.KeyEvent. */
internal data class EnterKey(val down: Boolean, val repeatCount: Int, val isEnterKey: Boolean)

internal enum class EnterEdge {
    /** The one call per Enter that acts. */
    ACT,
    /** The rest of an Enter already acted on (its key-up, a held key's repeats): swallowed. */
    CONSUME,
    /** Not an Enter at all: left to the TextView. */
    PASS,
}

/**
 * What a key press or a pasted run puts into the command line, decided on plain values so that it
 * stays free of android.view. Pure.
 */
internal object PromptInput {

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
}
