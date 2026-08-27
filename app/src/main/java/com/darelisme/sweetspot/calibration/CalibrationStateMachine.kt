package com.darelisme.sweetspot.calibration

sealed interface CalibrationEvent {
    data class ChannelAccepted(val evidence: AcceptedChannelEvidence) : CalibrationEvent
    data class CaptureRejected(
        val request: CaptureRequest,
        val reason: CaptureRejectionReason,
    ) : CalibrationEvent
    data class CandidateStaged(val candidate: CalibrationCandidateState) : CalibrationEvent
    data class ValidationClassified(val outcome: ValidationOutcome) : CalibrationEvent
    data object BrowserDisconnected : CalibrationEvent
}

sealed interface BrowserCalibrationCommand {
    data class CancelCapture(val captureId: CaptureId) : BrowserCalibrationCommand
    data object CancelOptionalRefinement : BrowserCalibrationCommand
    data object FinishWithBest : BrowserCalibrationCommand
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
        CalibrationEvent.BrowserDisconnected -> CalibrationTransition(job, emptyList())
    }

    fun handleBrowserCommand(job: CalibrationJob, command: BrowserCalibrationCommand): CalibrationTransition =
        when (command) {
            is BrowserCalibrationCommand.CancelCapture -> {
                val activeCapture = (job.nextAction as? CalibrationAction.Capture)?.request?.captureId
                if (activeCapture != command.captureId) CalibrationTransition(job, emptyList())
                else persistAndPublish(job.copy(nextAction = CalibrationPlanner.nextAction(job)))
            }
            BrowserCalibrationCommand.CancelOptionalRefinement,
            BrowserCalibrationCommand.FinishWithBest -> finishWithBest(job)
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
            is OptimizationResult.Valid -> optimized.solution.confidence
            is OptimizationResult.Insufficient -> optimized.confidence
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
            lastError = CalibrationJobError(reason.name.lowercase(), "Capture was rejected"),
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
        val history = job.validationHistory + record
        return when (outcome) {
            ValidationOutcome.IMPROVED,
            ValidationOutcome.NEUTRAL -> {
                val solution = (job.usability as CalibrationUsability.Usable).best
                persistAndPublish(
                    job.copy(
                        revision = job.revision + 1,
                        phase = CalibrationPhase.Complete,
                        validationHistory = history,
                        nextAction = CalibrationAction.Complete(solution.id),
                        pendingEffect = null,
                    ),
                )
            }
            ValidationOutcome.INCONCLUSIVE_CAPTURE -> {
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

    private fun finishWithBest(job: CalibrationJob): CalibrationTransition {
        val usable = job.usability as? CalibrationUsability.Usable
            ?: return CalibrationTransition(job, emptyList())
        val pending = PendingCalibrationEffect.StageCandidate(usable.best.id)
        val updated = job.copy(
            revision = job.revision + 1,
            phase = CalibrationPhase.CandidatePending,
            nextAction = CalibrationAction.Wait("Staging the best calibration."),
            pendingEffect = pending,
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
        if (mandatoryExhausted(job)) return CalibrationPhase.Failed("Three complete mandatory positions were not obtained")
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
            job.ledger.complete(position) == null && CaptureChannel.entries.any { channel ->
                attemptCount(job, position, channel) < MAX_ATTEMPTS_PER_CHANNEL
            }
        }
    }

    private fun mandatoryExhausted(job: CalibrationJob): Boolean =
        PositionLedger.MANDATORY_POSITIONS.any { position ->
            job.ledger.complete(position) == null && CaptureChannel.entries.any { channel ->
                job.ledger.channels(position).accepted(channel) == null &&
                    attemptCount(job, position, channel) >= MAX_ATTEMPTS_PER_CHANNEL
            }
        }

    private fun nextForPositions(
        job: CalibrationJob,
        positions: List<CalibrationPosition>,
    ): CalibrationAction.Capture? {
        positions.forEach { position ->
            if (job.ledger.complete(position) != null) return@forEach
            CaptureChannel.entries.forEach { channel ->
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
}
