package com.github.reygnn.verso_launcher

import java.io.File

/**
 * "%": the plain drawer most used first, or A to Z (order.txt). The app's own preference, not the
 * user's config file: "%" is the one thing changed in the app. Read on main before the state
 * exists (hard rule 5), written on VersoApp.io through [writeDisposable]. If order.txt is lost, the
 * drawer is A to Z until the next "%".
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
