package com.github.reygnn.verso_launcher

import android.view.View
import android.view.ViewGroup

/** Every view of type [T] in this view's tree, itself included, depth-first. */
internal inline fun <reified T : View> View.collect(): List<T> = allViews(this).filterIsInstance<T>()

internal fun allViews(v: View): List<View> =
    listOf(v) + ((v as? ViewGroup)?.let { g -> (0 until g.childCount).flatMap { allViews(g.getChildAt(it)) } } ?: emptyList())
