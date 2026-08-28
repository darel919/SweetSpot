package com.darelisme.sweetspot

import com.darelisme.sweetspot.pairing.PairingSessionManager
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WebServerRedirectTest {
    @Test
    fun rootRedirectReadsPairingSessionOnceAndSerializesExpectedResponse() {
        var reads = 0
        val session = PairingSessionManager.Session(
            code = "ABCD2345",
            rendezvousId = "0123456789abcdef0123456789abcdef",
            pairSecret = "secret-secret-secret-secret-secret-secret",
            expiresAt = 10_000L,
        )
        val response = WebServer.rootRedirectResponse {
            reads++
            session
        }

        val serialized = WebServer.serializeResponse(response).toString(StandardCharsets.UTF_8)
        val expected = buildString {
            append("HTTP/1.1 302 Found\r\n")
            append("Location: ${PairingSessionManager.connectUrl(session)}\r\n")
            append("Content-Length: 0\r\n")
            append("Connection: close\r\n")
            append("Cache-Control: no-store\r\n")
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

    @Test
    fun serializerUsesUtf8ByteLengthForResponseBody() {
        val serialized = WebServer.serializeResponse(
            WebServer.HttpResponse(
                statusCode = 200,
                reasonPhrase = "OK",
                contentType = "text/plain; charset=utf-8",
                body = "é".toByteArray(StandardCharsets.UTF_8),
            ),
        ).toString(StandardCharsets.UTF_8)

        assertTrue(serialized.contains("Content-Length: 2\r\n"))
        assertEquals("é", serialized.substringAfter("\r\n\r\n"))
    }

    @Test
    fun authorizationRequiresBearerTokenAndUsesConstantTimeComparison() {
        assertEquals(false, WebServer.isAuthorized(emptyMap(), "secret-token"))
        assertEquals(false, WebServer.isAuthorized(mapOf("authorization" to "Basic secret-token"), "secret-token"))
        assertEquals(true, WebServer.isAuthorized(mapOf("authorization" to "Bearer secret-token"), "secret-token"))
        assertEquals(false, WebServer.isAuthorized(mapOf("authorization" to "Bearer wrong"), "secret-token"))
    }
}
