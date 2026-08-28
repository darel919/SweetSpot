package com.darelisme.sweetspot

import org.junit.Assert.assertEquals
import org.junit.Test

class CalibrationPositionGuideTest {
    private val context = MeasurementContext(
        positionId = "right",
        reference = "center",
        xCm = 35.0,
        yCm = 0.0,
        zCm = 0.0,
        positionIndex = 1,
        positionCount = 3,
        channel = "both",
        captureKind = "position-composite",
        repairChannel = "both",
        attemptIndex = 1,
        attemptCount = 2,
        phase = "validation",
    )

    @Test
    fun validationMeasuringTitleShowsTheActiveState() {
        assertEquals(
            "VALIDATION • MEASURING • RIGHT POSITION",
            calibrationGuideTitle(context.copy(attemptIndex = 0), CalibrationGuideState.MEASURING),
        )
    }

    @Test
    fun retryMeasuringTitleKeepsBothOperationAndState() {
        assertEquals(
            "VALIDATION • RETRY • MEASURING • RIGHT POSITION",
            calibrationGuideTitle(context, CalibrationGuideState.MEASURING),
        )
    }
}