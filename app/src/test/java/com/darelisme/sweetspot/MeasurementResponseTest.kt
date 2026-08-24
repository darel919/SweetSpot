package com.darelisme.sweetspot

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MeasurementResponseTest {
    @Test
    fun acceptsCompactResponseValuesWithAnOptionalChannel() {
        val response = MeasurementResponsePayload.fromValues(
            sessionId = "session-1",
            current = 2,
            total = 4,
            leftFrequenciesHz = doubleArrayOf(20.0, 100.0, 1_000.0),
            leftMagnitudesDb = doubleArrayOf(-12.0, -8.0, -4.0),
            rightFrequenciesHz = null,
            rightMagnitudesDb = null
        )

        assertNotNull(response)
        val actual = response!!
        assertEquals("session-1", actual.sessionId)
        assertEquals(2, actual.current)
        assertEquals(4, actual.total)
        assertArrayEquals(doubleArrayOf(20.0, 100.0, 1_000.0), actual.left!!.frequenciesHz, 0.0)
        assertArrayEquals(doubleArrayOf(-12.0, -8.0, -4.0), actual.left.magnitudesDb, 0.0)
        assertNull(actual.right)
    }

    @Test
    fun rejectsInvalidTraceShapesAndValues() {
        val duplicateFrequency = responseWithLeft(
            doubleArrayOf(20.0, 100.0, 100.0),
            doubleArrayOf(-12.0, -8.0, -4.0)
        )
        val mismatchedLengths = responseWithLeft(
            doubleArrayOf(20.0, 100.0),
            doubleArrayOf(-12.0)
        )
        val nonFiniteMagnitude = responseWithLeft(
            doubleArrayOf(20.0, 100.0),
            doubleArrayOf(-12.0, Double.NaN)
        )
        val tooFewPoints = responseWithLeft(
            doubleArrayOf(20.0),
            doubleArrayOf(-12.0)
        )
        val nonPositiveFrequency = responseWithLeft(
            doubleArrayOf(0.0, 100.0),
            doubleArrayOf(-12.0, -8.0)
        )
        val nonFiniteFrequency = responseWithLeft(
            doubleArrayOf(20.0, Double.POSITIVE_INFINITY),
            doubleArrayOf(-12.0, -8.0)
        )
        val tooManyPoints = responseWithLeft(
            DoubleArray(65) { index -> 20.0 + index },
            DoubleArray(65) { -12.0 }
        )

        assertNull(duplicateFrequency)
        assertNull(mismatchedLengths)
        assertNull(nonFiniteMagnitude)
        assertNull(tooFewPoints)
        assertNull(nonPositiveFrequency)
        assertNull(nonFiniteFrequency)
        assertNull(tooManyPoints)
    }

    @Test
    fun rejectsInvalidSessionProgressAndPartialChannels() {
        assertNull(responseWithLeft(doubleArrayOf(20.0, 20_000.0), doubleArrayOf(-12.0, -18.0), sessionId = ""))
        assertNull(responseWithLeft(doubleArrayOf(20.0, 20_000.0), null))
        assertNull(
            MeasurementResponsePayload.fromValues(
                sessionId = "session-1",
                current = 1,
                total = 1,
                leftFrequenciesHz = null,
                leftMagnitudesDb = null,
                rightFrequenciesHz = null,
                rightMagnitudesDb = null
            )
        )
        assertNull(
            MeasurementResponsePayload.fromValues(
                sessionId = "session-1",
                current = 5,
                total = 4,
                leftFrequenciesHz = doubleArrayOf(20.0, 20_000.0),
                leftMagnitudesDb = doubleArrayOf(-12.0, -18.0),
                rightFrequenciesHz = null,
                rightMagnitudesDb = null
            )
        )
    }

    @Test
    fun forwardsOnlyTheActiveSessionResponse() {
        val active = parsedResponse("active")
        val stale = parsedResponse("stale")

        assertTrue(shouldForwardMeasurementResponse("active", active))
        assertFalse(shouldForwardMeasurementResponse(null, active))
        assertFalse(shouldForwardMeasurementResponse("active", stale))
    }

    private fun responseWithLeft(
        frequenciesHz: DoubleArray?,
        magnitudesDb: DoubleArray?,
        sessionId: String = "session-1"
    ): MeasurementResponse? = MeasurementResponsePayload.fromValues(
        sessionId = sessionId,
        current = 1,
        total = 1,
        leftFrequenciesHz = frequenciesHz,
        leftMagnitudesDb = magnitudesDb,
        rightFrequenciesHz = null,
        rightMagnitudesDb = null
    )

    private fun parsedResponse(sessionId: String): MeasurementResponse = requireNotNull(
        responseWithLeft(
            frequenciesHz = doubleArrayOf(20.0, 20_000.0),
            magnitudesDb = doubleArrayOf(-12.0, -18.0),
            sessionId = sessionId
        )
    )
}
