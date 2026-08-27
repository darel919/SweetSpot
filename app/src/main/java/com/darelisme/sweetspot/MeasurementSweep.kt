package com.darelisme.sweetspot

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The playback description for one physical microphone position.
 *
 * The marker and both routed sweeps are one capture. A repair may suppress
 * one sweep, but it still uses the same marker/layout so the browser can keep
 * the accepted sibling channel and analyse the requested channel in place.
 */
data class MeasurementSweep(
    val sampleRate: Int,
    val startHz: Float = 20f,
    val endHz: Float = 20_000f,
    val durationMs: Int = 1_500,
    val preRollMs: Int = 500,
    val postRollMs: Int = 500,
    val syncMarkerStartHz: Float = 700f,
    val syncMarkerEndHz: Float = 2_600f,
    val syncMarkerDurationMs: Int = 150,
    val syncMarkerGapMs: Int = 50,
    val endMarkerStartHz: Float = 3_500f,
    val endMarkerEndHz: Float = 1_500f,
    val endMarkerDurationMs: Int = 150,
    val interSweepGapMs: Int = 50,
    val sweepLevelDbfs: Float = -12f,
    val markerLevelDbfs: Float = -12f,
    val fadeInMs: Int = 20,
    val fadeOutMs: Int = 20,
    val algorithm: String = "exponential-sine-v1",
    val captureKind: String = "position-composite",
    val sweepRevision: String = "android-sweep-v3",
    val markerChannel: String = "left",
) {
    val totalFrames: Int
        get() = parts().totalFrames

    internal data class Parts(
        val captureKind: String,
        val preRollFrames: Int,
        val syncMarkerFrames: Int,
        val syncMarkerGapFrames: Int,
        val sweepFrames: Int,
        val interSweepGapFrames: Int,
        val endMarkerFrames: Int,
        val postRollFrames: Int,
    ) {
        val leadingMarkerStartFrame: Int
            get() = (preRollFrames - syncMarkerFrames - syncMarkerGapFrames).coerceAtLeast(0)
        val sweepStartFrame: Int
            get() = preRollFrames
        val rightSweepStartFrame: Int
            get() = sweepStartFrame + sweepFrames + interSweepGapFrames
        val trailingMarkerStartFrame: Int
            get() = if (captureKind == "marker-only") {
                leadingMarkerStartFrame + syncMarkerFrames + syncMarkerGapFrames
            } else {
                rightSweepStartFrame + sweepFrames + syncMarkerGapFrames
            }
        val totalFrames: Int
            get() = trailingMarkerStartFrame + endMarkerFrames + postRollFrames
    }

    internal fun parts(): Parts = Parts(
        captureKind = captureKind,
        preRollFrames = (preRollMs * sampleRate / 1000f).roundToInt(),
        syncMarkerFrames = (syncMarkerDurationMs * sampleRate / 1000f).roundToInt().coerceAtLeast(1),
        syncMarkerGapFrames = (syncMarkerGapMs * sampleRate / 1000f).roundToInt(),
        sweepFrames = (durationMs * sampleRate / 1000f).roundToInt().coerceAtLeast(1),
        interSweepGapFrames = (interSweepGapMs * sampleRate / 1000f).roundToInt(),
        endMarkerFrames = (endMarkerDurationMs * sampleRate / 1000f).roundToInt().coerceAtLeast(1),
        postRollFrames = (postRollMs * sampleRate / 1000f).roundToInt(),
    )
}

object MeasurementSweepGenerator {
    private val MARKER_CHANNELS = setOf("left", "right")

    fun generateStereoPcm(sweep: MeasurementSweep, channel: String = "both"): ShortArray {
        val output = ShortArray(sweep.totalFrames * 2)
        writeStereoPcm(sweep, channel, 0, sweep.totalFrames, output)
        return output
    }

    fun writeStereoPcm(
        sweep: MeasurementSweep,
        channel: String,
        firstFrame: Int,
        frameCount: Int,
        output: ShortArray,
    ) {
        require(sweep.sampleRate > 0)
        require(sweep.startHz > 0f && sweep.endHz > sweep.startHz)
        require(sweep.syncMarkerStartHz > 0f && sweep.syncMarkerEndHz > sweep.syncMarkerStartHz)
        require(sweep.endMarkerStartHz > 0f && sweep.endMarkerEndHz > 0f)
        require(maxOf(sweep.syncMarkerEndHz, sweep.endMarkerStartHz) < sweep.sampleRate / 2f)
        require(sweep.preRollMs >= sweep.syncMarkerDurationMs + sweep.syncMarkerGapMs)
        require(sweep.durationMs > 0)
        require(sweep.interSweepGapMs >= 0)
        require(channel == "both" || channel == "left" || channel == "right")
        require(sweep.markerChannel in MARKER_CHANNELS)
        require(firstFrame in 0..sweep.totalFrames)
        require(frameCount in 0..(sweep.totalFrames - firstFrame))
        require(output.size >= frameCount * 2)

        val parts = sweep.parts()
        val sweepFrames = parts.sweepFrames
        val fadeInFrames = (sweep.fadeInMs * sweep.sampleRate / 1000f).roundToInt()
        val fadeOutFrames = (sweep.fadeOutMs * sweep.sampleRate / 1000f).roundToInt()
        val amplitude = 10.0.pow(sweep.sweepLevelDbfs / 20.0)
        val logarithmicRate = ln(sweep.endHz / sweep.startHz) / (sweep.durationMs / 1000.0)
        val phaseScale = 2.0 * PI * sweep.startHz / logarithmicRate
        val leftEnabled = channel == "both" || channel == "left"
        val rightEnabled = channel == "both" || channel == "right"
        output.fill(0, 0, frameCount * 2)

        for (frame in firstFrame until min(firstFrame + frameCount, parts.totalFrames)) {
            val marker = when {
                frame in parts.leadingMarkerStartFrame until parts.leadingMarkerStartFrame + parts.syncMarkerFrames ->
                    syncMarkerValue(sweep, frame - parts.leadingMarkerStartFrame, parts.syncMarkerFrames, end = false)
                frame in parts.trailingMarkerStartFrame until parts.trailingMarkerStartFrame + parts.endMarkerFrames ->
                    syncMarkerValue(sweep, frame - parts.trailingMarkerStartFrame, parts.endMarkerFrames, end = true)
                else -> null
            }
            val leftSweepFrame = frame - parts.sweepStartFrame
            val rightSweepFrame = frame - parts.rightSweepStartFrame
            val leftValue = marker?.takeIf { sweep.markerChannel == "left" } ?: if (sweep.captureKind != "position-composite") null else sweepValue(
                sweep = sweep,
                frame = leftSweepFrame,
                frameCount = sweepFrames,
                fadeInFrames = fadeInFrames,
                fadeOutFrames = fadeOutFrames,
                amplitude = amplitude,
                phaseScale = phaseScale,
                logarithmicRate = logarithmicRate,
            ).takeIf { leftEnabled }
            val rightValue = marker?.takeIf { sweep.markerChannel == "right" } ?: if (sweep.captureKind != "position-composite") null else sweepValue(
                sweep = sweep,
                frame = rightSweepFrame,
                frameCount = sweepFrames,
                fadeInFrames = fadeInFrames,
                fadeOutFrames = fadeOutFrames,
                amplitude = amplitude,
                phaseScale = phaseScale,
                logarithmicRate = logarithmicRate,
            ).takeIf { rightEnabled }
            val outputFrame = frame - firstFrame
            output[outputFrame * 2] = pcmSample(leftValue ?: 0.0)
            output[outputFrame * 2 + 1] = pcmSample(rightValue ?: 0.0)
        }
    }

    private fun sweepValue(
        sweep: MeasurementSweep,
        frame: Int,
        frameCount: Int,
        fadeInFrames: Int,
        fadeOutFrames: Int,
        amplitude: Double,
        phaseScale: Double,
        logarithmicRate: Double,
    ): Double {
        if (frame !in 0 until frameCount) return 0.0
        val progress = if (frameCount == 1) 0.0 else frame.toDouble() / (frameCount - 1)
        val time = progress * sweep.durationMs / 1000.0
        val phase = phaseScale * (exp(logarithmicRate * time) - 1.0)
        val fadeIn = if (fadeInFrames <= 1) 1.0 else min(1.0, frame.toDouble() / (fadeInFrames - 1))
        val fadeOut = if (fadeOutFrames <= 1) 1.0 else {
            min(1.0, (frameCount - 1 - frame).toDouble() / (fadeOutFrames - 1))
        }
        return sin(phase) * amplitude * min(fadeIn, fadeOut)
    }

    private fun syncMarkerValue(
        sweep: MeasurementSweep,
        frame: Int,
        frameCount: Int,
        end: Boolean,
    ): Double {
        val progress = if (frameCount == 1) 0.0 else frame.toDouble() / (frameCount - 1)
        val durationMs = if (end) sweep.endMarkerDurationMs else sweep.syncMarkerDurationMs
        val startHz = if (end) sweep.endMarkerStartHz else sweep.syncMarkerStartHz
        val endHz = if (end) sweep.endMarkerEndHz else sweep.syncMarkerEndHz
        val time = progress * durationMs / 1000.0
        val rate = (endHz - startHz) / (durationMs / 1000.0)
        val phase = 2.0 * PI * (startHz * time + 0.5 * rate * time * time)
        val window = if (frameCount <= 1) 1.0 else sin(PI * progress)
        return 10.0.pow(sweep.markerLevelDbfs / 20.0) * window * sin(phase)
    }

    private fun pcmSample(value: Double): Short =
        (value * Short.MAX_VALUE)
            .roundToInt()
            .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            .toShort()
}
