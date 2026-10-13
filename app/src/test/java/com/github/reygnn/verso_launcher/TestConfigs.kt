package com.github.reygnn.verso_launcher

/** A config holding just [favorites], in that order — the fixture the store and restore tests share. */
internal fun cfg(vararg favorites: String) = VersoConfig(favorites = LinkedHashSet(favorites.toList()))
