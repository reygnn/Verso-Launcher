package com.github.reygnn.verso_launcher

/**
 * The one [Ordered] fake the pure-logic tests share: an identity [key] and its folded label.
 * By default the label IS the key (ordering tests read keys as labels); [orderedFake] folds a
 * display label the way production does.
 */
internal data class OrderedFake(override val key: String, override val labelLower: String = key) : Ordered

/** An [OrderedFake] for display label [label], folded like an AppEntry's. */
internal fun orderedFake(key: String, label: String) = OrderedFake(key, LauncherLogic.foldLabel(label))
