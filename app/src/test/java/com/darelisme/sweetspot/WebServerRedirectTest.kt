package com.darelisme.sweetspot

import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class WebServerRedirectTest {
    @Test
    fun rootRedirectReadsPairCodeOnceAndSerializesExpectedResponse() {
        var reads = 0
        val response = WebServer.rootRedirectResponse {
            reads++
            " ab-cd-2345 "
        }

        val serialized = WebServer.serializeResponse(response).toString(StandardCharsets.UTF_8)
        val expected = buildString {
            append("HTTP/1.1 302 Found\r\n")
            append("Location: ${Config.DASHBOARD_URL}/connect/ABCD2345\r\n")
            append("Content-Length: 0\r\n")
            append("Connection: close\r\n")
            append("Cache-Control: no-store\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("\r\n")
        }

        assertEquals(1, reads)
        assertEquals(expected, serialized)
        assertEquals("", serialized.substringAfter("\r\n\r\n"))
    }

    @Test
    fun serializerRejectsCrOrLfInLocation() {
        val response = WebServer.redirectResponse(
            "${Config.DASHBOARD_URL}/connect/ABCD2345\r\nX-Injected: true"
        )

        assertThrows(IllegalArgumentException::class.java) {
            WebServer.serializeResponse(response)
        }
    }
}
