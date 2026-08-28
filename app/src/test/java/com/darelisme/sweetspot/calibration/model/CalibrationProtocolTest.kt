package com.darelisme.sweetspot.calibration.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CalibrationProtocolTest {
    @Test
    fun abortRequiresARecognizedCode() {
        assertNull(parseCalibrationSessionAbortValues("session-1", "", null))
        assertNull(parseCalibrationSessionAbortValues("session-1", "unknown_failure", null))
        listOf(
            "direct_arrival_low_confidence",
            "impulse_not_found",
            "response_not_generated",
            "capture_sample_rate_changed",
        ).forEach { code ->
            assertEquals(code, parseCalibrationSessionAbortValues("session-1", code, null)?.code)
        }
    }

    @Test
    fun abortPreservesRecognizedReasonAndBoundsTheOptionalMessage() {
        val parsed = parseCalibrationSessionAbortValues(
            sessionId = "session-1",
            code = "signal_too_low",
            message = "The validation signal was too quiet",
        )

        assertEquals("session-1", parsed?.sessionId)
        assertEquals("signal_too_low", parsed?.code)
        assertEquals("The validation signal was too quiet", parsed?.message)

        assertNull(
            parseCalibrationSessionAbortValues(
                sessionId = "session-1",
                code = "signal_too_low",
                message = "x".repeat(MAX_CALIBRATION_ABORT_MESSAGE_LENGTH + 1),
            ),
        )
    }

    @Test
    fun sessionEndOutcomesAreClosedAndUnknownValuesAreRejected() {
        assertEquals(true, isCalibrationSessionOutcome("sufficient"))
        assertEquals(true, isCalibrationSessionOutcome("bounded"))
        assertEquals(true, isCalibrationSessionOutcome("insufficient"))
        assertEquals(true, isCalibrationSessionOutcome("cancelled"))
        assertEquals(true, isCalibrationSessionOutcome("error"))
        assertEquals(false, isCalibrationSessionOutcome("unknown"))
    }
}
