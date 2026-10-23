package com.github.reygnn.verso_launcher

import android.content.Context
import com.github.reygnn.guru.GuruTexts

/**
 * Guru's words from Verso's resources (`guru_*` in values and values-de), so the alert's hint,
 * its TalkBack actions and the toast follow the device language. Read each time Guru asks.
 */
internal class VersoGuruTexts(private val context: Context) : GuruTexts {
    override val hint: String get() = context.getString(R.string.guru_hint)
    override val actionContinue: String get() = context.getString(R.string.guru_action_continue)
    override val actionSend: String get() = context.getString(R.string.guru_action_send)

    override fun more(count: Int): String = context.resources.getQuantityString(R.plurals.guru_more, count, count)

    override fun sent(sent: Int, total: Int): String = when {
        sent == total -> context.resources.getQuantityString(R.plurals.guru_sent_all, total, total)
        sent == 0 -> context.getString(R.string.guru_sent_none)
        else -> context.resources.getQuantityString(R.plurals.guru_sent_some, total, sent, total)
    }
}
