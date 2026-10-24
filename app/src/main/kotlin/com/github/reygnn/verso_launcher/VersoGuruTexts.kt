package com.github.reygnn.verso_launcher

import android.content.Context
import com.github.reygnn.guru.GuruTexts

/**
 * Guru's words from Verso's resources (`guru_*` in values and values-de), so the alert's hint,
 * its TalkBack actions and the "saved" line follow the device language. Read each time Guru asks.
 * Every member is overridden: Guru's defaults are English, a missing one would silently be too.
 */
internal class VersoGuruTexts(private val context: Context) : GuruTexts {
    override val hint: String get() = context.getString(R.string.guru_hint)
    override val actionClose: String get() = context.getString(R.string.guru_action_close)
    override val actionShare: String get() = context.getString(R.string.guru_action_share)

    override fun saved(count: Int): String = context.resources.getQuantityString(R.plurals.guru_saved, count, count)
}
