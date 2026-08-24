package com.darelisme.sweetspot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DynamicsProcessingCalibrationSafetyTest {
    @Test
    fun calibrationArraysRequireFiniteValuesWithinTheAuthoritativeLimit() {
        assertTrue(DynamicsProcessingEq.isValidCalibrationArray(FloatArray(64)))
        assertTrue(
            DynamicsProcessingEq.isValidCalibrationArray(
                FloatArray(64) { index ->
                    if (index == 0) DynamicsProcessingEq.MAX_CALIBRATION_GAIN_DB else -DynamicsProcessingEq.MAX_CALIBRATION_GAIN_DB
                }
            )
        )
        assertFalse(
            DynamicsProcessingEq.isValidCalibrationArray(
                FloatArray(64) { index -> if (index == 12) DynamicsProcessingEq.MAX_CALIBRATION_GAIN_DB + 0.01f else 0f }
            )
        )
        assertFalse(
            DynamicsProcessingEq.isValidCalibrationArray(
                FloatArray(64) { index -> if (index == 12) Float.NaN else 0f }
            )
        )
        assertFalse(DynamicsProcessingEq.isValidCalibrationArray(FloatArray(63)))
    }

    @Test
    fun missingHeadroomCannotTurnARequestedBoostIntoAnAppliedBoost() {
        assertEquals(
            0f,
            DynamicsProcessingEq.effectiveCalibrationGain(4f, 0f, headroomVerified = false),
            0f
        )
        assertEquals(
            -2f,
            DynamicsProcessingEq.effectiveCalibrationGain(-2f, 0f, headroomVerified = false),
            0f
        )
    }

    @Test
    fun verifiedHeadroomPreservesTheRequestedEffectiveGain() {
        assertEquals(
            4f,
            DynamicsProcessingEq.effectiveCalibrationGain(2f, 2f, headroomVerified = true),
            0f
        )
    }
}
