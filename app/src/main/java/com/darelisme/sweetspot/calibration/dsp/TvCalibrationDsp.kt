package com.darelisme.sweetspot.calibration.dsp

import com.darelisme.sweetspot.audio.engine.DynamicsProcessingEq
import com.darelisme.sweetspot.calibration.analysis.*
import com.darelisme.sweetspot.calibration.model.*
import com.darelisme.sweetspot.calibration.model.CalibrationValidationStatus
import com.darelisme.sweetspot.calibration.playback.CalibrationAudioResult
import com.darelisme.sweetspot.calibration.playback.CalibrationDspPort

internal class TvCalibrationDsp(
    private val eq: DynamicsProcessingEq,
) : CalibrationDspPort {
    override fun pendingCandidateId(): CandidateId? = eq.getCalibrationTransaction()?.candidateId?.let(::CandidateId)

    override fun pendingValidationOutcome(): ValidationOutcome? = when (eq.getCalibrationTransaction()?.validationStatus) {
        CalibrationValidationStatus.PASSED -> ValidationOutcome.IMPROVED
        CalibrationValidationStatus.NEUTRAL -> ValidationOutcome.NEUTRAL
        CalibrationValidationStatus.WORSE -> ValidationOutcome.WORSE
        CalibrationValidationStatus.INCONCLUSIVE -> ValidationOutcome.INCONCLUSIVE_CAPTURE
        CalibrationValidationStatus.FAILED -> ValidationOutcome.DSP_ERROR
        else -> null
    }

    override fun stageCandidate(solution: CalibrationSolution): CalibrationAudioResult {
        if (!eq.applyCalibrationCandidate(solution.correctionDb.toFloatArray())) {
            return CalibrationAudioResult.Failure(
                eq.getLastCalibrationApplyError() ?: "Calibration candidate was rejected",
            )
        }
        val transaction = eq.getCalibrationTransaction()
            ?: return CalibrationAudioResult.Failure("Calibration candidate transaction was not persisted")
        return CalibrationAudioResult.Success(CandidateId(transaction.candidateId))
    }

    override fun recordValidation(
        candidateId: CandidateId,
        outcome: ValidationOutcome,
        beforeDb: Float?,
        afterDb: Float?,
        reason: String?,
    ): CalibrationAudioResult {
        val status = when (outcome) {
            ValidationOutcome.IMPROVED -> CalibrationValidationStatus.PASSED
            ValidationOutcome.NEUTRAL -> CalibrationValidationStatus.NEUTRAL
            ValidationOutcome.WORSE -> CalibrationValidationStatus.WORSE
            ValidationOutcome.INCONCLUSIVE_CAPTURE -> CalibrationValidationStatus.INCONCLUSIVE
            ValidationOutcome.DSP_ERROR -> CalibrationValidationStatus.FAILED
        }
        return if (eq.recordCalibrationValidation(candidateId.value, status, beforeDb, afterDb, reason)) {
            CalibrationAudioResult.Success()
        } else {
            CalibrationAudioResult.Failure(
                eq.getLastCalibrationApplyError() ?: "Calibration validation could not be persisted",
            )
        }
    }

    override fun acceptCandidate(candidateId: CandidateId): CalibrationAudioResult =
        if (eq.acceptCalibrationCandidate(candidateId.value)) {
            CalibrationAudioResult.Success()
        } else {
            CalibrationAudioResult.Failure(
                eq.getLastCalibrationApplyError() ?: "Calibration candidate was not accepted",
            )
        }

    override fun rollbackCandidate(candidateId: CandidateId): CalibrationAudioResult =
        if (eq.rollbackCalibrationCandidate(candidateId.value)) {
            CalibrationAudioResult.Success()
        } else {
            CalibrationAudioResult.Failure(
                eq.getLastCalibrationApplyError() ?: "Calibration candidate rollback failed",
            )
        }

    override fun isLiveDspVerified(): Boolean = eq.isLiveDspVerified()
}
