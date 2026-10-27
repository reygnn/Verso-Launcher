package com.github.reygnn.verso_launcher

import org.json.JSONArray
import org.json.JSONObject

internal fun cfg(vararg favorites: String) = VersoConfig(favorites = favorites.toList())

/** [config] as the JSON files of earlier versions held it: verso.json and every "~backup". */
internal fun legacyJson(config: VersoConfig): String {
    val names = JSONObject()
    for ((k, v) in config.names) names.put(k, v)
    val tags = JSONObject()
    for ((k, v) in config.tags) tags.put(k, JSONArray(v))
    return JSONObject().apply {
        put("hidden", JSONArray(config.hidden.toList()))
        put("favorites", JSONArray(config.favorites))
        put("names", names)
        put("tags", tags)
        put("order", if (config.byUsage) ConfigJson.ORDER_USAGE else ConfigJson.ORDER_NAME)
    }.toString(2)
}
