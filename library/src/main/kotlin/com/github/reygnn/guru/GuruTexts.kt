package com.github.reygnn.guru

/**
 * Every word Guru shows or speaks besides the Amiga quote, which stays as it is. The host passes
 * its own from its resources, so TalkBack on a German device reads German; [English] is the
 * default. Read each time the alert is built, never kept: a language change in between is
 * picked up.
 *
 * Every member has its English text as the default, here and nowhere else, so a member added
 * later breaks no host — and [English] overrides nothing, so no default can call itself.
 */
internal interface GuruTexts {

    /** Under the alert box, and the end of what a screen reader says for it. */
    val hint: String get() = "double-tap: close · hold: share"

    /** The screen reader's name for closing the alert — a double-tap, or Back. */
    val actionClose: String get() = "close"

    /** The screen reader's name for a hold: the saved files to the share chooser. */
    val actionShare: String get() = "share the report"

    /** Under the Guru number: [count] reports, the one shown among them, are in Download/Guru. */
    fun saved(count: Int): String = if (count == 1) "saved to Download/Guru" else "$count reports in Download/Guru"

    /** The defaults, as they are. */
    object English : GuruTexts
}
