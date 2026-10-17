package com.github.reygnn.verso_launcher

internal fun cfg(vararg favorites: String) = VersoConfig(favorites = LinkedHashSet(favorites.toList()))
