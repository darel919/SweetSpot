package com.darelisme.sweetspot.calibration.analysis

import com.darelisme.sweetspot.calibration.model.*
import com.darelisme.sweetspot.calibration.playback.MeasurementSweep
import com.darelisme.sweetspot.calibration.playback.MeasurementSweepGenerator
import com.darelisme.sweetspot.calibration.playback.SyncMarkerKind
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

enum class AnalysisChannel {
    LEFT,
    RIGHT,
    BOTH,
}

data class CalibrationCapture(
    val sampleRateHz: Int,
    val samples: FloatArray,
    val channel: AnalysisChannel = AnalysisChannel.BOTH,
) {
    init {
        require(sampleRateHz in 8_000..192_000)
        require(samples.isNotEmpty())
        require(samples.all(Float::isFinite))
    }
}

data class MarkerCandidate(
    val sample: Int,
    val correlation: Float,
)

data class MarkerPairCandidate(
    val leadingSample: Int,
    val trailingSample: Int,
    val leadingCorrelation: Float,
    val trailingCorrelation: Float,
    val observedSeparationSamples: Int,
    val separationPpm: Float,
    val timingAgreement: Float,
    val score: Float,
    val accepted: Boolean,
    val rejection: MarkerFailure?,
)

enum class MarkerFailure {
    MARKER_ABSENT,
    LEADING_MARKER_WEAK,
    TRAILING_MARKER_WEAK,
    MARKER_PAIR_LOW_CONFIDENCE,
    MARKER_PAIR_AMBIGUOUS,
    MARKER_PAIR_BAD_TIMING,
    CLOCK_DRIFT_UNRELIABLE,
}

data class MarkerDetection(
    val accepted: Boolean,
    val startSample: Int?,
    val rightStartSample: Int?,
    val leadingMarkerSample: Int?,
    val trailingMarkerSample: Int?,
    val confidence: Float,
    val leadingCorrelation: Float,
    val trailingCorrelation: Float,
    val driftPpm: Float?,
    val clockRatio: Float?,
    val expectedSeparationSamples: Float,
    val observedSeparationSamples: Int?,
    val failure: MarkerFailure?,
    val leadingCandidates: List<MarkerCandidate>,
    val trailingCandidates: List<MarkerCandidate>,
    val pairCandidates: List<MarkerPairCandidate>,
)

data class DirectArrivalDiagnostics(
    val acceptedSample: Int?,
    val peak: Float,
    val noiseRms: Float,
    val peakToNoiseDb: Float?,
    val supportRms: Float,
    val supportThreshold: Float,
    val rejection: DirectArrivalFailure?,
    val candidateSample: Int? = acceptedSample,
    val acceptanceThreshold: Float = supportThreshold,
    val laterReflectionSample: Int? = null,
    val laterReflectionPeak: Float? = null,
)

enum class DirectArrivalFailure {
    NO_CANDIDATE,
    PEAK_BELOW_NOISE,
    CANDIDATE_NOT_SUSTAINED,
}

data class ResponsePoint(
    val frequencyHz: Float,
    val magnitudeDb: Float,
)

data class MicrophoneCalibrationProfile(
    val frequenciesHz: FloatArray,
    val responseDb: FloatArray,
    val normalizeAtHz: Float = 1_000f,
    val trustMinHz: Float = 30f,
    val trustFullMaxHz: Float = 8_000f,
    val trustTaperToHz: Float = 12_000f,
) {
    init {
        require(frequenciesHz.size == responseDb.size)
        require(frequenciesHz.size >= 2)
        for (index in 0 until frequenciesHz.lastIndex) {
            require(frequenciesHz[index] < frequenciesHz[index + 1])
        }
        require(frequenciesHz.all { it > 0f && it.isFinite() })
        require(responseDb.all(Float::isFinite))
        require(normalizeAtHz > 0f && normalizeAtHz.isFinite())
        require(trustMinHz > 0f && trustMinHz < trustFullMaxHz)
        require(trustFullMaxHz < trustTaperToHz && trustTaperToHz.isFinite())
    }

    fun compensationDbAt(frequencyHz: Float): Float {
        require(frequencyHz > 0f && frequencyHz.isFinite())
        val normalized = interpolate(frequencyHz) - interpolate(normalizeAtHz)
        val weight = when {
            frequencyHz < trustMinHz || frequencyHz >= trustTaperToHz -> 0f
            frequencyHz <= trustFullMaxHz -> 1f
            frequencyHz <= 10_000f -> 1f - 0.5f * (frequencyHz - trustFullMaxHz) / (10_000f - trustFullMaxHz)
            else -> 0.5f * (trustTaperToHz - frequencyHz) / (trustTaperToHz - 10_000f)
        }.coerceIn(0f, 1f)
        val maximumAbsoluteCompensation = when {
            frequencyHz <= trustFullMaxHz -> Float.POSITIVE_INFINITY
            frequencyHz <= 10_000f -> 2f
            frequencyHz < trustTaperToHz -> 1f
            else -> 0f
        }
        val compensation = -normalized * weight
        return if (maximumAbsoluteCompensation.isInfinite()) {
            compensation
        } else {
            compensation.coerceIn(-maximumAbsoluteCompensation, maximumAbsoluteCompensation)
        }
    }

    private fun interpolate(frequencyHz: Float): Float {
        if (frequencyHz <= frequenciesHz.first()) return responseDb.first()
        if (frequencyHz >= frequenciesHz.last()) return responseDb.last()
        val upper = frequenciesHz.indexOfFirst { it >= frequencyHz }
        val lower = upper - 1
        val fraction = (log10(frequencyHz) - log10(frequenciesHz[lower])) /
            (log10(frequenciesHz[upper]) - log10(frequenciesHz[lower]))
        return responseDb[lower] + (responseDb[upper] - responseDb[lower]) * fraction
    }
}

enum class AnalysisStatus {
    OK,
    UNSUPPORTED_SAMPLE_RATE,
    CAPTURE_TOO_SHORT,
    CAPTURE_CLIPPED,
    SIGNAL_TOO_LOW,
    SYNC_MARKER_NOT_FOUND,
    CLOCK_DRIFT_UNRELIABLE,
    DIRECT_ARRIVAL_LOW_CONFIDENCE,
    RESPONSE_NOT_GENERATED,
}

data class CaptureSignalQuality(
    val rms: Float,
    val peak: Float,
    val snrDb: Float?,
    val clippedSamples: Int,
)

data class CalibrationAnalysis(
    val status: AnalysisStatus,
    val marker: MarkerDetection,
    val quality: CaptureSignalQuality,
    val leftResponse: List<ResponsePoint>,
    val rightResponse: List<ResponsePoint>,
    val leftDirectArrival: DirectArrivalDiagnostics?,
    val rightDirectArrival: DirectArrivalDiagnostics?,
)

interface CalibrationAnalyzer {
    val revision: AnalyzerRevision
    fun analyze(
        capture: CalibrationCapture,
        sweep: MeasurementSweep,
        microphoneProfile: MicrophoneCalibrationProfile? = null,
    ): CalibrationAnalysis
}

class AndroidResponseV1Analyzer : CalibrationAnalyzer {
    override val revision: AnalyzerRevision = AnalyzerRevision("android-response-v1")

    override fun analyze(
        capture: CalibrationCapture,
        sweep: MeasurementSweep,
        microphoneProfile: MicrophoneCalibrationProfile?,
    ): CalibrationAnalysis {
        require(sweep.sampleRate > 0)
        val centered = removeDc(capture.samples)
        val marker = MarkerDetector.detect(centered, sweep, capture.sampleRateHz)
        val quality = quality(
            centered,
            sweep,
            capture.sampleRateHz,
            marker.startSample,
            marker.leadingMarkerSample,
        )
        if (quality.clippedSamples > 0) return emptyAnalysis(AnalysisStatus.CAPTURE_CLIPPED, marker, quality)
        if (capture.sampleRateHz < 40_000 || sweep.endHz >= capture.sampleRateHz / 2f) {
            return emptyAnalysis(AnalysisStatus.UNSUPPORTED_SAMPLE_RATE, marker, quality)
        }
        val expectedLength = scaledParts(sweep, capture.sampleRateHz).totalFrames
        if (capture.samples.size < expectedLength) {
            return emptyAnalysis(AnalysisStatus.CAPTURE_TOO_SHORT, marker, quality)
        }
        if (quality.rms < SIGNAL_RMS_FLOOR || (quality.snrDb != null && quality.snrDb < SNR_FLOOR_DB)) {
            return emptyAnalysis(AnalysisStatus.SIGNAL_TOO_LOW, marker, quality)
        }
        if (!marker.accepted) {
            val status = when (marker.failure) {
                MarkerFailure.CLOCK_DRIFT_UNRELIABLE,
                MarkerFailure.MARKER_PAIR_BAD_TIMING -> AnalysisStatus.CLOCK_DRIFT_UNRELIABLE
                else -> AnalysisStatus.SYNC_MARKER_NOT_FOUND
            }
            return emptyAnalysis(status, marker, quality)
        }

        val parts = scaledParts(sweep, capture.sampleRateHz)
        val clockRatio = marker.clockRatio ?: 1f
        val leftResult = if (capture.channel == AnalysisChannel.RIGHT) {
            SweepAnalysis(emptyList(), null)
        } else {
            analyzeSweep(
                centered,
                marker.startSample ?: 0,
                parts.sweepFrames,
                parts.interSweepGapFrames,
                sweep,
                capture.sampleRateHz,
                microphoneProfile,
                clockRatio,
            )
        }
        val rightResult = if (capture.channel == AnalysisChannel.LEFT) {
            SweepAnalysis(emptyList(), null)
        } else {
            analyzeSweep(
                centered,
                marker.rightStartSample ?: 0,
                parts.sweepFrames,
                parts.postRollFrames,
                sweep,
                capture.sampleRateHz,
                microphoneProfile,
                clockRatio,
            )
        }
        val left = leftResult.response
        val right = rightResult.response
        val leftDirect = leftResult.directArrival
        val rightDirect = rightResult.directArrival
        val leftRequested = capture.channel != AnalysisChannel.RIGHT
        val rightRequested = capture.channel != AnalysisChannel.LEFT
        val directFailure = listOfNotNull(
            if (leftRequested) leftDirect?.rejection else null,
            if (rightRequested) rightDirect?.rejection else null,
        ).firstOrNull()
        if (directFailure != null) {
            return CalibrationAnalysis(
                AnalysisStatus.DIRECT_ARRIVAL_LOW_CONFIDENCE,
                marker,
                quality,
                left,
                right,
                leftDirect,
                rightDirect,
            )
        }
        if (left.isEmpty() && right.isEmpty()) {
            return emptyAnalysis(AnalysisStatus.RESPONSE_NOT_GENERATED, marker, quality)
        }
        return CalibrationAnalysis(AnalysisStatus.OK, marker, quality, left, right, leftDirect, rightDirect)
    }

    private data class SweepAnalysis(
        val response: List<ResponsePoint>,
        val directArrival: DirectArrivalDiagnostics?,
    )

    private fun analyzeSweep(
        samples: FloatArray,
        start: Int,
        length: Int,
        postRollFrames: Int,
        sweep: MeasurementSweep,
        sampleRate: Int,
        profile: MicrophoneCalibrationProfile?,
        clockRatio: Float,
    ): SweepAnalysis {
        val targetLength = length + postRollFrames
        val recorded = clockCorrectedSlice(samples, start, targetLength, clockRatio) ?: return SweepAnalysis(emptyList(), null)
        val reference = MeasurementSweepGenerator.generateSweepSignal(sweep, sampleRate)
        val impulse = EssDeconvolver.deconvolve(
            recorded = recorded,
            reference = reference,
            targetLength = targetLength,
            causalLength = postRollFrames + 1,
            clockRatio = 1f,
        )
        val direct = directArrival(impulse)
        if (direct.rejection != null || direct.acceptedSample == null) return SweepAnalysis(emptyList(), direct)
        val points = FrequencyResponse.extractWindowed(impulse, sampleRate, sweep.startHz, sweep.endHz)
        val corrected = points.map { point ->
            point.copy(magnitudeDb = point.magnitudeDb + (profile?.compensationDbAt(point.frequencyHz) ?: 0f))
        }
        return SweepAnalysis(corrected, direct)
    }

    private fun clockCorrectedSlice(
        samples: FloatArray,
        start: Int,
        targetLength: Int,
        clockRatio: Float,
    ): FloatArray? {
        if (start < 0 || targetLength <= 0 || !clockRatio.isFinite() || clockRatio <= 0f) return null
        val sourceLength = (targetLength * clockRatio).roundToInt().coerceAtLeast(1)
        if (start + sourceLength > samples.size) return null
        if (sourceLength == targetLength) return samples.copyOfRange(start, start + targetLength)
        return FloatArray(targetLength) { index ->
            val position = index.toFloat() * (sourceLength - 1) / (targetLength - 1).coerceAtLeast(1)
            val lower = position.toInt().coerceIn(0, sourceLength - 1)
            val upper = (lower + 1).coerceAtMost(sourceLength - 1)
            val fraction = position - lower
            samples[start + lower] * (1f - fraction) + samples[start + upper] * fraction
        }
    }

    private fun quality(
        samples: FloatArray,
        sweep: MeasurementSweep,
        sampleRate: Int,
        startSample: Int?,
        noiseAnchorSample: Int?,
    ): CaptureSignalQuality {
        val parts = scaledParts(sweep, sampleRate)
        val signalStart = startSample?.coerceIn(0, samples.size) ?: 0
        val signalEnd = if (startSample == null) {
            samples.size
        } else {
            min(samples.size, signalStart + parts.sweepFrames + parts.postRollFrames)
        }
        var peak = 0f
        var clipped = 0
        for (index in signalStart until signalEnd) {
            val absolute = abs(samples[index])
            peak = max(peak, absolute)
            if (absolute >= CLIP_THRESHOLD) clipped++
        }
        val signalRms = if (signalEnd > signalStart) rms(samples, signalStart, signalEnd) else 0f
        val noiseEnd = min(samples.size, noiseAnchorSample ?: startSample ?: parts.leadingMarkerStartFrame)
        val noiseStart = max(0, noiseEnd - min(parts.preRollFrames, sampleRate / 4))
        val noise = if (noiseEnd > noiseStart) rms(samples, noiseStart, noiseEnd) else 0f
        val snr = if (noise > 0f && signalRms > 0f) (20f * log10(signalRms / noise)) else null
        return CaptureSignalQuality(signalRms, peak, snr, clipped)
    }

    private fun removeDc(samples: FloatArray): FloatArray {
        if (samples.isEmpty()) return samples
        val mean = samples.average().toFloat()
        if (abs(mean) < 1e-8f) return samples
        return FloatArray(samples.size) { index -> samples[index] - mean }
    }

    private fun emptyAnalysis(
        status: AnalysisStatus,
        marker: MarkerDetection,
        quality: CaptureSignalQuality,
    ): CalibrationAnalysis = CalibrationAnalysis(status, marker, quality, emptyList(), emptyList(), null, null)

    private fun rms(samples: FloatArray, start: Int, end: Int): Float {
        if (end <= start) return 0f
        var total = 0.0
        for (index in start until end) total += samples[index] * samples[index]
        return sqrt(total / (end - start)).toFloat()
    }

    private companion object {
        const val SIGNAL_RMS_FLOOR = 0.0001f
        const val SNR_FLOOR_DB = 8f
        const val CLIP_THRESHOLD = 0.999f

        fun scaledParts(sweep: MeasurementSweep, sampleRate: Int): MeasurementSweep.Parts =
            sweep.copy(sampleRate = sampleRate).parts()
    }
}

object MarkerDetector {
    const val CLOCK_DRIFT_WARNING_PPM = 250f
    const val CLOCK_DRIFT_HARD_REJECT_PPM = 1_000f
    const val MARKER_PAIR_SEARCH_MAX_DRIFT_PPM = 5_000f
    const val MARKER_PAIR_SCORE_THRESHOLD = 0.63f
    const val MAX_SEARCH_CANDIDATES = 64
    const val MAX_EXPORTED_CANDIDATES = 16

    fun detect(samples: FloatArray, sweep: MeasurementSweep, sampleRateHz: Int): MarkerDetection {
        require(sampleRateHz > 0)
        val parts = sweep.copy(sampleRate = sampleRateHz).parts()
        val tvParts = sweep.parts()
        val expectedSeparation = (tvParts.trailingMarkerStartFrame - tvParts.leadingMarkerStartFrame) *
            sampleRateHz.toFloat() / sweep.sampleRate
        val leadingCorrelation = normalizedCorrelation(
            samples,
            MeasurementSweepGenerator.generateSyncMarker(sweep, sampleRateHz, SyncMarkerKind.START),
        )
        val trailingCorrelation = normalizedCorrelation(
            samples,
            MeasurementSweepGenerator.generateSyncMarker(sweep, sampleRateHz, SyncMarkerKind.END),
        )
        val leading = candidates(leadingCorrelation, threshold = 0.22f, minimumDistance = max(1, parts.syncMarkerFrames / 32))
        val trailing = candidates(trailingCorrelation, threshold = 0.22f, minimumDistance = max(1, parts.endMarkerFrames / 32))
        val leadingExport = leading.take(MAX_EXPORTED_CANDIDATES)
        val trailingExport = trailing.take(MAX_EXPORTED_CANDIDATES)
        val pairs = mutableListOf<PairScore>()
        for (left in leading) {
            for (right in trailing) {
                if (right.sample <= left.sample) continue
                val observed = right.sample - left.sample
                val ratio = observed / expectedSeparation
                val drift = (ratio - 1f) * 1_000_000f
                if (!ratio.isFinite() || !drift.isFinite()) continue
                val timing = timingAgreement(abs(drift))
                val balanced = min(left.correlation, right.correlation) * 0.7f +
                    sqrt(max(0f, left.correlation * right.correlation)) * 0.3f
                pairs += PairScore(left, right, observed, ratio, drift, timing, balanced * 0.5f + timing * 0.5f)
            }
        }
        val ordered = pairs.sortedWith(compareByDescending<PairScore> { it.score }.thenBy { abs(it.driftPpm) })
        val selected = ordered.firstOrNull { abs(it.driftPpm) <= CLOCK_DRIFT_HARD_REJECT_PPM }
            ?: ordered.firstOrNull { abs(it.driftPpm) <= MARKER_PAIR_SEARCH_MAX_DRIFT_PPM }
            ?: ordered.firstOrNull()
        val second = selected?.let { candidate ->
            ordered.firstOrNull { other ->
                other !== candidate &&
                    (abs(other.leading.sample - candidate.leading.sample) >= max(4, parts.syncMarkerFrames / 4) ||
                        abs(other.trailing.sample - candidate.trailing.sample) >= max(4, parts.endMarkerFrames / 4))
            }
        }
        val ambiguous = selected != null && second != null &&
            (selected.score - second.score < 0.05f || selected.score / max(0.0001f, second.score) < 1.1f)
        val leadingBest = leading.firstOrNull()?.correlation ?: 0f
        val trailingBest = trailing.firstOrNull()?.correlation ?: 0f
        val minimum = min(leadingBest, trailingBest)
        val failure = when {
            selected == null -> MarkerFailure.MARKER_ABSENT
            ambiguous -> MarkerFailure.MARKER_PAIR_AMBIGUOUS
            minimum < 0.25f -> if (leadingBest < trailingBest) MarkerFailure.LEADING_MARKER_WEAK else MarkerFailure.TRAILING_MARKER_WEAK
            abs(selected.driftPpm) > MARKER_PAIR_SEARCH_MAX_DRIFT_PPM -> MarkerFailure.MARKER_PAIR_BAD_TIMING
            abs(selected.driftPpm) > CLOCK_DRIFT_HARD_REJECT_PPM ->
                if (minimum >= 0.55f) MarkerFailure.CLOCK_DRIFT_UNRELIABLE else MarkerFailure.MARKER_PAIR_LOW_CONFIDENCE
            selected.score < MARKER_PAIR_SCORE_THRESHOLD -> MarkerFailure.MARKER_PAIR_LOW_CONFIDENCE
            else -> null
        }
        val accepted = failure == null
        val driftTrusted = selected != null && minimum >= 0.55f && accepted
        val nominalCapturePerTvFrame = sampleRateHz.toFloat() / sweep.sampleRate
        val start = if (accepted && selected != null) {
            selected.leading.sample + ((tvParts.sweepStartFrame - tvParts.leadingMarkerStartFrame).toFloat() *
                nominalCapturePerTvFrame * selected.clockRatio).roundToInt()
        } else null
        val rightStart = if (accepted && selected != null) {
            selected.leading.sample + ((tvParts.rightSweepStartFrame - tvParts.leadingMarkerStartFrame).toFloat() *
                nominalCapturePerTvFrame * selected.clockRatio).roundToInt()
        } else null
        return MarkerDetection(
            accepted = accepted,
            startSample = start,
            rightStartSample = rightStart,
            leadingMarkerSample = selected?.leading?.sample,
            trailingMarkerSample = selected?.trailing?.sample,
            confidence = selected?.score ?: minimum,
            leadingCorrelation = leadingBest,
            trailingCorrelation = trailingBest,
            driftPpm = selected?.driftPpm?.takeIf { driftTrusted },
            clockRatio = selected?.clockRatio,
            expectedSeparationSamples = expectedSeparation,
            observedSeparationSamples = selected?.observed,
            failure = failure,
            leadingCandidates = leadingExport,
            trailingCandidates = trailingExport,
            pairCandidates = ordered.take(MAX_EXPORTED_CANDIDATES).map { pair ->
                MarkerPairCandidate(
                    pair.leading.sample,
                    pair.trailing.sample,
                    pair.leading.correlation,
                    pair.trailing.correlation,
                    pair.observed,
                    pair.driftPpm,
                    pair.timing,
                    pair.score,
                    accepted && pair === selected,
                    when {
                        pair === selected && ambiguous -> MarkerFailure.MARKER_PAIR_AMBIGUOUS
                        pair === selected -> failure
                        abs(pair.driftPpm) > MARKER_PAIR_SEARCH_MAX_DRIFT_PPM -> MarkerFailure.MARKER_PAIR_BAD_TIMING
                        else -> null
                    },
                )
            },
        )
    }

    private data class PairScore(
        val leading: MarkerCandidate,
        val trailing: MarkerCandidate,
        val observed: Int,
        val clockRatio: Float,
        val driftPpm: Float,
        val timing: Float,
        val score: Float,
    )

    private fun timingAgreement(absDriftPpm: Float): Float = if (absDriftPpm <= CLOCK_DRIFT_WARNING_PPM) {
        1f
    } else {
        ((CLOCK_DRIFT_HARD_REJECT_PPM - absDriftPpm) /
            (CLOCK_DRIFT_HARD_REJECT_PPM - CLOCK_DRIFT_WARNING_PPM)).coerceIn(0f, 1f)
    }

    private fun candidates(
        correlation: FloatArray,
        threshold: Float,
        minimumDistance: Int,
    ): List<MarkerCandidate> {
        val peaks = correlation.indices.filter { index ->
            correlation[index] >= threshold &&
                (index == 0 || correlation[index] >= correlation[index - 1]) &&
                (index == correlation.lastIndex || correlation[index] >= correlation[index + 1])
        }.sortedByDescending { correlation[it] }
        val selected = mutableListOf<MarkerCandidate>()
        for (index in peaks) {
            if (selected.none { abs(it.sample - index) < minimumDistance }) {
                selected += MarkerCandidate(index, correlation[index])
                if (selected.size >= MAX_SEARCH_CANDIDATES) break
            }
        }
        return selected
    }

    private fun normalizedCorrelation(samples: FloatArray, marker: FloatArray): FloatArray {
        if (samples.size < marker.size) return FloatArray(0)
        val centeredMarker = DoubleArray(marker.size)
        val markerMean = marker.average()
        var markerEnergy = 0.0
        marker.forEachIndexed { index, value ->
            centeredMarker[index] = value - markerMean
            markerEnergy += centeredMarker[index] * centeredMarker[index]
        }
        if (markerEnergy <= 1e-12) return FloatArray(0)
        val length = nextPowerOfTwo(samples.size + marker.size - 1)
        val sampleReal = DoubleArray(length)
        val sampleImaginary = DoubleArray(length)
        val markerReal = DoubleArray(length)
        val markerImaginary = DoubleArray(length)
        samples.forEachIndexed { index, value -> sampleReal[index] = value.toDouble() }
        centeredMarker.forEachIndexed { index, value -> markerReal[marker.lastIndex - index] = value }
        CalibrationFft.transform(sampleReal, sampleImaginary)
        CalibrationFft.transform(markerReal, markerImaginary)
        for (index in 0 until length) {
            val real = sampleReal[index] * markerReal[index] - sampleImaginary[index] * markerImaginary[index]
            val imaginary = sampleReal[index] * markerImaginary[index] + sampleImaginary[index] * markerReal[index]
            sampleReal[index] = real
            sampleImaginary[index] = imaginary
        }
        CalibrationFft.transform(sampleReal, sampleImaginary, inverse = true)
        val prefix = DoubleArray(samples.size + 1)
        samples.forEachIndexed { index, value -> prefix[index + 1] = prefix[index] + value * value }
        return FloatArray(samples.size - marker.size + 1) { start ->
            val energy = prefix[start + marker.size] - prefix[start]
            if (energy <= 1e-12) 0f else min(1.0, abs(sampleReal[start + marker.lastIndex]) /
                sqrt(markerEnergy * energy)).toFloat()
        }
    }
}

object CalibrationFft {
    fun transform(real: DoubleArray, imaginary: DoubleArray, inverse: Boolean = false) {
        require(real.isNotEmpty() && real.size == imaginary.size && real.size and (real.size - 1) == 0)
        var reverse = 0
        for (index in 1 until real.size) {
            var bit = real.size shr 1
            while (reverse and bit != 0) {
                reverse = reverse xor bit
                bit = bit shr 1
            }
            reverse = reverse xor bit
            if (index < reverse) {
                real[index] = real[reverse].also { real[reverse] = real[index] }
                imaginary[index] = imaginary[reverse].also { imaginary[reverse] = imaginary[index] }
            }
        }
        var width = 2
        while (width <= real.size) {
            val angle = (if (inverse) 2.0 else -2.0) * Math.PI / width
            val cosine = cos(angle)
            val sine = sin(angle)
            for (start in real.indices step width) {
                var currentCosine = 1.0
                var currentSine = 0.0
                for (offset in 0 until width / 2) {
                    val left = start + offset
                    val right = left + width / 2
                    val rightReal = real[right] * currentCosine - imaginary[right] * currentSine
                    val rightImaginary = real[right] * currentSine + imaginary[right] * currentCosine
                    real[right] = real[left] - rightReal
                    imaginary[right] = imaginary[left] - rightImaginary
                    real[left] += rightReal
                    imaginary[left] += rightImaginary
                    val nextCosine = currentCosine * cosine - currentSine * sine
                    currentSine = currentCosine * sine + currentSine * cosine
                    currentCosine = nextCosine
                }
            }
            width = width shl 1
        }
        if (inverse) real.indices.forEach { index ->
            real[index] /= real.size
            imaginary[index] /= real.size
        }
    }
}

object EssDeconvolver {
    fun deconvolve(recorded: FloatArray, reference: FloatArray): FloatArray {
        return deconvolve(
            recorded = recorded,
            reference = reference,
            targetLength = recorded.size,
            causalLength = recorded.size,
            clockRatio = 1f,
        )
    }

    fun deconvolve(
        recorded: FloatArray,
        reference: FloatArray,
        targetLength: Int,
        causalLength: Int,
        clockRatio: Float = 1f,
    ): FloatArray {
        require(recorded.isNotEmpty() && reference.isNotEmpty())
        require(targetLength >= reference.size)
        require(causalLength in 1..targetLength)
        require(clockRatio.isFinite() && clockRatio > 0f)
        val sourceLength = (targetLength * clockRatio).roundToInt()
        require(sourceLength <= recorded.size) {
            "Recorded capture is shorter than the requested deconvolution window"
        }
        val warped = if (sourceLength == targetLength && sourceLength == recorded.size) {
            recorded
        } else {
            FloatArray(targetLength) { index ->
                val position = index.toFloat() * (sourceLength - 1) / (targetLength - 1).coerceAtLeast(1)
                val lower = position.toInt().coerceIn(0, sourceLength - 1)
                val upper = (lower + 1).coerceAtMost(sourceLength - 1)
                val fraction = position - lower
                recorded[lower] * (1f - fraction) + recorded[upper] * fraction
            }
        }
        val length = nextPowerOfTwo(targetLength + reference.size - 1)
        val recordedReal = DoubleArray(length)
        val recordedImaginary = DoubleArray(length)
        val referenceReal = DoubleArray(length)
        val referenceImaginary = DoubleArray(length)
        warped.forEachIndexed { index, value -> recordedReal[index] = value.toDouble() }
        reference.forEachIndexed { index, value -> referenceReal[index] = value.toDouble() }
        CalibrationFft.transform(recordedReal, recordedImaginary)
        CalibrationFft.transform(referenceReal, referenceImaginary)
        var referencePower = 0.0
        for (index in referenceReal.indices) referencePower = max(
            referencePower,
            referenceReal[index] * referenceReal[index] + referenceImaginary[index] * referenceImaginary[index],
        )
        val regularization = max(referencePower * 1e-7, 1e-12)
        for (index in recordedReal.indices) {
            val power = referenceReal[index] * referenceReal[index] + referenceImaginary[index] * referenceImaginary[index]
            val scale = 1.0 / (power + regularization)
            val real = (recordedReal[index] * referenceReal[index] + recordedImaginary[index] * referenceImaginary[index]) * scale
            val imaginary = (recordedImaginary[index] * referenceReal[index] - recordedReal[index] * referenceImaginary[index]) * scale
            recordedReal[index] = real
            recordedImaginary[index] = imaginary
        }
        CalibrationFft.transform(recordedReal, recordedImaginary, inverse = true)
        return FloatArray(causalLength) { index -> recordedReal[index].toFloat() }
    }
}

object FrequencyResponse {
    fun extractWindowed(
        impulse: FloatArray,
        sampleRateHz: Int,
        startHz: Float,
        endHz: Float,
        points: Int = 48,
    ): List<ResponsePoint> {
        if (impulse.isEmpty() || sampleRateHz <= 0 || points < 1) return emptyList()
        val arrival = directArrival(impulse)
        val peak = arrival.acceptedSample ?: return emptyList()
        val fftLength = nextPowerOfTwo(max(impulse.size, max(256, points * 4)))
        fun spectrum(gateMs: Float, taperMs: Float): Pair<DoubleArray, DoubleArray> {
            val real = DoubleArray(fftLength)
            val imaginary = DoubleArray(fftLength)
            val preSamples = max(1, (sampleRateHz * 0.001f).roundToInt())
            val gateSamples = max(preSamples + 1, (sampleRateHz * gateMs / 1000f).roundToInt())
            val taperSamples = max(1, (sampleRateHz * taperMs / 1000f).roundToInt())
            val end = min(impulse.size, peak + gateSamples + taperSamples)
            for (index in max(0, peak - preSamples) until end) {
                val relative = index - peak
                val weight = when {
                    relative < 0 -> (relative + preSamples).toFloat() / preSamples
                    relative <= gateSamples -> 1f
                    else -> 0.5f * (1f + cos(Math.PI * (relative - gateSamples) / taperSamples).toFloat())
                }.coerceIn(0f, 1f)
                real[index] = (impulse[index] * weight).toDouble()
            }
            CalibrationFft.transform(real, imaginary)
            return real to imaginary
        }
        val long = spectrum(250f, 40f)
        val short = spectrum(80f, 40f)
        val lowHz = max(10f, startHz)
        val highHz = min(endHz, sampleRateHz / 2f - sampleRateHz.toFloat() / fftLength)
        if (!(highHz > lowHz)) return emptyList()
        val raw = (0 until points).map { pointIndex ->
            val progress = if (points == 1) 0f else pointIndex.toFloat() / (points - 1)
            val frequency = lowHz * (highHz / lowHz).pow(progress)
            val centerBin = max(1, (frequency * fftLength / sampleRateHz).roundToInt())
            val radius = max(2, (centerBin * 0.01f).roundToInt())
            var totalDb = 0.0
            var count = 0
            for (bin in max(1, centerBin - radius)..min(fftLength / 2 - 1, centerBin + radius)) {
                val longMagnitude = hypot(long.first[bin], long.second[bin])
                val shortMagnitude = hypot(short.first[bin], short.second[bin])
                val transition = when {
                    frequency <= 200f -> 0f
                    frequency >= 1_000f -> 1f
                    else -> log10(frequency / 200f) / log10(1_000f / 200f)
                }
                val blend = transition * transition * (3f - 2f * transition)
                val longDb = if (longMagnitude > 0.0) 20 * log10(longMagnitude) else -120.0
                val shortDb = if (shortMagnitude > 0.0) 20 * log10(shortMagnitude) else -120.0
                val magnitude = 10.0.pow((longDb * (1 - blend) + shortDb * blend) / 20)
                if (magnitude > 0.0 && magnitude.isFinite()) {
                    totalDb += 20 * log10(magnitude)
                    count++
                }
            }
            ResponsePoint(frequency, if (count == 0) -120f else (totalDb / count).toFloat())
        }
        val reference = raw.filter { it.frequencyHz in 500f..2_000f }.map(ResponsePoint::magnitudeDb)
            .ifEmpty { raw.map(ResponsePoint::magnitudeDb) }
            .sorted()
        val normalization = if (reference.size % 2 == 0) {
            (reference[reference.size / 2 - 1] + reference[reference.size / 2]) / 2f
        } else {
            reference[reference.size / 2]
        }
        return raw.map { point -> point.copy(magnitudeDb = point.magnitudeDb - normalization) }
    }
}

private fun directArrival(impulse: FloatArray): DirectArrivalDiagnostics {
    if (impulse.isEmpty()) {
        return DirectArrivalDiagnostics(null, 0f, 0f, null, 0f, 1e-7f, DirectArrivalFailure.NO_CANDIDATE, null, 1e-7f)
    }
    val noiseStart = if (impulse.size > 8) max(impulse.size * 3 / 4, impulse.size - max(1, impulse.size / 4)) else impulse.size
    val noiseRms = rms(impulse, noiseStart, impulse.size)
    val searchEnd = min(impulse.size, max(1, (impulse.size * 0.08f).roundToInt()))
    var peak = 0f
    var directPeak = 0f
    var directPeakIndex = -1
    impulse.forEach { directValue -> peak = max(peak, abs(directValue)) }
    for (index in 0 until searchEnd) {
        val value = abs(impulse[index])
        if (value > directPeak) {
            directPeak = value
            directPeakIndex = index
        }
    }
    val peakGate = max(noiseRms * 6f, 1e-7f)
    if (directPeakIndex < 0) {
        return DirectArrivalDiagnostics(null, peak, noiseRms, ratioDb(directPeak, noiseRms), 0f, peakGate, DirectArrivalFailure.NO_CANDIDATE, null, peakGate)
    }
    if (directPeak <= peakGate) {
        val radius = max(1, (impulse.size * 0.0001f).roundToInt())
        val support = rms(impulse, max(0, directPeakIndex - radius), min(searchEnd, directPeakIndex + radius + 1))
        return DirectArrivalDiagnostics(null, peak, noiseRms, ratioDb(directPeak, noiseRms), support, peakGate, DirectArrivalFailure.PEAK_BELOW_NOISE, directPeakIndex, peakGate)
    }
    val threshold = max(max(directPeak * 0.03f, noiseRms * 8f), 1e-7f)
    val radius = max(1, (impulse.size * 0.0001f).roundToInt())
    var rejected: DirectArrivalDiagnostics? = null
    for (index in 0..directPeakIndex) {
        val value = abs(impulse[index])
        val left = if (index > 0) abs(impulse[index - 1]) else value
        val right = if (index + 1 < searchEnd) abs(impulse[index + 1]) else value
        if (value < left || value < right || value < threshold) continue
        val start = max(0, index - radius)
        val end = min(searchEnd, index + radius + 1)
        val support = rmsExcluding(impulse, start, end, index)
        val supportThreshold = max(noiseRms * 1.5f, threshold * 0.05f)
        if (support >= supportThreshold || (index == 0 && directPeak >= threshold)) {
            return DirectArrivalDiagnostics(index, peak, noiseRms, ratioDb(directPeak, noiseRms), support, supportThreshold, null, index, threshold)
        }
        if (rejected == null || value > rejected.peak) {
            rejected = DirectArrivalDiagnostics(null, peak, noiseRms, ratioDb(directPeak, noiseRms), support, supportThreshold, DirectArrivalFailure.CANDIDATE_NOT_SUSTAINED, index, threshold)
        }
    }
    return rejected ?: DirectArrivalDiagnostics(null, peak, noiseRms, ratioDb(directPeak, noiseRms), 0f, threshold, DirectArrivalFailure.CANDIDATE_NOT_SUSTAINED, directPeakIndex, threshold)
}

private fun rmsExcluding(values: FloatArray, start: Int, end: Int, excluded: Int): Float {
    if (end <= start) return 0f
    var sum = 0.0
    var count = 0
    for (index in start until end) {
        if (index == excluded) continue
        sum += values[index] * values[index]
        count++
    }
    return if (count == 0) 0f else sqrt(sum / count).toFloat()
}

private fun rms(values: FloatArray, start: Int, end: Int): Float {
    if (end <= start) return 0f
    var sum = 0.0
    for (index in start until end) sum += values[index] * values[index]
    return sqrt(sum / (end - start)).toFloat()
}

private fun ratioDb(numerator: Float, denominator: Float): Float? =
    if (numerator > 0f && denominator > 0f) 20 * log10(numerator / denominator) else null

private fun nextPowerOfTwo(value: Int): Int {
    require(value > 0)
    var result = 1
    while (result < value) result = result shl 1
    return result
}
