package com.github.reygnn.guru

/**
 * Every word Guru shows or speaks besides the Amiga quote, which stays as it is. The host passes
 * its own from its resources, so TalkBack on a German device reads German; [English] is the
 * default. Read each time the alert is built or a toast shown, never kept: a language change in
 * between is picked up.
 */
internal interface GuruTexts {

    /** Under the alert box, and the end of what a screen reader says for it. */
    val hint: String

    /** The screen reader's name for continuing — a double-tap (the reports go). */
    val actionContinue: String

    /** The screen reader's name for a hold (send). */
    val actionSend: String

    /** Under the Guru number when more reports wait behind the one shown: [count] of them. */
    fun more(count: Int): String

    /** The toast after sending: [sent] of [total] delivered; 0 also when no app could take them. */
    fun sent(sent: Int, total: Int): String

    object English : GuruTexts {
        override val hint = "double-tap: continue · hold: send · back: later"
        override val actionContinue = "continue, discard the report"
        override val actionSend = "send the report"

        override fun more(count: Int) = "+$count more"

        override fun sent(sent: Int, total: Int): String = when {
            sent == total && total == 1 -> "Guru: report sent"
            sent == total -> "Guru: $total reports sent"
            sent == 0 -> "Guru: not sent, kept for the next start"
            else -> "Guru: $sent of $total sent, the rest kept"
        }
    }
}
