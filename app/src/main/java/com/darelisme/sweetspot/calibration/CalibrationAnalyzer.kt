package com.darelisme.sweetspot.calibration

import com.darelisme.sweetspot.MeasurementSweep
import com.darelisme.sweetspot.MeasurementSweepGenerator
import com.darelisme.sweetspot.SyncMarkerKind
import kotlin.math.abs
import kotlin.math.cos
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
        require(sampleRateHz in 8_000..96_000)
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
    }

    fun compensationDbAt(frequencyHz: Float): Float {
        require(frequencyHz > 0f && frequencyHz.isFinite())
        val normalized = interpolate(frequencyHz) - interpolate(normalizeAtHz)
        return -normalized
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
        val quality = quality(capture.samples, sweep, capture.sampleRateHz)
        val marker = MarkerDetector.detect(capture.samples, sweep, capture.sampleRateHz)
        if (quality.clippedSamples > 0) return emptyAnalysis(AnalysisStatus.CAPTURE_CLIPPED, marker, quality)
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
        val left = if (capture.channel == AnalysisChannel.RIGHT) {
            emptyList()
        } else {
            analyzeSweep(capture.samples, marker.startSample ?: 0, parts.sweepFrames, sweep, capture.sampleRateHz, microphoneProfile)
        }
        val right = if (capture.channel == AnalysisChannel.LEFT) {
            emptyList()
        } else {
            analyzeSweep(capture.samples, marker.rightStartSample ?: 0, parts.sweepFrames, sweep, capture.sampleRateHz, microphoneProfile)
        }
        val leftImpulse = if (left.isNotEmpty()) deconvolveChannel(capture.samples, marker.startSample ?: 0, parts.sweepFrames, sweep, capture.sampleRateHz) else null
        val rightImpulse = if (right.isNotEmpty()) deconvolveChannel(capture.samples, marker.rightStartSample ?: 0, parts.sweepFrames, sweep, capture.sampleRateHz) else null
        val leftDirect = leftImpulse?.let(::directArrival)
        val rightDirect = rightImpulse?.let(::directArrival)
        val directFailure = listOfNotNull(
            if (left.isNotEmpty()) leftDirect?.rejection else null,
            if (right.isNotEmpty()) rightDirect?.rejection else null,
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

    private fun analyzeSweep(
        samples: FloatArray,
        start: Int,
        length: Int,
        sweep: MeasurementSweep,
        sampleRate: Int,
        profile: MicrophoneCalibrationProfile?,
    ): List<ResponsePoint> {
        if (start < 0 || start + length > samples.size) return emptyList()
        val reference = MeasurementSweepGenerator.generateSweepSignal(sweep, sampleRate)
        val recorded = samples.copyOfRange(start, start + length)
        val points = FrequencyResponse.extract(recorded, reference, sampleRate)
        return points.map { point ->
            point.copy(magnitudeDb = point.magnitudeDb + (profile?.compensationDbAt(point.frequencyHz) ?: 0f))
        }
    }

    private fun deconvolveChannel(
        samples: FloatArray,
        start: Int,
        length: Int,
        sweep: MeasurementSweep,
        sampleRate: Int,
    ): FloatArray? {
        if (start < 0 || start + length > samples.size) return null
        val reference = MeasurementSweepGenerator.generateSweepSignal(sweep, sampleRate)
        return EssDeconvolver.deconvolve(samples.copyOfRange(start, start + length), reference)
    }

    private fun quality(samples: FloatArray, sweep: MeasurementSweep, sampleRate: Int): CaptureSignalQuality {
        var sumSquares = 0.0
        var peak = 0f
        var clipped = 0
        samples.forEach { sample ->
            val absolute = abs(sample)
            sumSquares += sample * sample
            peak = max(peak, absolute)
            if (absolute >= CLIP_THRESHOLD) clipped++
        }
        val rms = sqrt(sumSquares / samples.size).toFloat()
        val parts = scaledParts(sweep, sampleRate)
        val noiseEnd = min(samples.size, parts.leadingMarkerStartFrame)
        val noiseStart = max(0, noiseEnd - min(parts.preRollFrames, sampleRate / 4))
        val noise = if (noiseEnd > noiseStart) rms(samples, noiseStart, noiseEnd) else 0f
        val snr = if (noise > 0f && rms > 0f) (20f * log10(rms / noise)) else null
        return CaptureSignalQuality(rms, peak, snr, clipped)
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
        val driftTrusted = selected != null && minimum >= 0.55f &&
            (accepted || failure == MarkerFailure.CLOCK_DRIFT_UNRELIABLE)
        val start = if (accepted && selected != null) {
            selected.leading.sample + ((tvParts.sweepStartFrame - tvParts.leadingMarkerStartFrame) *
                sampleRateHz / sweep.sampleRate.toFloat() * selected.clockRatio).roundToInt()
        } else null
        val rightStart = if (accepted && selected != null) {
            selected.leading.sample + ((tvParts.rightSweepStartFrame - tvParts.leadingMarkerStartFrame) *
                sampleRateHz / sweep.sampleRate.toFloat() * selected.clockRatio).roundToInt()
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
        peaks.forEach { index ->
            if (selected.none { abs(it.sample - index) < minimumDistance }) {
                selected += MarkerCandidate(index, correlation[index])
                if (selected.size == MAX_SEARCH_CANDIDATES) return@forEach
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
        require(recorded.isNotEmpty() && reference.isNotEmpty())
        val length = nextPowerOfTwo(recorded.size + reference.size - 1)
        val recordedReal = DoubleArray(length)
        val recordedImaginary = DoubleArray(length)
        val referenceReal = DoubleArray(length)
        val referenceImaginary = DoubleArray(length)
        recorded.forEachIndexed { index, value -> recordedReal[index] = value.toDouble() }
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
        return FloatArray(min(recorded.size, recordedReal.size)) { index -> recordedReal[index].toFloat() }
    }
}

object FrequencyResponse {
    fun extract(recorded: FloatArray, reference: FloatArray, sampleRateHz: Int, points: Int = 48): List<ResponsePoint> {
        require(recorded.size == reference.size && recorded.isNotEmpty())
        require(points >= 2 && sampleRateHz > 0)
        val length = nextPowerOfTwo(recorded.size)
        val recordedReal = DoubleArray(length)
        val recordedImaginary = DoubleArray(length)
        val referenceReal = DoubleArray(length)
        val referenceImaginary = DoubleArray(length)
        recorded.forEachIndexed { index, value -> recordedReal[index] = value.toDouble() }
        reference.forEachIndexed { index, value -> referenceReal[index] = value.toDouble() }
        CalibrationFft.transform(recordedReal, recordedImaginary)
        CalibrationFft.transform(referenceReal, referenceImaginary)
        val maxReferencePower = referenceReal.indices.maxOf { index ->
            referenceReal[index] * referenceReal[index] + referenceImaginary[index] * referenceImaginary[index]
        }
        val regularization = max(maxReferencePower * 1e-7, 1e-12)
        val raw = (0 until points).map { index ->
            val frequency = 20f * (20_000f / 20f).pow(index.toFloat() / (points - 1))
            val bin = (frequency * length / sampleRateHz).roundToInt().coerceIn(1, length / 2)
            val refPower = referenceReal[bin] * referenceReal[bin] + referenceImaginary[bin] * referenceImaginary[bin]
            val numeratorReal = recordedReal[bin] * referenceReal[bin] + recordedImaginary[bin] * referenceImaginary[bin]
            val numeratorImaginary = recordedImaginary[bin] * referenceReal[bin] - recordedReal[bin] * referenceImaginary[bin]
            val magnitude = sqrt(numeratorReal * numeratorReal + numeratorImaginary * numeratorImaginary) /
                max(regularization, refPower)
            ResponsePoint(frequency, (20 * log10(max(magnitude, 1e-12))).toFloat())
        }
        val normalization = raw.filter { it.frequencyHz in 500f..2_000f }.map(ResponsePoint::magnitudeDb).let { values ->
            if (values.isEmpty()) 0f else values.sorted()[values.size / 2]
        }
        return raw.map { point -> point.copy(magnitudeDb = point.magnitudeDb - normalization) }
    }
}

private fun directArrival(impulse: FloatArray): DirectArrivalDiagnostics {
    val searchEnd = min(impulse.size, max(1, (impulse.size * 0.08f).toInt()))
    val noiseStart = max(0, impulse.size - max(1, impulse.size / 4))
    val noiseRms = rms(impulse, noiseStart, impulse.size)
    var peakSample = 0
    var peak = 0f
    for (index in 0 until searchEnd) {
        if (abs(impulse[index]) > peak) {
            peak = abs(impulse[index])
            peakSample = index
        }
    }
    val threshold = max(noiseRms * 8f, 1e-7f)
    if (peak < noiseRms * 6f || peak < 1e-7f) {
        return DirectArrivalDiagnostics(null, peak, noiseRms, ratioDb(peak, noiseRms), 0f, threshold, DirectArrivalFailure.PEAK_BELOW_NOISE)
    }
    val radius = max(1, (impulse.size * 0.0001f).toInt())
    val start = max(0, peakSample - radius)
    val end = min(impulse.size, peakSample + radius + 1)
    val support = rms(impulse, start, end)
    val supportThreshold = max(noiseRms * 1.5f, threshold * 0.05f)
    if (support < supportThreshold) {
        return DirectArrivalDiagnostics(null, peak, noiseRms, ratioDb(peak, noiseRms), support, supportThreshold, DirectArrivalFailure.CANDIDATE_NOT_SUSTAINED)
    }
    return DirectArrivalDiagnostics(peakSample, peak, noiseRms, ratioDb(peak, noiseRms), support, supportThreshold, null)
}

private fun rms(values: FloatArray, start: Int, end: Int): Float {
    if (end <= start) return 0f
    var sum = 0.0
    for (index in start until end) sum += values[index] * values[index]
    return sqrt(sum / (end - start)).toFloat()
}

private fun ratioDb(numerator: Float, denominator: Float): Float? =
    if (numerator > 0f && denominator > 0f) (20 * log10(numerator / denominator)).toFloat() else null

private fun nextPowerOfTwo(value: Int): Int {
    require(value > 0)
    var result = 1
    while (result < value) result = result shl 1
    return result
}
