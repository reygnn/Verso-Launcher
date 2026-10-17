package com.github.reygnn.verso_launcher

/** The shared [Ordered] fake; by default the label IS the key (ordering tests read keys as labels). */
internal data class OrderedFake(override val key: String, override val labelLower: String = key) : Ordered

internal fun orderedFake(key: String, label: String) = OrderedFake(key, LauncherLogic.foldLabel(label))
