package com.darelisme.sweetspot.calibration

sealed interface CalibrationEvent {
    data class ChannelAccepted(val evidence: AcceptedChannelEvidence) : CalibrationEvent
    data class CaptureRejected(
        val request: CaptureRequest,
        val reason: CaptureRejectionReason,
    ) : CalibrationEvent
    data class CandidateStaged(val candidate: CalibrationCandidateState) : CalibrationEvent
    data class ValidationClassified(val outcome: ValidationOutcome) : CalibrationEvent
}

sealed interface CalibrationEffect {
    data class Persist(val job: CalibrationJob) : CalibrationEffect
    data class Publish(val action: CalibrationAction?) : CalibrationEffect
    data class Execute(val effect: PendingCalibrationEffect) : CalibrationEffect
}

data class CalibrationTransition(
    val job: CalibrationJob,
    val effects: List<CalibrationEffect>,
)

class CalibrationStateMachine(
    private val spatialCorrection: SpatialCorrection = SpatialCorrection(),
) {
    fun reduce(job: CalibrationJob, event: CalibrationEvent): CalibrationTransition = when (event) {
        is CalibrationEvent.ChannelAccepted -> acceptChannel(job, event.evidence)
        is CalibrationEvent.CaptureRejected -> rejectCapture(job, event.request, event.reason)
        is CalibrationEvent.CandidateStaged -> stageCandidate(job, event.candidate)
        is CalibrationEvent.ValidationClassified -> classifyValidation(job, event.outcome)
    }

    private fun acceptChannel(job: CalibrationJob, evidence: AcceptedChannelEvidence): CalibrationTransition {
        val ledger = job.ledger.recordAccepted(evidence)
        val optimized = if (ledger.containsAllMandatoryPositions()) {
            spatialCorrection.optimize(ledger.completePositions)
        } else {
            null
        }
        val updatedUsability = selectUsability(job.usability, ledger, optimized)
        val confidence = when (optimized) {
            is OptimizationResult.Valid -> if (
                (updatedUsability as? CalibrationUsability.Usable)?.best?.id == optimized.solution.id
            ) {
                optimized.solution.confidence
            } else {
                job.confidence
            }
            is OptimizationResult.Insufficient -> job.confidence ?: optimized.confidence
            null -> job.confidence
        }
        val provisional = job.copy(
            revision = job.revision + 1,
            ledger = ledger,
            usability = updatedUsability,
            confidence = confidence,
            pendingEffect = null,
            lastError = null,
        )
        val phase = CalibrationPlanner.phase(provisional)
        val next = CalibrationPlanner.nextAction(provisional.copy(phase = phase))
        return persistAndPublish(provisional.copy(phase = phase, nextAction = next))
    }

    private fun rejectCapture(
        job: CalibrationJob,
        request: CaptureRequest,
        reason: CaptureRejectionReason,
    ): CalibrationTransition {
        val ledger = job.ledger.recordRejected(request, reason)
        val provisional = job.copy(
            revision = job.revision + 1,
            ledger = ledger,
            lastError = CalibrationJobError(reason.name.lowercase(), reason.userMessage()),
        )
        val phase = CalibrationPlanner.phase(provisional)
        val next = CalibrationPlanner.nextAction(provisional.copy(phase = phase))
        return persistAndPublish(provisional.copy(phase = phase, nextAction = next))
    }

    private fun stageCandidate(
        job: CalibrationJob,
        candidate: CalibrationCandidateState,
    ): CalibrationTransition {
        val usable = job.usability as? CalibrationUsability.Usable
            ?: throw IllegalStateException("A candidate requires a usable calibration")
        require(candidate.solutionId == usable.best.id)
        require(job.ledger.solutionSourcesAreAccepted(usable.best))
        val action = CalibrationAction.Validate(
            captureId = CaptureId("validation-${candidate.id.value}-${candidate.validationAttemptIndex}"),
            position = CalibrationPosition.CENTER,
            candidateId = candidate.id,
            attemptIndex = candidate.validationAttemptIndex,
            instruction = "Return the phone to the center position for validation.",
        )
        return persistAndPublish(
            job.copy(
                revision = job.revision + 1,
                phase = CalibrationPhase.Validating,
                candidate = candidate,
                nextAction = action,
                pendingEffect = null,
            ),
        )
    }

    private fun classifyValidation(
        job: CalibrationJob,
        outcome: ValidationOutcome,
    ): CalibrationTransition {
        val candidate = requireNotNull(job.candidate)
        val record = ValidationRecord(candidate.id, outcome, candidate.validationAttemptIndex)
        val history = if (job.validationHistory.lastOrNull() == record) {
            job.validationHistory
        } else {
            job.validationHistory + record
        }
        return when (outcome) {
            ValidationOutcome.IMPROVED,
            ValidationOutcome.NEUTRAL -> {
                val solution = (job.usability as CalibrationUsability.Usable).best
                persistAndPublish(
                    job.copy(
                        revision = job.revision + 1,
                        phase = CalibrationPhase.Complete,
                        validationHistory = history,
                        candidate = null,
                        nextAction = CalibrationAction.Complete(solution.id),
                        pendingEffect = null,
                    ),
                )
            }
            ValidationOutcome.INCONCLUSIVE_CAPTURE -> {
                if (candidate.validationAttemptIndex + 1 >= MAX_VALIDATION_ATTEMPTS) {
                    return rollback(job, candidate, history, dspError = true)
                }
                val retry = candidate.copy(validationAttemptIndex = candidate.validationAttemptIndex + 1)
                val action = CalibrationAction.Validate(
                    captureId = CaptureId("validation-${candidate.id.value}-${retry.validationAttemptIndex}"),
                    position = CalibrationPosition.CENTER,
                    candidateId = candidate.id,
                    attemptIndex = retry.validationAttemptIndex,
                    instruction = "Repeat the center validation capture.",
                )
                persistAndPublish(
                    job.copy(
                        revision = job.revision + 1,
                        validationHistory = history,
                        candidate = retry,
                        nextAction = action,
                    ),
                )
            }
            ValidationOutcome.WORSE -> rollback(job, candidate, history, dspError = false)
            ValidationOutcome.DSP_ERROR -> rollback(job, candidate, history, dspError = true)
        }
    }

    private fun rollback(
        job: CalibrationJob,
        candidate: CalibrationCandidateState,
        history: List<ValidationRecord>,
        dspError: Boolean,
    ): CalibrationTransition {
        val nextMode = if (dspError) null else CalibrationValidationFallbackPolicy.nextModeAfterWorse(candidate.mode)
        val pending = if (dspError || nextMode == null) {
            PendingCalibrationEffect.RestorePrevious(candidate.id)
        } else {
            PendingCalibrationEffect.RollbackThenReoptimize(candidate.id, nextMode)
        }
        val updated = job.copy(
            revision = job.revision + 1,
            phase = CalibrationPhase.Restoring,
            validationHistory = history,
            nextAction = CalibrationAction.Wait("Restoring the previous calibration."),
            pendingEffect = pending,
            lastError = if (dspError) CalibrationJobError("dsp_error", "DSP validation failed") else null,
        )
        return CalibrationTransition(
            updated,
            listOf(
                CalibrationEffect.Persist(updated),
                CalibrationEffect.Execute(pending),
                CalibrationEffect.Publish(updated.nextAction),
            ),
        )
    }

    private fun selectUsability(
        current: CalibrationUsability,
        ledger: PositionLedger,
        optimized: OptimizationResult?,
    ): CalibrationUsability = when (optimized) {
        is OptimizationResult.Valid -> {
            require(ledger.solutionSourcesAreAccepted(optimized.solution))
            when (current) {
                CalibrationUsability.NotYetUsable -> CalibrationUsability.Usable(
                    optimized.solution,
                    requireNotNull(optimized.solution.confidence.grade),
                )
                is CalibrationUsability.Usable -> if (
                    CalibrationSolutionComparator.prefers(optimized.solution, current.best)
                ) {
                    CalibrationUsability.Usable(
                        optimized.solution,
                        requireNotNull(optimized.solution.confidence.grade),
                    )
                } else {
                    current
                }
            }
        }
        is OptimizationResult.Insufficient,
        null -> current
    }

    private fun persistAndPublish(job: CalibrationJob): CalibrationTransition = CalibrationTransition(
        job,
        listOf(CalibrationEffect.Persist(job), CalibrationEffect.Publish(job.nextAction)),
    )

    private companion object {
        const val MAX_VALIDATION_ATTEMPTS = 2
    }
}

private fun CaptureRejectionReason.userMessage(): String = when (this) {
    CaptureRejectionReason.CLIPPING -> "The recording clipped. Lower the TV volume and try this position again."
    CaptureRejectionReason.MARKER_UNRELIABLE -> "The TV sweep marker was unclear. Keep the phone still and reduce background noise."
    CaptureRejectionReason.BAD_TIMING -> "The sweep timing was unstable. Keep the phone still and try this position again."
    CaptureRejectionReason.CLOCK_DRIFT_UNTRUSTED -> "The phone clock drifted too far during the sweep. Keep the capture uninterrupted and try again."
    CaptureRejectionReason.SIGNAL_TOO_LOW -> "The sweep was too quiet. Move the phone closer or raise the TV volume slightly."
    CaptureRejectionReason.BACKGROUND_NOISE_HIGH -> "Background noise was too high. Pause playback and try again in a quieter room."
    CaptureRejectionReason.DIRECT_ARRIVAL_WEAK -> "The direct speaker arrival was too weak. Point the phone toward the TV and try again."
    CaptureRejectionReason.CAPTURE_TOO_SHORT -> "The phone recording ended too early. Keep the browser open until the sweep finishes."
    CaptureRejectionReason.INVALID_PCM -> "The phone sent an invalid recording. Keep the browser open and try this position again."
    CaptureRejectionReason.UNSUPPORTED_SAMPLE_RATE -> "The phone microphone sample rate is unsupported for this calibration."
    CaptureRejectionReason.MICROPHONE_PROFILE_UNAVAILABLE -> "The phone did not provide a validated microphone profile. Choose a supported profile and try again."
    CaptureRejectionReason.PLAYBACK_FAILED -> "The TV could not play this sweep. Stop other audio operations and try again."
}

object CalibrationPlanner {
    private const val MAX_ATTEMPTS_PER_CHANNEL = 2

    fun firstAction(): CalibrationAction = capture(CalibrationPosition.CENTER, CaptureChannel.LEFT, 0)

    fun phase(job: CalibrationJob): CalibrationPhase {
        if (job.phase is CalibrationPhase.Complete || job.phase is CalibrationPhase.Restoring) return job.phase
        if (job.usability is CalibrationUsability.Usable) {
            return if (optionalMeasurementNeeded(job)) CalibrationPhase.Refining else CalibrationPhase.Usable
        }
        if (job.ledger.containsAllMandatoryPositions()) {
            return CalibrationPhase.Failed("Mandatory positions did not produce a trustworthy correction")
        }
        if (mandatoryExhausted(job)) return CalibrationPhase.Failed(mandatoryFailureMessage(job))
        return if (job.ledger.complete(CalibrationPosition.CENTER) == null) {
            CalibrationPhase.CenterPreflight
        } else {
            CalibrationPhase.MeasuringRequired
        }
    }

    fun nextAction(job: CalibrationJob): CalibrationAction? {
        if (job.phase is CalibrationPhase.Failed || job.phase is CalibrationPhase.Complete) return null
        nextForPositions(job, PositionLedger.MANDATORY_POSITIONS.toList().sortedBy { it.ordinal })?.let { return it }
        val usable = job.usability as? CalibrationUsability.Usable ?: return null
        if (usable.grade == UsabilityGrade.SUFFICIENT) {
            return CalibrationAction.Wait("The calibration is ready to stage.")
        }
        nextForPositions(job, listOf(CalibrationPosition.FORWARD, CalibrationPosition.BACKWARD))?.let { return it }
        return CalibrationAction.Wait("The best calibration is ready to stage.")
    }

    private fun optionalMeasurementNeeded(job: CalibrationJob): Boolean {
        val usable = job.usability as CalibrationUsability.Usable
        if (usable.grade == UsabilityGrade.SUFFICIENT) return false
        return listOf(CalibrationPosition.FORWARD, CalibrationPosition.BACKWARD).any { position ->
            job.ledger.complete(position) == null && ROOM_CHANNELS.any { channel ->
                attemptCount(job, position, channel) < MAX_ATTEMPTS_PER_CHANNEL
            }
        }
    }

    private fun mandatoryExhausted(job: CalibrationJob): Boolean =
        PositionLedger.MANDATORY_POSITIONS.any { position ->
            job.ledger.complete(position) == null && ROOM_CHANNELS.all { channel ->
                job.ledger.channels(position).accepted(channel) != null ||
                    attemptCount(job, position, channel) >= MAX_ATTEMPTS_PER_CHANNEL
            }
        }

    private fun mandatoryFailureMessage(job: CalibrationJob): String {
        val exhausted = PositionLedger.MANDATORY_POSITIONS.firstOrNull { position ->
            job.ledger.complete(position) == null && ROOM_CHANNELS.all { channel ->
                job.ledger.channels(position).accepted(channel) != null ||
                    attemptCount(job, position, channel) >= MAX_ATTEMPTS_PER_CHANNEL
            }
        }
        return when (exhausted) {
            CalibrationPosition.CENTER -> "Center setup could not become trustworthy after the retry budget. Check volume, noise, and phone placement before trying again."
            CalibrationPosition.LEFT -> "The left listening position could not become trustworthy after the retry budget. Keep the phone still and try again."
            CalibrationPosition.RIGHT -> "The right listening position could not become trustworthy after the retry budget. Keep the phone still and try again."
            null -> "Three complete mandatory positions were not obtained"
            else -> "A required listening position could not become trustworthy after the retry budget"
        }
    }

    private fun nextForPositions(
        job: CalibrationJob,
        positions: List<CalibrationPosition>,
    ): CalibrationAction.Capture? {
        positions.forEach { position ->
            if (job.ledger.complete(position) != null) return@forEach
            ROOM_CHANNELS.forEach { channel ->
                if (job.ledger.channels(position).accepted(channel) != null) return@forEach
                val attempts = attemptCount(job, position, channel)
                if (attempts < MAX_ATTEMPTS_PER_CHANNEL) return capture(position, channel, attempts)
            }
        }
        return null
    }

    private fun attemptCount(
        job: CalibrationJob,
        position: CalibrationPosition,
        channel: CaptureChannel,
    ): Int = job.ledger.attempts.count {
        it.request.position == position && it.request.channel == channel
    }

    private fun PositionChannels.accepted(channel: CaptureChannel): AcceptedChannelEvidence? = when (channel) {
        CaptureChannel.LEFT -> left
        CaptureChannel.RIGHT -> right
        CaptureChannel.BOTH -> null
    }

    private fun capture(
        position: CalibrationPosition,
        channel: CaptureChannel,
        attemptIndex: Int,
    ): CalibrationAction.Capture = CalibrationAction.Capture(
        request = CaptureRequest(
            captureId = CaptureId("${position.name.lowercase()}-${channel.name.lowercase()}-$attemptIndex"),
            position = position,
            channel = channel,
            attemptIndex = attemptIndex,
            optional = position.optional,
        ),
        instruction = "Measure ${position.name.lowercase()} ${channel.name.lowercase()}.",
    )

    private val ROOM_CHANNELS = listOf(CaptureChannel.LEFT, CaptureChannel.RIGHT)
}
