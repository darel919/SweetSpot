package com.darelisme.sweetspot.transport

import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

class TransportControlFixtureTest {
    @Test
    fun sharedCaptureControlFixturesKeepTheirWireSemantics() {
        val fixtures = requireNotNull(javaClass.classLoader?.getResourceAsStream("transport-control-events.json"))
            .bufferedReader()
            .use { it.readText() }
        assertTrue(fixtures.contains("\"type\": \"calibration.capture.started\""))
        assertTrue(fixtures.contains("\"jobId\": \"job-1\""))
        assertTrue(fixtures.contains("\"captureId\": \"capture-1\""))
        assertTrue(fixtures.contains("\"captureAttemptId\": \"capture-attempt-1\""))
        assertTrue(fixtures.contains("\"nextSequence\": 8"))
        assertTrue(fixtures.contains("\"windowSize\": 8"))
        assertTrue(Regex("\"invalidWindow\"[\\s\\S]*?\"windowSize\": 0").containsMatchIn(fixtures))

        val oversized = fixtures.replace("\"paddingBytes\": 16384", "\"padding\": \"${"x".repeat(16_384)}\"")
        assertTrue(oversized.toByteArray(StandardCharsets.UTF_8).size > 16 * 1024)
    }
}
