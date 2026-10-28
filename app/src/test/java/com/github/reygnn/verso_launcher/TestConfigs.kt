package com.github.reygnn.verso_launcher

internal fun cfg(vararg favorites: String) = VersoConfig(favorites = favorites.toList())

/** [config] as a file of keys: every reference is a key, so [ConfigText.resolve] gives [config] back. */
internal fun textOf(config: VersoConfig): ConfigText = ConfigText(
    favorites = config.favorites,
    hidden = config.hidden.toList(),
    names = config.names.toList(),
    tags = config.tags.toList(),
)
