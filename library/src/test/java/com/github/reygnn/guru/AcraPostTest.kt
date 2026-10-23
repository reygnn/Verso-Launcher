package com.github.reygnn.guru

import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket

/** A real HTTP server on loopback (the JDK's own), no mock connection: the bytes on the wire are what is tested. */
class AcraPostTest {

    private class Request(val method: String, val path: String, val headers: Map<String, String?>, val body: String)

    private val requests = mutableListOf<Request>()
    private val logs = mutableListOf<String>()
    private var status = 200
    private val server: HttpServer = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
        createContext("/") { exchange ->
            requests += Request(
                method = exchange.requestMethod,
                path = exchange.requestURI.path,
                headers = mapOf(
                    "Content-Type" to exchange.requestHeaders.getFirst("Content-Type"),
                    "Authorization" to exchange.requestHeaders.getFirst("Authorization"),
                ),
                body = exchange.requestBody.readBytes().toString(Charsets.UTF_8),
            )
            exchange.sendResponseHeaders(status, -1)
            exchange.close()
        }
        start()
    }

    private val url get() = "http://127.0.0.1:${server.address.port}/report"

    private fun post(json: String = """{"REPORT_ID":"a"}""", target: String = url) =
        AcraPost.post(target, "reporter", "s3cret", json) { msg, _ -> logs += msg }

    @After
    fun tearDown() = server.stop(0)

    @Test
    fun `a report is posted as JSON with basic auth`() {
        val json = """{"STACK_TRACE":"ü 😀"}"""
        assertEquals(200, post(json))
        val request = requests.single()
        assertEquals("POST", request.method)
        assertEquals("/report", request.path)
        assertEquals("application/json; charset=utf-8", request.headers["Content-Type"])
        assertEquals("Basic cmVwb3J0ZXI6czNjcmV0", request.headers["Authorization"])
        assertEquals(json, request.body)
    }

    /** Both sides of "delivered": 2xx removes the report, anything else keeps it. */
    @Test
    fun `only 2xx counts as delivered`() {
        assertTrue(AcraPost.delivered(200))
        assertTrue(AcraPost.delivered(299))
        assertFalse(AcraPost.delivered(199))
        assertFalse(AcraPost.delivered(300))
        assertFalse(AcraPost.delivered(AcraPost.NO_ANSWER))
    }

    @Test
    fun `a server error is its status, logged`() {
        status = 500
        assertEquals(500, post())
        assertTrue(logs.single().contains("500"))
    }

    @Test
    fun `a refused connection is no answer, not an exception`() {
        val closed = ServerSocket(0, 0, InetAddress.getLoopbackAddress()).use { it.localPort }
        assertEquals(AcraPost.NO_ANSWER, post(target = "http://127.0.0.1:$closed/report"))
        assertEquals("sending failed", logs.single())
    }

    @Test
    fun `a url that is not http is no answer`() {
        assertEquals(AcraPost.NO_ANSWER, post(target = "file:///tmp/report"))
        assertEquals(AcraPost.NO_ANSWER, post(target = "not a url"))
        assertEquals(2, logs.size)
    }

    @Test
    fun `basic auth encodes UTF-8`() {
        assertEquals("Basic w6Q6w7Y=", AcraPost.basic("ä", "ö"))
    }
}
