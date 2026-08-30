package com.darelisme.sweetspot.calibration.model

sealed interface CaptureAttempt {
    val request: CaptureRequest

    data class Accepted(val evidence: AcceptedChannelEvidence) : CaptureAttempt {
        override val request: CaptureRequest get() = evidence.request
    }

    data class Rejected(
        override val request: CaptureRequest,
        val reason: CaptureRejectionReason,
    ) : CaptureAttempt
}

data class PositionChannels(
    val left: AcceptedChannelEvidence? = null,
    val right: AcceptedChannelEvidence? = null,
) {
    fun accept(evidence: AcceptedChannelEvidence): PositionChannels = when (evidence.request.channel) {
        CaptureChannel.LEFT -> {
            require(left == null || left.request.captureId == evidence.request.captureId)
            copy(left = left ?: evidence)
        }
        CaptureChannel.RIGHT -> {
            require(right == null || right.request.captureId == evidence.request.captureId)
            copy(right = right ?: evidence)
        }
        CaptureChannel.BOTH -> error("A composite capture cannot enter a single-channel position ledger")
    }

    fun complete(position: CalibrationPosition): CompletePosition? {
        val acceptedLeft = left ?: return null
        val acceptedRight = right ?: return null
        return CompletePosition(position, acceptedLeft, acceptedRight)
    }
}

@ConsistentCopyVisibility
data class PositionLedger private constructor(
    val attempts: List<CaptureAttempt>,
    private val channelsByPosition: Map<CalibrationPosition, PositionChannels>,
) {
    val rejectedAttempts: List<CaptureAttempt.Rejected>
        get() = attempts.filterIsInstance<CaptureAttempt.Rejected>()

    val completePositions: List<CompletePosition>
        get() = CalibrationPosition.entries.mapNotNull { position ->
            channelsByPosition[position]?.complete(position)
        }

    fun channels(position: CalibrationPosition): PositionChannels =
        channelsByPosition[position] ?: PositionChannels()

    fun complete(position: CalibrationPosition): CompletePosition? =
        channelsByPosition[position]?.complete(position)

    fun recordAccepted(evidence: AcceptedChannelEvidence): PositionLedger {
        val priorAttempt = attempts.firstOrNull { it.request.captureId == evidence.request.captureId }
        if (priorAttempt != null) {
            require(priorAttempt == CaptureAttempt.Accepted(evidence))
            return this
        }
        val position = evidence.request.position
        val updated = channels(position).accept(evidence)
        return copy(
            attempts = attempts + CaptureAttempt.Accepted(evidence),
            channelsByPosition = channelsByPosition + (position to updated),
        )
    }

    fun recordRejected(request: CaptureRequest, reason: CaptureRejectionReason): PositionLedger {
        val rejected = CaptureAttempt.Rejected(request, reason)
        val priorAttempt = attempts.firstOrNull { it.request.captureId == request.captureId }
        if (priorAttempt != null) {
            require(priorAttempt == rejected)
            return this
        }
        return copy(attempts = attempts + rejected)
    }

    fun containsAllMandatoryPositions(): Boolean = MANDATORY_POSITIONS.all { complete(it) != null }

    fun solutionSourcesAreAccepted(solution: CalibrationSolution): Boolean =
        solution.sourcePositions.all { complete(it) != null }

    companion object {
        val MANDATORY_POSITIONS = setOf(
            CalibrationPosition.CENTER,
            CalibrationPosition.LEFT,
            CalibrationPosition.RIGHT,
        )

        fun empty(): PositionLedger = PositionLedger(emptyList(), emptyMap())

        fun fromAttempts(attempts: List<CaptureAttempt>): PositionLedger {
            var ledger = empty()
            attempts.forEach { attempt ->
                ledger = when (attempt) {
                    is CaptureAttempt.Accepted -> ledger.recordAccepted(attempt.evidence)
                    is CaptureAttempt.Rejected -> ledger.recordRejected(attempt.request, attempt.reason)
                }
            }
            return ledger
        }
    }
}
