package com.darelisme.sweetspot

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
    CalibrationValidationStatus.IMPORTED -> CalibrationRecoveryTarget.CANDIDATE
}

internal data class CalibrationCandidateTransaction(
    val candidateId: String,
    val previous: CalibrationCurveState,
    val candidate: CalibrationCurveState,
    val validationStatus: CalibrationValidationStatus,
    val beforeDb: Float?,
    val afterDb: Float?,
    val reason: String?,
) {
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
            || transaction.validationStatus == CalibrationValidationStatus.IMPORTED)
