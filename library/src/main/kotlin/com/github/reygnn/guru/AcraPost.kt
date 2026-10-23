package com.github.reygnn.guru

import java.net.HttpURLConnection
import java.net.URI
import java.util.Base64

/**
 * One report to an ACRA server (POST <server>/report, basic auth with the app's reporter
 * user). Plain java.net — no client library. Needs the INTERNET permission in the
 * host app; without it the connect throws and the report stays. Blocks for up to two
 * [TIMEOUT_MS]: call it on the IO thread only.
 */
internal object AcraPost {

    const val TIMEOUT_MS = 15_000
    const val NO_ANSWER = -1
    private const val OK_FIRST = 200
    private const val OK_LAST = 299

    fun delivered(status: Int): Boolean = status in OK_FIRST..OK_LAST

    /** The HTTP status, or [NO_ANSWER] when none came back (no network, refused, not http). Never throws. */
    // a failed send keeps the report; nothing here may take the IO thread down
    @Suppress("TooGenericExceptionCaught")
    fun post(url: String, login: String, password: String, json: String, log: (String, Throwable?) -> Unit): Int {
        var connection: HttpURLConnection? = null
        return try {
            connection = URI(url).toURL().openConnection() as? HttpURLConnection
                ?: return NO_ANSWER.also { log("not an http(s) url: $url", null) }
            val body = json.toByteArray(Charsets.UTF_8)
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Authorization", basic(login, password))
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }
            connection.responseCode.also { if (!delivered(it)) log("server answered $it", null) }
        } catch (e: Exception) {
            log("sending failed", e)
            NO_ANSWER
        } finally {
            connection?.disconnect()
        }
    }

    fun basic(login: String, password: String): String =
        "Basic " + Base64.getEncoder().encodeToString("$login:$password".toByteArray(Charsets.UTF_8))
}
