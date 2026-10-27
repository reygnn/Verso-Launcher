package com.github.reygnn.verso_launcher

import java.io.File

/**
 * The app's own small files around the user's config file, which Verso reads and never writes
 * (hard rule 4): where it is (config-link.txt, the document's URI) and a copy of the last text read
 * from it (config-cache.txt), so a start draws the config before the document is read. Both are
 * read on main before the state exists (hard rule 5: a few bytes in the app's own files) and
 * written on VersoApp.io through a temp file and a rename. Lost, the link costs one "~open"; the
 * copy, one read of the document. [read]'s log is the caller's.
 */
internal object ConfigLink {

    const val LINK = "config-link.txt"
    const val CACHE = "config-cache.txt"

    /** The linked document's URI, or null when none is linked (or the file cannot be read: logged). */
    fun readLink(dir: File, log: (String, Exception?) -> Unit): String? =
        readSmall(dir, LINK, log)?.trim()?.takeIf { it.isNotEmpty() }

    fun writeLink(dir: File, uri: String) = writeDisposable(dir, LINK, "$uri\n")

    /** The last text read from the linked document, or null. */
    fun readCache(dir: File, log: (String, Exception?) -> Unit): String? = readSmall(dir, CACHE, log)

    fun writeCache(dir: File, text: String) = writeDisposable(dir, CACHE, text)

    // disposable files: unreadable = none
    @Suppress("TooGenericExceptionCaught")
    private fun readSmall(dir: File, name: String, log: (String, Exception?) -> Unit): String? = try {
        File(dir, name).takeIf { it.isFile }?.readText()
    } catch (e: Exception) {
        log("$name unreadable", e)
        null
    }
}

/**
 * "%": the plain drawer most used first, or A to Z (order.txt). The app's own preference, not the
 * user's config file: "%" is the one thing changed in the app. Disposable like [ConfigLink]'s files.
 */
internal object DrawerOrder {

    const val FILE = "order.txt"
    private const val USAGE = "usage"
    private const val NAME = "name"

    /** True for most used first, false for A to Z, null when never set (or unreadable: logged). */
    // a disposable file: unreadable = never set
    @Suppress("TooGenericExceptionCaught")
    fun read(dir: File, log: (String, Exception?) -> Unit): Boolean? = try {
        when (File(dir, FILE).takeIf { it.isFile }?.readText()?.trim()) {
            USAGE -> true
            NAME -> false
            else -> null
        }
    } catch (e: Exception) {
        log("$FILE unreadable", e)
        null
    }

    fun write(dir: File, byUsage: Boolean) = writeDisposable(dir, FILE, if (byUsage) "$USAGE\n" else "$NAME\n")
}
