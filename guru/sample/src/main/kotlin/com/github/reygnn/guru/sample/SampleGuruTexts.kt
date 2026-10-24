package com.github.reygnn.guru.sample

import android.content.Context
import com.github.reygnn.guru.GuruTexts

/** Guru's words from this app's resources, so TalkBack on a German device reads German. */
internal class SampleGuruTexts(private val context: Context) : GuruTexts {
    override val hint: String get() = context.getString(R.string.guru_hint)
    override val actionClose: String get() = context.getString(R.string.guru_action_close)
    override val actionShare: String get() = context.getString(R.string.guru_action_share)

    override fun saved(count: Int): String = context.resources.getQuantityString(R.plurals.guru_saved, count, count)
}
