package com.darelisme.sweetspot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CalibrationRecoveryTest {
    @Test
    fun validationRecoveryRestoresBeforeRollingBackAndRunsOnce() {
        val events = mutableListOf<String>()
        var restoreCalls = 0
        var rollbackCalls = 0
        var verifyCalls = 0
        val gate = ValidationRecoveryGate()

        val first = gate.recover(
            candidateId = "candidate-1",
            restoreValidationState = {
                restoreCalls += 1
                events += "restore"
                true
            },
            rollbackCandidate = { candidateId ->
                rollbackCalls += 1
                events += "rollback:$candidateId"
                true
            },
            verifyFinalState = {
                verifyCalls += 1
                events += "verify"
                true
            },
        )
        val second = gate.recover(
            candidateId = "candidate-1",
            restoreValidationState = { error("restore must not run twice") },
            rollbackCandidate = { error("rollback must not run twice") },
            verifyFinalState = { error("verify must not run twice") },
        )

        assertTrue(first.finalStateVerified)
        assertEquals(first, second)
        assertEquals(listOf("restore", "rollback:candidate-1", "verify"), events)
        assertEquals(1, restoreCalls)
        assertEquals(1, rollbackCalls)
        assertEquals(1, verifyCalls)
    }

    @Test
    fun recoveryStillAttemptsTheSingleRollbackAfterRestoreFailure() {
        val events = mutableListOf<String>()
        val result = ValidationRecoveryGate().recover(
            candidateId = "candidate-2",
            restoreValidationState = {
                events += "restore"
                false
            },
            rollbackCandidate = { candidateId ->
                events += "rollback:$candidateId"
                true
            },
            verifyFinalState = {
                events += "verify"
                true
            },
        )

        assertEquals(false, result.validationStateRestored)
        assertEquals(true, result.candidateRolledBack)
        assertEquals(false, result.finalStateVerified)
        assertEquals(listOf("restore", "rollback:candidate-2"), events)
    }
}
