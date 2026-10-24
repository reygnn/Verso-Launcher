package com.github.reygnn.verso_launcher

import android.content.Context

/** [value] dp in px for this context's display; the one conversion the views share. */
internal fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
