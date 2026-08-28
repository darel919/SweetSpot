package com.darelisme.sweetspot

/**
 * Requested calibration curve state. The active flag describes the state itself,
 * not whether a candidate transaction has been committed.
 */
internal data class CalibrationCurveState(
    val common: FloatArray,
    val left: FloatArray?,
    val right: FloatArray?,
    val active: Boolean,
) {
    fun copyArrays(): CalibrationCurveState = copy(
        common = common.copyOf(),
        left = left?.copyOf(),
        right = right?.copyOf(),
    )
}

internal enum class CalibrationValidationStatus {
    APPLYING,
    ROLLING_BACK,
    PENDING,
    PASSED,
    NEUTRAL,
    WORSE,
    INCONCLUSIVE,
    FAILED,
    IMPORTED,
}

internal enum class CalibrationRecoveryTarget {
    PREVIOUS,
    CANDIDATE,
}

internal fun CalibrationValidationStatus.recoveryTarget(): CalibrationRecoveryTarget = when (this) {
    CalibrationValidationStatus.APPLYING,
    CalibrationValidationStatus.ROLLING_BACK,
    CalibrationValidationStatus.WORSE,
    CalibrationValidationStatus.INCONCLUSIVE,
    CalibrationValidationStatus.FAILED -> CalibrationRecoveryTarget.PREVIOUS
    CalibrationValidationStatus.PENDING,
    CalibrationValidationStatus.PASSED,
    CalibrationValidationStatus.NEUTRAL,
    CalibrationValidationStatus.IMPORTED -> CalibrationRecoveryTarget.CANDIDATE
}

/**
 * Persisted calibration candidate transaction.
 *
 * The previous state is the pre-candidate live calibration. The candidate
 * becomes committed when acceptance clears this transaction. The previous state
 * becomes committed when rollback writes it and clears this transaction.
 */
internal data class CalibrationCandidateTransaction(
    val candidateId: String,
    val previous: CalibrationCurveState,
    val candidate: CalibrationCurveState,
    val validationStatus: CalibrationValidationStatus,
    val beforeDb: Float?,
    val afterDb: Float?,
    val reason: String?,
) {
    val previousActive: Boolean
        get() = previous.active

    fun copyArrays(): CalibrationCandidateTransaction = copy(
        previous = previous.copyArrays(),
        candidate = candidate.copyArrays(),
    )
}

internal fun canRollbackCalibrationCandidate(
    transaction: CalibrationCandidateTransaction?,
    candidateId: String,
): Boolean =
    candidateId.isNotBlank() &&
        transaction != null &&
        transaction.candidateId == candidateId &&
        transaction.validationStatus != CalibrationValidationStatus.APPLYING

internal fun CalibrationValidationStatus.rollbackOutcome(): String = when (this) {
    CalibrationValidationStatus.WORSE -> "worse"
    CalibrationValidationStatus.FAILED -> "error"
    CalibrationValidationStatus.APPLYING,
    CalibrationValidationStatus.ROLLING_BACK,
    CalibrationValidationStatus.PENDING,
    CalibrationValidationStatus.PASSED,
    CalibrationValidationStatus.NEUTRAL,
    CalibrationValidationStatus.INCONCLUSIVE,
    CalibrationValidationStatus.IMPORTED -> "inconclusive"
}

internal fun canAcceptCalibrationCandidate(
    transaction: CalibrationCandidateTransaction?,
    candidateId: String,
    liveDspVerified: Boolean,
): Boolean =
    candidateId.isNotBlank() &&
        transaction != null &&
        transaction.candidateId == candidateId &&
        liveDspVerified &&
        (transaction.validationStatus == CalibrationValidationStatus.PASSED
            || transaction.validationStatus == CalibrationValidationStatus.NEUTRAL
            || transaction.validationStatus == CalibrationValidationStatus.IMPORTED)
