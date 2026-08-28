package com.darelisme.sweetspot.audio.engine

import com.darelisme.sweetspot.calibration.model.CalibrationCandidateTransaction
import com.darelisme.sweetspot.calibration.model.CalibrationCurveState
import com.darelisme.sweetspot.calibration.model.CalibrationRecoveryTarget
import com.darelisme.sweetspot.calibration.model.CalibrationValidationStatus
import com.darelisme.sweetspot.calibration.model.canAcceptCalibrationCandidate
import com.darelisme.sweetspot.calibration.model.recoveryTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    @Test
    fun validationMetricsAreRequiredForPassedOrWorseAndMissingMetricsBecomeInconclusive() {
        val result = DynamicsProcessingEq.normalizeValidationResult(
            requestedStatus = CalibrationValidationStatus.PASSED,
            beforeDb = null,
            afterDb = null,
            reason = null,
        )

        assertEquals(CalibrationValidationStatus.INCONCLUSIVE, result.status)
        assertNull(result.beforeDb)
        assertNull(result.afterDb)
        assertEquals("Validation metrics were unavailable", result.reason)
    }

    @Test
    fun validationStatusIsDerivedFromTheSharedWorseTolerance() {
        val worse = DynamicsProcessingEq.normalizeValidationResult(
            requestedStatus = CalibrationValidationStatus.PASSED,
            beforeDb = 4f,
            afterDb = 4f + DynamicsProcessingEq.VALIDATION_WORSE_TOLERANCE_DB + 0.01f,
            reason = null,
        )
        val passed = DynamicsProcessingEq.normalizeValidationResult(
            requestedStatus = CalibrationValidationStatus.WORSE,
            beforeDb = 4f,
            afterDb = 4f + DynamicsProcessingEq.VALIDATION_WORSE_TOLERANCE_DB,
            reason = null,
        )
        val improved = DynamicsProcessingEq.normalizeValidationResult(
            requestedStatus = CalibrationValidationStatus.WORSE,
            beforeDb = 4f,
            afterDb = 4f - DynamicsProcessingEq.VALIDATION_WORSE_TOLERANCE_DB - 0.01f,
            reason = null,
        )

        assertEquals(CalibrationValidationStatus.WORSE, worse.status)
        assertEquals(CalibrationValidationStatus.INCONCLUSIVE, passed.status)
        assertEquals(CalibrationValidationStatus.PASSED, improved.status)
    }

    @Test
    fun explicitFailedStatusIsPreservedWithoutMetricsOrReasonLoss() {
        val result = DynamicsProcessingEq.normalizeValidationResult(
            requestedStatus = CalibrationValidationStatus.FAILED,
            beforeDb = null,
            afterDb = null,
            reason = "marker timing failed",
        )

        assertEquals(CalibrationValidationStatus.FAILED, result.status)
        assertEquals("marker timing failed", result.reason)
        assertNull(result.beforeDb)
        assertNull(result.afterDb)
    }

    @Test
    fun explicitInconclusiveStatusDoesNotRequireMetrics() {
        val result = DynamicsProcessingEq.normalizeValidationResult(
            requestedStatus = CalibrationValidationStatus.INCONCLUSIVE,
            beforeDb = null,
            afterDb = null,
            reason = "change was within tolerance",
        )

        assertEquals(CalibrationValidationStatus.INCONCLUSIVE, result.status)
        assertEquals("change was within tolerance", result.reason)
    }

    @Test
    fun validationAllowsCutOnlyCandidatesWithoutVerifiedHeadroom() {
        assertFalse(DynamicsProcessingEq.candidateRequiresHeadroom(candidateTransaction(CalibrationValidationStatus.PENDING)))
        assertTrue(DynamicsProcessingEq.candidateRequiresHeadroom(candidateTransaction(CalibrationValidationStatus.PENDING, positive = true)))
    }

    @Test
    fun resetFailureTelemetryNamesTheFailureAndRollbackOutcome() {
        val restored = DynamicsProcessingEq.calibrationResetFailureMessage(
            DynamicsProcessingEq.ResetFailureStage.APPLY,
            "input gain rejected",
            restored = true,
        )
        val notRestored = DynamicsProcessingEq.calibrationResetFailureMessage(
            DynamicsProcessingEq.ResetFailureStage.PERSIST,
            "preferences unavailable",
            restored = false,
        )

        assertTrue(restored.contains("reset application failed"))
        assertTrue(restored.contains("previous calibration was restored and verified"))
        assertTrue(notRestored.contains("reset persistence failed"))
        assertTrue(notRestored.contains("previous calibration could not be verified"))
    }

    @Test
    fun theVerifiedTargetTvAllowsCalibrationCandidates() {
        assertTrue(DynamicsProcessingEq.BAND_TRANSFER_CHARACTERIZED)
        assertNull(DynamicsProcessingEq.calibrationTransferCharacterizationError())
    }

    @Test
    fun onlyPassedOrImportedCandidatesCanBeAcceptedAfterLiveVerification() {
        val imported = candidateTransaction(CalibrationValidationStatus.IMPORTED)
        val passed = candidateTransaction(CalibrationValidationStatus.PASSED)
        val pending = candidateTransaction(CalibrationValidationStatus.PENDING)

        assertTrue(canAcceptCalibrationCandidate(imported, "candidate", liveDspVerified = true))
        assertTrue(canAcceptCalibrationCandidate(passed, "candidate", liveDspVerified = true))
        assertFalse(canAcceptCalibrationCandidate(pending, "candidate", liveDspVerified = true))
        assertFalse(canAcceptCalibrationCandidate(imported, "candidate", liveDspVerified = false))
    }

    private fun candidateTransaction(status: CalibrationValidationStatus, positive: Boolean = false) = CalibrationCandidateTransaction(
        candidateId = "candidate",
        previous = CalibrationCurveState(FloatArray(64), null, null, true),
        candidate = CalibrationCurveState(FloatArray(64) { if (positive) 1f else -1f }, null, null, true),
        validationStatus = status,
        beforeDb = null,
        afterDb = null,
        reason = null,
    )

    private class FakePreferences : android.content.SharedPreferences {
        private val values = linkedMapOf<String, Any?>()

        override fun getAll(): MutableMap<String, *> = values.toMutableMap()
        override fun getString(key: String, defValue: String?): String? = values[key] as? String ?: defValue
        override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? = defValues
        override fun getInt(key: String, defValue: Int): Int = values[key] as? Int ?: defValue
        override fun getLong(key: String, defValue: Long): Long = values[key] as? Long ?: defValue
        override fun getFloat(key: String, defValue: Float): Float = values[key] as? Float ?: defValue
        override fun getBoolean(key: String, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue
        override fun contains(key: String): Boolean = values.containsKey(key)
        override fun edit(): android.content.SharedPreferences.Editor = Editor()
        override fun registerOnSharedPreferenceChangeListener(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener) = Unit

        private inner class Editor : android.content.SharedPreferences.Editor {
            override fun putString(key: String, value: String?): android.content.SharedPreferences.Editor { values[key] = value; return this }
            override fun putStringSet(key: String, value: MutableSet<String>?): android.content.SharedPreferences.Editor { values[key] = value; return this }
            override fun putInt(key: String, value: Int): android.content.SharedPreferences.Editor { values[key] = value; return this }
            override fun putLong(key: String, value: Long): android.content.SharedPreferences.Editor { values[key] = value; return this }
            override fun putFloat(key: String, value: Float): android.content.SharedPreferences.Editor { values[key] = value; return this }
            override fun putBoolean(key: String, value: Boolean): android.content.SharedPreferences.Editor { values[key] = value; return this }
            override fun remove(key: String): android.content.SharedPreferences.Editor { values.remove(key); return this }
            override fun clear(): android.content.SharedPreferences.Editor { values.clear(); return this }
            override fun commit(): Boolean = true
            override fun apply() = Unit
        }
    }
}
