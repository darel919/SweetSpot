package com.darelisme.sweetspot.calibration.model

import kotlin.math.pow
import kotlin.math.sqrt

@JvmInline
value class CalibrationJobId(val value: String) {
    init { require(value.isNotBlank()) }
}

@JvmInline
value class CaptureId(val value: String) {
    init { require(value.isNotBlank()) }
}

@JvmInline
value class SolutionId(val value: String) {
    init { require(value.isNotBlank()) }
}

@JvmInline
value class CandidateId(val value: String) {
    init { require(value.isNotBlank()) }
}

@JvmInline
value class AnalyzerRevision(val value: String) {
    init { require(value.isNotBlank()) }
}

@JvmInline
value class SweepRevision(val value: String) {
    init { require(value.isNotBlank()) }
}

object CalibrationBandGrid {
    const val BAND_COUNT = 64
    const val MIN_FREQUENCY_HZ = 20f
    const val MAX_FREQUENCY_HZ = 20_000f

    val upperFrequenciesHz: FloatArray = FloatArray(BAND_COUNT) { index ->
        (MIN_FREQUENCY_HZ *
            (MAX_FREQUENCY_HZ / MIN_FREQUENCY_HZ).toDouble()
                .pow((index + 1).toDouble() / BAND_COUNT)).toFloat()
    }

    val centerFrequenciesHz: FloatArray = FloatArray(BAND_COUNT) { index ->
        val lower = if (index == 0) MIN_FREQUENCY_HZ else upperFrequenciesHz[index - 1]
        sqrt(lower * upperFrequenciesHz[index])
    }
}

class BandCurve private constructor(private val values: FloatArray) {
    val size: Int get() = values.size

    operator fun get(index: Int): Float = values[index]

    fun toFloatArray(): FloatArray = values.copyOf()

    override fun equals(other: Any?): Boolean =
        other is BandCurve && values.contentEquals(other.values)

    override fun hashCode(): Int = values.contentHashCode()

    override fun toString(): String = "BandCurve(size=${values.size})"

    companion object {
        fun of(values: FloatArray): BandCurve {
            require(values.size == CalibrationBandGrid.BAND_COUNT)
            require(values.all(Float::isFinite))
            return BandCurve(values.copyOf())
        }
    }
}

enum class CalibrationPosition(val optional: Boolean) {
    CENTER(false),
    LEFT(false),
    RIGHT(false),
    FORWARD(true),
    BACKWARD(true),
}

enum class CalibrationJobMode {
    AUTO,
    ADVANCED,
}

enum class CaptureChannel {
    LEFT,
    RIGHT,
    BOTH,
}

data class CaptureRequest(
    val captureId: CaptureId,
    val position: CalibrationPosition,
    val channel: CaptureChannel,
    val attemptIndex: Int,
    val optional: Boolean,
) {
    init {
        require(attemptIndex >= 0)
        require(optional == position.optional)
    }
}

data class CaptureQuality(
    val snrDb: Float,
    val markerConfidence: Float,
    val directArrivalConfidence: Float,
) {
    init {
        require(snrDb.isFinite())
        require(markerConfidence in 0f..1f)
        require(directArrivalConfidence in 0f..1f)
    }
}

data class AcceptedChannelEvidence(
    val request: CaptureRequest,
    val responseDb: BandCurve,
    val quality: CaptureQuality,
    val microphoneProfileId: String = "unknown",
    val microphoneProfileRevision: String = "unknown",
) {
    init {
        require(microphoneProfileId.isNotBlank() && microphoneProfileId.length <= 128)
        require(microphoneProfileRevision.isNotBlank() && microphoneProfileRevision.length <= 128)
    }
}

data class CompletePosition(
    val position: CalibrationPosition,
    val left: AcceptedChannelEvidence,
    val right: AcceptedChannelEvidence,
) {
    init {
        require(left.request.position == position)
        require(right.request.position == position)
        require(left.request.channel == CaptureChannel.LEFT)
        require(right.request.channel == CaptureChannel.RIGHT)
    }
}

enum class CaptureRejectionReason {
    CLIPPING,
    MARKER_UNRELIABLE,
    BAD_TIMING,
    CLOCK_DRIFT_UNTRUSTED,
    SIGNAL_TOO_LOW,
    BACKGROUND_NOISE_HIGH,
    DIRECT_ARRIVAL_WEAK,
    CAPTURE_TOO_SHORT,
    INVALID_PCM,
    UNSUPPORTED_SAMPLE_RATE,
    MICROPHONE_PROFILE_UNAVAILABLE,
    PLAYBACK_FAILED,
}

enum class CorrectionMode {
    NORMAL,
    GENTLE,
    RESTRICTED_BAND,
}

enum class UsabilityGrade {
    BOUNDED_USABLE,
    SUFFICIENT,
}

data class BandConfidence(
    val frequencyHz: Float,
    val confidence: Float,
    val spatialSpreadDb: Float,
    val usable: Boolean,
) {
    init {
        require(frequencyHz > 0f && frequencyHz.isFinite())
        require(confidence in 0f..1f)
        require(spatialSpreadDb >= 0f && spatialSpreadDb.isFinite())
    }
}

data class CalibrationConfidence(
    val bands: List<BandConfidence>,
    val usableBandCount: Int,
    val score: Float,
    val grade: UsabilityGrade?,
) {
    init {
        require(bands.size == CalibrationBandGrid.BAND_COUNT)
        require(usableBandCount == bands.count(BandConfidence::usable))
        require(score in 0f..1f)
    }
}

class CalibrationSolution private constructor(
    val id: SolutionId,
    val sourcePositions: Set<CalibrationPosition>,
    val correctionDb: BandCurve,
    val confidence: CalibrationConfidence,
    val score: Float,
    val correctionMode: CorrectionMode,
) {
    init {
        require(sourcePositions.isNotEmpty())
        require(score.isFinite())
    }

    override fun equals(other: Any?): Boolean =
        other is CalibrationSolution &&
            id == other.id &&
            sourcePositions == other.sourcePositions &&
            correctionDb == other.correctionDb &&
            confidence == other.confidence &&
            score == other.score &&
            correctionMode == other.correctionMode

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + sourcePositions.hashCode()
        result = 31 * result + correctionDb.hashCode()
        result = 31 * result + confidence.hashCode()
        result = 31 * result + score.toBits()
        result = 31 * result + correctionMode.hashCode()
        return result
    }

    companion object {
        fun fromCompletePositions(
            id: SolutionId,
            positions: List<CompletePosition>,
            correctionDb: BandCurve,
            confidence: CalibrationConfidence,
            score: Float,
            correctionMode: CorrectionMode,
        ): CalibrationSolution {
            require(positions.isNotEmpty())
            require(positions.map(CompletePosition::position).distinct().size == positions.size)
            return CalibrationSolution(
                id = id,
                sourcePositions = positions.mapTo(linkedSetOf(), CompletePosition::position),
                correctionDb = correctionDb,
                confidence = confidence,
                score = score,
                correctionMode = correctionMode,
            )
        }
    }
}

sealed interface CalibrationUsability {
    data object NotYetUsable : CalibrationUsability

    data class Usable(
        val best: CalibrationSolution,
        val grade: UsabilityGrade,
    ) : CalibrationUsability
}

sealed interface CalibrationPhase {
    data object CenterPreflight : CalibrationPhase
    data object MeasuringRequired : CalibrationPhase
    data object Usable : CalibrationPhase
    data object Refining : CalibrationPhase
    data object CandidatePending : CalibrationPhase
    data object Validating : CalibrationPhase
    data object Reoptimizing : CalibrationPhase
    data object Restoring : CalibrationPhase
    data object Complete : CalibrationPhase
    data class Failed(val reason: String) : CalibrationPhase
    data object Cancelled : CalibrationPhase
}

sealed interface CalibrationAction {
    data class Capture(val request: CaptureRequest, val instruction: String) : CalibrationAction
    data class Validate(
        val captureId: CaptureId,
        val position: CalibrationPosition,
        val candidateId: CandidateId,
        val attemptIndex: Int,
        val instruction: String,
    ) : CalibrationAction
    data class Wait(val message: String) : CalibrationAction
    data class Complete(val solutionId: SolutionId) : CalibrationAction
}

sealed interface PendingCalibrationEffect {
    data class StageCandidate(val solutionId: SolutionId) : PendingCalibrationEffect
    data class AcceptCandidate(val candidateId: CandidateId) : PendingCalibrationEffect
    data class RollbackThenReoptimize(
        val candidateId: CandidateId,
        val nextMode: CorrectionMode?,
    ) : PendingCalibrationEffect
    data class RestorePrevious(val candidateId: CandidateId) : PendingCalibrationEffect
}

data class CalibrationCandidateState(
    val id: CandidateId,
    val solutionId: SolutionId,
    val mode: CorrectionMode,
    val validationAttemptIndex: Int,
)

enum class ValidationOutcome {
    IMPROVED,
    NEUTRAL,
    WORSE,
    INCONCLUSIVE_CAPTURE,
    DSP_ERROR,
}

data class ValidationRecord(
    val candidateId: CandidateId,
    val outcome: ValidationOutcome,
    val attemptIndex: Int,
)

data class CalibrationJobError(val code: String, val message: String)

data class CalibrationJob(
    val id: CalibrationJobId,
    val createdAtMs: Long,
    val revision: Long,
    val analyzerRevision: AnalyzerRevision,
    val sweepRevision: SweepRevision,
    val mode: CalibrationJobMode = CalibrationJobMode.AUTO,
    val phase: CalibrationPhase,
    val ledger: PositionLedger,
    val usability: CalibrationUsability,
    val confidence: CalibrationConfidence?,
    val nextAction: CalibrationAction?,
    val candidate: CalibrationCandidateState?,
    val validationHistory: List<ValidationRecord>,
    val pendingEffect: PendingCalibrationEffect?,
    val lastError: CalibrationJobError?,
) {
    val minimumViableCalibration: Boolean
        get() = usability is CalibrationUsability.Usable

    val bestSolution: CalibrationSolution?
        get() = (usability as? CalibrationUsability.Usable)?.best

    companion object {
        fun new(
            id: CalibrationJobId,
            createdAtMs: Long,
            analyzerRevision: AnalyzerRevision,
            sweepRevision: SweepRevision,
            mode: CalibrationJobMode = CalibrationJobMode.AUTO,
        ): CalibrationJob = CalibrationJob(
            id = id,
            createdAtMs = createdAtMs,
            revision = 0,
            analyzerRevision = analyzerRevision,
            sweepRevision = sweepRevision,
            mode = mode,
            phase = CalibrationPhase.CenterPreflight,
            ledger = PositionLedger.empty(),
            usability = CalibrationUsability.NotYetUsable,
            confidence = null,
            nextAction = CalibrationPlanner.firstAction(),
            candidate = null,
            validationHistory = emptyList(),
            pendingEffect = null,
            lastError = null,
        )
    }
}
