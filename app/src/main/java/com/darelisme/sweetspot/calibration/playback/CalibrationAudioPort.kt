package com.darelisme.sweetspot.calibration.playback

import com.darelisme.sweetspot.calibration.model.*

sealed interface CalibrationAudioResult {
    data class Success(val candidateId: CandidateId? = null) : CalibrationAudioResult
    data class Failure(
        val message: String,
        val code: String = "playback_failed",
        val retryable: Boolean = true,
    ) : CalibrationAudioResult
}

interface CalibrationPlaybackPort {
    fun start(request: CaptureRequest, onFinished: () -> Unit): CalibrationAudioResult
    fun startWithFailure(
        request: CaptureRequest,
        onFinished: () -> Unit,
        onFailure: (CalibrationAudioResult.Failure) -> Unit,
    ): CalibrationAudioResult = start(request, onFinished)

    fun startValidation(action: CalibrationAction.Validate, onFinished: () -> Unit): CalibrationAudioResult =
        start(
            CaptureRequest(
                captureId = action.captureId,
                position = action.position,
                channel = CaptureChannel.BOTH,
                attemptIndex = action.attemptIndex,
                optional = false,
            ),
            onFinished,
        )

    fun startValidationWithFailure(
        action: CalibrationAction.Validate,
        onFinished: () -> Unit,
        onFailure: (CalibrationAudioResult.Failure) -> Unit,
    ): CalibrationAudioResult = startValidation(action, onFinished)
    fun cancel(request: CaptureRequest): CalibrationAudioResult = CalibrationAudioResult.Success()
}

interface CalibrationDspPort {
    /** Returns the persisted candidate when a prior transaction is unresolved. */
    fun pendingCandidateId(): CandidateId? = null

    /** Returns a recorded validation outcome when validation or acceptance was interrupted. */
    fun pendingValidationOutcome(): ValidationOutcome? = null

    fun stageCandidate(solution: CalibrationSolution): CalibrationAudioResult
    fun recordValidation(
        candidateId: CandidateId,
        outcome: ValidationOutcome,
        beforeDb: Float?,
        afterDb: Float?,
        reason: String? = null,
    ): CalibrationAudioResult
    fun acceptCandidate(candidateId: CandidateId): CalibrationAudioResult
    fun rollbackCandidate(candidateId: CandidateId): CalibrationAudioResult
    fun isLiveDspVerified(): Boolean
}

object NoopCalibrationPlayback : CalibrationPlaybackPort {
    override fun start(request: CaptureRequest, onFinished: () -> Unit): CalibrationAudioResult {
        onFinished()
        return CalibrationAudioResult.Success()
    }
}

object NoopCalibrationDsp : CalibrationDspPort {
    override fun stageCandidate(solution: CalibrationSolution): CalibrationAudioResult =
        CalibrationAudioResult.Success(CandidateId("candidate-${solution.id.value}"))

    override fun recordValidation(
        candidateId: CandidateId,
        outcome: ValidationOutcome,
        beforeDb: Float?,
        afterDb: Float?,
        reason: String?,
    ): CalibrationAudioResult = CalibrationAudioResult.Success()

    override fun acceptCandidate(candidateId: CandidateId): CalibrationAudioResult = CalibrationAudioResult.Success()

    override fun rollbackCandidate(candidateId: CandidateId): CalibrationAudioResult = CalibrationAudioResult.Success()

    override fun isLiveDspVerified(): Boolean = true
}
