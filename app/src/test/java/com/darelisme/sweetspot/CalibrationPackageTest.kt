package com.darelisme.sweetspot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CalibrationPackageTest {
    @Test
    fun validPackagePassesImportValidationWithoutMeasurementData() {
        val value = packageValue()

        assertNull(
            CalibrationPackageCodec.validateForImport(
                value,
                EXPECTED_FREQUENCIES,
                independentRoutingVerified = false,
            ),
        )
        assertTrue(value.effectiveBandsDb!!.contentEquals(FloatArray(64) { -it / 128f }))
        assertTrue(value.leftBandsDb == null)
        assertTrue(value.rightBandsDb == null)
        assertEquals("tv-build-1", value.sourceDevice.buildId)
    }

    @Test
    fun importValidationRejectsInactiveMismatchedGridAndUnsafeCurves() {
        assertEquals(
            "Inactive calibration packages cannot be imported",
            CalibrationPackageCodec.validateForImport(
                packageValue().copy(active = false),
                EXPECTED_FREQUENCIES,
                independentRoutingVerified = false,
            ),
        )
        assertEquals(
            "Calibration package frequency grid does not match this TV",
            CalibrationPackageCodec.validateForImport(
                packageValue(),
                IntArray(64) { 20 + it * 301 },
                independentRoutingVerified = false,
            ),
        )
        assertEquals(
            "bandsDb contains an invalid gain",
            CalibrationPackageCodec.validateForImport(
                packageValue().copy(bandsDb = FloatArray(64) { if (it == 5) 12.01f else 0f }),
                EXPECTED_FREQUENCIES,
                independentRoutingVerified = false,
            ),
        )
        assertEquals(
            "Independent channel calibration is not verified on this TV",
            CalibrationPackageCodec.validateForImport(
                packageValue().copy(
                    leftBandsDb = FloatArray(64),
                    rightBandsDb = FloatArray(64),
                ),
                EXPECTED_FREQUENCIES,
                independentRoutingVerified = false,
            ),
        )
    }

    private fun packageValue() = CalibrationPackage(
        exportedAt = 1_700_000_000_000.0,
        sourceDevice = CalibrationPackageSourceDevice("tv_source", "Living Room TV", "0.1.0", "tv-build-1"),
        active = true,
        frequenciesHz = EXPECTED_FREQUENCIES.map { it.toDouble() }.toDoubleArray(),
        bandsDb = FloatArray(64) { -it / 64f },
        effectiveBandsDb = FloatArray(64) { -it / 128f },
    )

    companion object {
        private val EXPECTED_FREQUENCIES = IntArray(64) { 20 + it * 300 }
    }
}
