package com.github.reygnn.verso_launcher

/**
 * An [AppEntry] for the pure tests, given by its key ("package/class", [AppEntry.keyOf]) and the
 * label it shows, which is also its system label. Without a label the key is shown: enough where
 * only the entry's identity or its key counts.
 */
internal fun testApp(key: String, label: String = key): AppEntry {
    require('/' in key) { "no app key: $key" }
    return AppEntry(label, key.substringBefore('/'), key.substringAfter('/'), label)
}

/**
 * Apps of the package "p", each named by one word that is its class and its label: "a" has the
 * key "p/a". For the ordering tests, which read a list by its keys.
 */
internal fun testApps(vararg names: String): List<AppEntry> = names.map { testApp("p/$it", it) }
