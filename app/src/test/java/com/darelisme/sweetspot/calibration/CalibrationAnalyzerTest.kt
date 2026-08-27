package com.darelisme.sweetspot.calibration

import com.darelisme.sweetspot.MeasurementSweep
import com.darelisme.sweetspot.MeasurementSweepGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CalibrationAnalyzerTest {
    private val analyzer = AndroidResponseV1Analyzer()

    @Test
    fun generatedSweepUsesTheTvMarkersAndProducesAnAcceptedPair() {
        val sweep = shortSweep()
        val capture = monoCapture(sweep)

        val marker = MarkerDetector.detect(capture.samples, sweep, capture.sampleRateHz)

        assertTrue(marker.accepted)
        assertTrue(marker.confidence >= MarkerDetector.MARKER_PAIR_SCORE_THRESHOLD)
        assertTrue(marker.leadingCandidates.size <= MarkerDetector.MAX_EXPORTED_CANDIDATES)
        assertTrue(marker.trailingCandidates.size <= MarkerDetector.MAX_EXPORTED_CANDIDATES)
        assertNotNull(marker.startSample)
        assertNotNull(marker.rightStartSample)
    }

    @Test
    fun weakMarkerEvidenceDoesNotBecomeClockDrift() {
        val sweep = shortSweep()
        val samples = FloatArray(sweep.totalFrames) { index ->
            (kotlin.math.sin(index * 0.731) * 0.08f).toFloat()
        }

        val marker = MarkerDetector.detect(samples, sweep, sweep.sampleRate)

        assertTrue(!marker.accepted)
        assertTrue(marker.driftPpm == null)
        assertTrue(marker.failure != null)
    }

    @Test
    fun markerSeparationRegressionIsBadTimingAndNotAccepted() {
        val sweep = MeasurementSweep(48_000)
        val pcm = MeasurementSweepGenerator.generateStereoPcm(sweep, "left")
        val parts = sweep.parts()
        val shift = 887
        val shiftedTrailingStart = parts.trailingMarkerStartFrame - shift
        val samples = FloatArray(sweep.totalFrames) { frame -> pcm[frame * 2].toFloat() / Short.MAX_VALUE }
        for (frame in shiftedTrailingStart until shiftedTrailingStart + parts.endMarkerFrames) {
            samples[frame] = 0f
        }
        for (frame in parts.trailingMarkerStartFrame until parts.trailingMarkerStartFrame + parts.endMarkerFrames) {
            samples[frame] = 0f
        }
        val marker = MeasurementSweepGenerator.generateSyncMarker(
            sweep,
            sweep.sampleRate,
            com.darelisme.sweetspot.SyncMarkerKind.END,
        )
        marker.forEachIndexed { index, value -> samples[shiftedTrailingStart + index] = value }

        val detection = MarkerDetector.detect(samples, sweep, sweep.sampleRate)

        assertTrue(!detection.accepted)
        assertEquals(MarkerFailure.MARKER_PAIR_BAD_TIMING, detection.failure)
        assertTrue(detection.driftPpm == null)
    }

    @Test
    fun actualRecorderRateIsUsedForMarkerTiming() {
        val sweep = shortSweep()
        val sampleRate = 44_100
        val recorderSweep = sweep.copy(sampleRate = sampleRate)
        val pcm = MeasurementSweepGenerator.generateStereoPcm(recorderSweep, "left")
        val samples = FloatArray(recorderSweep.totalFrames) { index -> pcm[index * 2].toFloat() / Short.MAX_VALUE }

        val detection = MarkerDetector.detect(samples, sweep, sampleRate)

        assertTrue(detection.accepted)
        assertTrue(kotlin.math.abs(detection.driftPpm ?: Float.POSITIVE_INFINITY) < 1f)
    }

    @Test
    fun analyzerReportsClippingBeforeAcousticAcceptance() {
        val sweep = shortSweep()
        val capture = monoCapture(sweep).samples.copyOf()
        capture[capture.lastIndex] = 1f

        val result = analyzer.analyze(CalibrationCapture(sweep.sampleRate, capture), sweep)

        assertEquals(AnalysisStatus.CAPTURE_CLIPPED, result.status)
    }

    @Test
    fun microphoneProfileCompensationUsesInverseNormalizedResponse() {
        val profile = MicrophoneCalibrationProfile(
            frequenciesHz = floatArrayOf(100f, 1_000f, 10_000f),
            responseDb = floatArrayOf(2f, 0f, -2f),
        )

        assertEquals(-1f, profile.compensationDbAt(316.22775f), 0.01f)
        assertEquals(1f, profile.compensationDbAt(3_162.2776f), 0.01f)
    }

    private fun shortSweep(): MeasurementSweep = MeasurementSweep(
        sampleRate = 8_000,
        startHz = 80f,
        endHz = 3_000f,
        durationMs = 100,
        preRollMs = 40,
        postRollMs = 40,
        syncMarkerStartHz = 500f,
        syncMarkerEndHz = 2_000f,
        syncMarkerDurationMs = 20,
        syncMarkerGapMs = 10,
        endMarkerStartHz = 2_500f,
        endMarkerEndHz = 700f,
        endMarkerDurationMs = 20,
        interSweepGapMs = 10,
    )

    private fun monoCapture(sweep: MeasurementSweep): CalibrationCapture {
        val pcm = MeasurementSweepGenerator.generateStereoPcm(sweep, "left")
        return CalibrationCapture(
            sampleRateHz = sweep.sampleRate,
            samples = FloatArray(sweep.totalFrames) { frame -> pcm[frame * 2].toFloat() / Short.MAX_VALUE },
            channel = AnalysisChannel.LEFT,
        )
    }
}
