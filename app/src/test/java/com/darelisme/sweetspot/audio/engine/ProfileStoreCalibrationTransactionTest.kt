package com.darelisme.sweetspot.audio.engine

import com.darelisme.sweetspot.calibration.model.CalibrationCandidateTransaction
import com.darelisme.sweetspot.calibration.model.CalibrationCurveState
import com.darelisme.sweetspot.calibration.model.CalibrationRecoveryTarget
import com.darelisme.sweetspot.calibration.model.CalibrationValidationStatus
import com.darelisme.sweetspot.calibration.model.canRollbackCalibrationCandidate
import com.darelisme.sweetspot.calibration.model.recoveryTarget
import com.darelisme.sweetspot.calibration.model.rollbackOutcome
import android.content.SharedPreferences
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileStoreCalibrationTransactionTest {
    @Test
    fun rollbackRequiresTheExactCandidateId() {
        val transaction = CalibrationCandidateTransaction(
            candidateId = "candidate-rollback",
            previous = CalibrationCurveState(FloatArray(64), null, null, true),
            candidate = CalibrationCurveState(FloatArray(64) { 1f }, null, null, true),
            validationStatus = CalibrationValidationStatus.PENDING,
            beforeDb = null,
            afterDb = null,
            reason = null,
        )

        assertTrue(canRollbackCalibrationCandidate(transaction, "candidate-rollback"))
        assertTrue(!canRollbackCalibrationCandidate(transaction, "stale-candidate"))
    }

    @Test
    fun candidateStateSurvivesApplyingPendingValidationAndClear() {
        val store = ProfileStore(FakePreferences())
        val previous = CalibrationCurveState(FloatArray(64) { -1f }, null, null, true)
        val candidate = CalibrationCurveState(FloatArray(64) { 2f }, null, null, true)
        val transaction = CalibrationCandidateTransaction(
            candidateId = "candidate-1",
            previous = previous,
            candidate = candidate,
            validationStatus = CalibrationValidationStatus.APPLYING,
            beforeDb = null,
            afterDb = null,
            reason = null,
        )

        store.saveCalibration(previous.common)
        assertTrue(store.saveCandidateApplying(transaction))
        assertEquals(CalibrationValidationStatus.APPLYING, store.loadCalibrationTransaction()?.validationStatus)
        assertTrue(previous.common.contentEquals(store.loadCalibrationTransaction()?.previous?.common ?: FloatArray(0)))

        assertTrue(store.saveCandidatePendingValidation(transaction.copy(validationStatus = CalibrationValidationStatus.PENDING)))
        assertTrue(candidate.common.contentEquals(store.loadCalibration() ?: FloatArray(0)))
        assertEquals(CalibrationValidationStatus.PENDING, store.loadCalibrationTransaction()?.validationStatus)
        assertEquals(
            CalibrationRecoveryTarget.CANDIDATE,
            store.loadCalibrationTransaction()?.validationStatus?.recoveryTarget(),
        )

        assertTrue(
            store.saveCandidateValidation(
                transaction.copy(
                    validationStatus = CalibrationValidationStatus.PASSED,
                    beforeDb = 4f,
                    afterDb = 2f,
                ),
            ),
        )
        assertEquals(CalibrationValidationStatus.PASSED, store.loadCalibrationTransaction()?.validationStatus)
        assertEquals(4f, store.loadCalibrationTransaction()?.beforeDb)
        assertEquals(2f, store.loadCalibrationTransaction()?.afterDb)

        assertTrue(store.saveActiveCalibrationAndClearCandidate(previous))
        assertTrue(previous.common.contentEquals(store.loadCalibration() ?: FloatArray(0)))
        assertNull(store.loadCalibrationTransaction())
    }

    @Test
    fun copyArraysPreventsReconnectStateFromSharingMutableCurves() {
        val original = CalibrationCandidateTransaction(
            candidateId = "candidate-2",
            previous = CalibrationCurveState(FloatArray(64) { -1f }, null, null, true),
            candidate = CalibrationCurveState(
                FloatArray(64) { 1f },
                FloatArray(64) { 2f },
                FloatArray(64) { 3f },
                true,
            ),
            validationStatus = CalibrationValidationStatus.PENDING,
            beforeDb = null,
            afterDb = null,
            reason = null,
        )

        val copy = original.copyArrays()
        copy.candidate.common[0] = 99f
        copy.candidate.left?.set(0, 98f)
        copy.candidate.right?.set(0, 97f)

        assertEquals(1f, original.candidate.common[0])
        assertEquals(2f, original.candidate.left?.get(0))
        assertEquals(3f, original.candidate.right?.get(0))
    }

    @Test
    fun rollbackIntentSurvivesReloadBeforeThePreviousCurveIsApplied() {
        val store = ProfileStore(FakePreferences())
        val transaction = CalibrationCandidateTransaction(
            candidateId = "candidate-rollback",
            previous = CalibrationCurveState(FloatArray(64) { -1f }, null, null, true),
            candidate = CalibrationCurveState(FloatArray(64) { 2f }, null, null, true),
            validationStatus = CalibrationValidationStatus.PENDING,
            beforeDb = null,
            afterDb = null,
            reason = null,
        )

        assertTrue(store.saveCandidateApplying(transaction))
        assertTrue(store.saveCandidateRollingBack(transaction))

        assertEquals(
            CalibrationValidationStatus.ROLLING_BACK,
            store.loadCalibrationTransaction()?.validationStatus,
        )
        assertEquals(
            CalibrationRecoveryTarget.PREVIOUS,
            store.loadCalibrationTransaction()?.validationStatus?.recoveryTarget(),
        )
    }

    @Test
    fun importedCandidateSurvivesReloadAndRecoversToCandidate() {
        val preferences = FakePreferences()
        val store = ProfileStore(preferences)
        val candidate = CalibrationCurveState(FloatArray(64) { 2f }, null, null, true)
        val transaction = CalibrationCandidateTransaction(
            candidateId = "candidate-imported",
            previous = CalibrationCurveState(FloatArray(64) { -1f }, null, null, true),
            candidate = candidate,
            validationStatus = CalibrationValidationStatus.IMPORTED,
            beforeDb = null,
            afterDb = null,
            reason = "Imported from another TV",
        )

        assertTrue(store.saveCandidateImported(transaction))
        val reloaded = ProfileStore(preferences).loadCalibrationTransaction()

        assertEquals(CalibrationValidationStatus.IMPORTED, reloaded?.validationStatus)
        assertEquals(CalibrationRecoveryTarget.CANDIDATE, reloaded?.validationStatus?.recoveryTarget())
        assertTrue(candidate.common.contentEquals(reloaded?.candidate?.common ?: FloatArray(0)))
    }

    @Test
    fun inactivePreCandidateStateSurvivesReloadAndRollbackCommit() {
        val store = ProfileStore(FakePreferences())
        val previous = CalibrationCurveState(FloatArray(64) { -1f }, null, null, false)
        val candidate = CalibrationCurveState(FloatArray(64) { 2f }, null, null, true)
        val transaction = CalibrationCandidateTransaction(
            candidateId = "candidate-inactive-previous",
            previous = previous,
            candidate = candidate,
            validationStatus = CalibrationValidationStatus.PENDING,
            beforeDb = null,
            afterDb = null,
            reason = null,
        )

        assertTrue(store.saveCandidatePendingValidation(transaction))
        val reloaded = requireNotNull(store.loadCalibrationTransaction())
        assertFalse(reloaded.previousActive)
        assertFalse(reloaded.previous.active)
        assertTrue(store.loadCalibration() != null)

        assertTrue(store.saveActiveCalibrationAndClearCandidate(reloaded.previous))
        assertNull(store.loadCalibration())
        assertNull(store.loadCalibrationTransaction())
    }

    @Test
    fun rollbackCommitPreservesTheSeparateUserEqProfile() {
        val store = ProfileStore(FakePreferences())
        val userLevels = intArrayOf(120, -80, 40)
        store.save(enabled = false, preset = 2, levels = userLevels)
        val transaction = CalibrationCandidateTransaction(
            candidateId = "candidate-user-profile",
            previous = CalibrationCurveState(FloatArray(64) { -1f }, null, null, false),
            candidate = CalibrationCurveState(FloatArray(64) { 2f }, null, null, true),
            validationStatus = CalibrationValidationStatus.PENDING,
            beforeDb = null,
            afterDb = null,
            reason = null,
        )

        assertTrue(store.saveCandidatePendingValidation(transaction))
        assertTrue(store.saveActiveCalibrationAndClearCandidate(transaction.previous))

        val profile = store.load()
        assertFalse(profile.enabled)
        assertEquals(2, profile.preset)
        assertArrayEquals(userLevels, profile.levels)
    }

    @Test
    fun rollbackOutcomeUsesThePersistedValidationStatus() {
        val store = ProfileStore(FakePreferences())
        val base = CalibrationCandidateTransaction(
            candidateId = "candidate-outcome",
            previous = CalibrationCurveState(FloatArray(64), null, null, true),
            candidate = CalibrationCurveState(FloatArray(64) { 1f }, null, null, true),
            validationStatus = CalibrationValidationStatus.WORSE,
            beforeDb = 4f,
            afterDb = 5f,
            reason = "worse",
        )

        listOf(
            CalibrationValidationStatus.WORSE to "worse",
            CalibrationValidationStatus.INCONCLUSIVE to "inconclusive",
            CalibrationValidationStatus.FAILED to "error",
        ).forEach { (status, outcome) ->
            assertTrue(store.saveCandidateValidation(base.copy(validationStatus = status)))
            val reloaded = requireNotNull(store.loadCalibrationTransaction())
            assertEquals(outcome, reloaded.validationStatus.rollbackOutcome())
        }
    }

    @Test
    fun rollbackPersistenceFailureLeavesTheCandidateTransactionRecoverable() {
        val preferences = FakePreferences()
        val store = ProfileStore(preferences)
        val previous = CalibrationCurveState(FloatArray(64) { -1f }, null, null, false)
        val transaction = CalibrationCandidateTransaction(
            candidateId = "candidate-persist-failure",
            previous = previous,
            candidate = CalibrationCurveState(FloatArray(64) { 2f }, null, null, true),
            validationStatus = CalibrationValidationStatus.WORSE,
            beforeDb = 4f,
            afterDb = 5f,
            reason = "worse",
        )

        assertTrue(store.saveCandidatePendingValidation(transaction.copy(validationStatus = CalibrationValidationStatus.PENDING)))
        assertTrue(store.saveCandidateValidation(transaction))
        preferences.failCommit = true

        assertFalse(store.saveActiveCalibrationAndClearCandidate(previous))
        assertEquals(transaction.candidateId, store.loadCalibrationTransaction()?.candidateId)
        assertTrue(store.loadCalibration() != null)
    }

    private class FakePreferences : SharedPreferences {
        private val values = linkedMapOf<String, Any?>()
        var failCommit = false

        override fun getAll(): MutableMap<String, *> = values.toMutableMap()
        override fun getString(key: String, defValue: String?): String? = values[key] as? String ?: defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
            (values[key] as? Set<String>)?.toMutableSet() ?: defValues
        override fun getInt(key: String, defValue: Int): Int = values[key] as? Int ?: defValue
        override fun getLong(key: String, defValue: Long): Long = values[key] as? Long ?: defValue
        override fun getFloat(key: String, defValue: Float): Float = values[key] as? Float ?: defValue
        override fun getBoolean(key: String, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue
        override fun contains(key: String): Boolean = values.containsKey(key)
        override fun edit(): SharedPreferences.Editor = Editor()
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = Unit

        private inner class Editor : SharedPreferences.Editor {
            private val pending = values.toMutableMap()

            override fun putString(key: String, value: String?): SharedPreferences.Editor {
                if (value == null) pending.remove(key) else pending[key] = value
                return this
            }

            override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor {
                if (values == null) pending.remove(key) else pending[key] = values.toSet()
                return this
            }

            override fun putInt(key: String, value: Int): SharedPreferences.Editor { pending[key] = value; return this }
            override fun putLong(key: String, value: Long): SharedPreferences.Editor { pending[key] = value; return this }
            override fun putFloat(key: String, value: Float): SharedPreferences.Editor { pending[key] = value; return this }
            override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor { pending[key] = value; return this }
            override fun remove(key: String): SharedPreferences.Editor { pending.remove(key); return this }
            override fun clear(): SharedPreferences.Editor { pending.clear(); return this }
            override fun commit(): Boolean {
                if (failCommit) return false
                values.clear()
                values.putAll(pending)
                return true
            }
            override fun apply() = Unit
        }
    }
}
