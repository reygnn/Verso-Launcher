package com.github.reygnn.guru

/**
 * Where a report goes when the user holds the Guru screen ("right mouse button").
 * The host picks one at install.
 */
internal sealed interface GuruSink {

    /**
     * No permission: the reports, as ACRA JSON, are handed to an app the user picks — mail,
     * a messenger, a notes app. [mailTo] fills the recipient in for mail apps. Handed over =
     * done: the reports are removed, as ACRA's mail sender did.
     */
    data class Share(val mailTo: String? = null) : GuruSink

    /**
     * Straight to an ACRA server (the family's own backend, or Acrarium): [url] is its report endpoint
     * ("https://acra.example.org/report"), [login]/[password] the app's reporter user.
     * Needs `<uses-permission android:name="android.permission.INTERNET"/>` and https (cleartext
     * is blocked by default). A report stays until the server answers 2xx.
     */
    data class Acra(val url: String, val login: String, val password: String) : GuruSink {
        override fun toString() = "Acra(url=$url, login=$login)"
    }
}
