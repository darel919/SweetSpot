package com.darelisme.sweetspot.calibration.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CalibrationResponseSummaryTest {
    @Test
    fun summarizesBothChannelsForTheTv() {
        val response = requireNotNull(
            MeasurementResponsePayload.fromValues(
                sessionId = "session-1",
                current = 2,
                total = 4,
                leftFrequenciesHz = doubleArrayOf(20.0, 1_000.0, 20_000.0),
                leftMagnitudesDb = doubleArrayOf(-12.0, 0.0, 4.5),
                rightFrequenciesHz = doubleArrayOf(20.0, 1_000.0, 20_000.0),
                rightMagnitudesDb = doubleArrayOf(-10.0, -1.0, 3.2),
            ),
        )

        val summary = MeasurementResponseSummary.from(response)

        assertEquals(2, summary.current)
        assertEquals(4, summary.total)
        assertEquals(3, summary.left?.pointCount)
        assertEquals(20.0, summary.left?.firstFrequencyHz ?: 0.0, 0.0)
        assertEquals(20_000.0, summary.left?.lastFrequencyHz ?: 0.0, 0.0)
        assertEquals(-12.0, summary.left?.minimumDb ?: 0.0, 0.0)
        assertEquals(4.5, summary.left?.maximumDb ?: 0.0, 0.0)
        assertEquals(-10.0, summary.right?.minimumDb ?: 0.0, 0.0)
        assertEquals(3.2, summary.right?.maximumDb ?: 0.0, 0.0)
        assertEquals(
            "Phone data received. Take 2 of 4.\n" +
                "L: 3 points, 20 Hz to 20 kHz, -12.0 dB to +4.5 dB\n" +
                "R: 3 points, 20 Hz to 20 kHz, -10.0 dB to +3.2 dB",
            summary.displayText(),
        )
    }

    @Test
    fun identifiesTheMissingChannelInASingleChannelResponse() {
        val response = requireNotNull(
            MeasurementResponsePayload.fromValues(
                sessionId = "session-1",
                current = 1,
                total = 3,
                leftFrequenciesHz = null,
                leftMagnitudesDb = null,
                rightFrequenciesHz = doubleArrayOf(100.0, 2_000.0),
                rightMagnitudesDb = doubleArrayOf(-6.25, 1.0),
            ),
        )

        val summary = MeasurementResponseSummary.from(response)

        assertNull(summary.left)
        assertEquals(2, summary.right?.pointCount)
        assertEquals(
            "Phone data received. Take 1 of 3.\n" +
                "L: not included\n" +
                "R: 2 points, 100 Hz to 2 kHz, -6.3 dB to +1.0 dB",
            summary.displayText(),
        )
    }
}
