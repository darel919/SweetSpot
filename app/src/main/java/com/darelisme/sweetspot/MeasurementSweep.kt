package com.darelisme.sweetspot

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.pow

data class MeasurementSweep(
    val sampleRate: Int,
    val startHz: Float = 20f,
    val endHz: Float = 20_000f,
    val durationMs: Int = 4_000,
    val preRollMs: Int = 500,
    val postRollMs: Int = 1_500,
    val syncMarkerStartHz: Float = 1_000f,
    val syncMarkerEndHz: Float = 3_000f,
    val syncMarkerDurationMs: Int = 40,
    val syncMarkerGapMs: Int = 10,
    val levelDbfs: Float = -12f,
    val fadeInMs: Int = 20,
    val fadeOutMs: Int = 20,
    val algorithm: String = "exponential-sine-v1"
) {
    val totalFrames: Int
        get() = parts().totalFrames

    internal data class Parts(
        val preRollFrames: Int,
        val syncMarkerFrames: Int,
        val syncMarkerGapFrames: Int,
        val sweepFrames: Int,
        val postRollFrames: Int,
    ) {
        val leadingMarkerStartFrame: Int get() = (preRollFrames - syncMarkerFrames - syncMarkerGapFrames).coerceAtLeast(0)
        val sweepStartFrame: Int get() = preRollFrames
        val trailingMarkerStartFrame: Int get() = sweepStartFrame + sweepFrames + postRollFrames + syncMarkerGapFrames
        val totalFrames: Int get() = trailingMarkerStartFrame + syncMarkerFrames
    }

    internal fun parts(): Parts = Parts(
        preRollFrames = (preRollMs * sampleRate / 1000f).roundToInt(),
        syncMarkerFrames = (syncMarkerDurationMs * sampleRate / 1000f).roundToInt().coerceAtLeast(1),
        syncMarkerGapFrames = (syncMarkerGapMs * sampleRate / 1000f).roundToInt(),
        sweepFrames = (durationMs * sampleRate / 1000f).roundToInt().coerceAtLeast(1),
        postRollFrames = (postRollMs * sampleRate / 1000f).roundToInt(),
    )
}

object MeasurementSweepGenerator {
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
        output: ShortArray
    ) {
        require(sweep.sampleRate > 0)
        require(sweep.startHz > 0f && sweep.endHz > sweep.startHz)
        require(sweep.syncMarkerStartHz > 0f && sweep.syncMarkerEndHz > sweep.syncMarkerStartHz)
        require(sweep.syncMarkerEndHz < sweep.sampleRate / 2f)
        require(sweep.preRollMs >= sweep.syncMarkerDurationMs + sweep.syncMarkerGapMs)
        require(sweep.durationMs > 0)
        require(channel == "both" || channel == "left" || channel == "right")
        require(firstFrame in 0..sweep.totalFrames)
        require(frameCount in 0..(sweep.totalFrames - firstFrame))
        require(output.size >= frameCount * 2)

        val parts = sweep.parts()
        val totalFrames = parts.totalFrames
        val preRollFrames = parts.preRollFrames
        val sweepFrames = parts.sweepFrames
        val fadeInFrames = (sweep.fadeInMs * sweep.sampleRate / 1000f).roundToInt()
        val fadeOutFrames = (sweep.fadeOutMs * sweep.sampleRate / 1000f).roundToInt()
        val amplitude = 10.0.pow(sweep.levelDbfs / 20.0)
        val k = (sweep.durationMs / 1000.0) / ln(sweep.endHz / sweep.startHz)
        output.fill(0, 0, frameCount * 2)

        for (frame in firstFrame until min(firstFrame + frameCount, totalFrames)) {
            val markerFrame = when {
                frame in parts.leadingMarkerStartFrame until parts.leadingMarkerStartFrame + parts.syncMarkerFrames ->
                    frame - parts.leadingMarkerStartFrame
                frame in parts.trailingMarkerStartFrame until parts.trailingMarkerStartFrame + parts.syncMarkerFrames ->
                    frame - parts.trailingMarkerStartFrame
                else -> -1
            }
            val sweepFrame = frame - parts.sweepStartFrame
            val value = when {
                markerFrame >= 0 -> syncMarkerValue(sweep, markerFrame, parts.syncMarkerFrames)
                sweepFrame in 0 until sweepFrames -> {
                    val progress = if (sweepFrames == 1) 0.0 else sweepFrame.toDouble() / (sweepFrames - 1)
                    val t = progress * sweep.durationMs / 1000.0
                    val phase = 2.0 * PI * sweep.startHz * k * (exp(t / k) - 1.0)
                    val fadeIn = if (fadeInFrames <= 1) 1.0 else {
                        min(1.0, sweepFrame.toDouble() / (fadeInFrames - 1))
                    }
                    val fadeOut = if (fadeOutFrames <= 1) 1.0 else {
                        min(1.0, (sweepFrames - 1 - sweepFrame).toDouble() / (fadeOutFrames - 1))
                    }
                    sin(phase) * amplitude * min(fadeIn, fadeOut)
                }
                else -> 0.0
            }
            val sample = (value * Short.MAX_VALUE)
                .roundToInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                .toShort()
            val outputFrame = frame - firstFrame
            output[outputFrame * 2] = if (channel == "right") 0 else sample
            output[outputFrame * 2 + 1] = if (channel == "left") 0 else sample
        }
    }

    private fun syncMarkerValue(sweep: MeasurementSweep, frame: Int, frameCount: Int): Double {
        val progress = if (frameCount == 1) 0.0 else frame.toDouble() / (frameCount - 1)
        val time = progress * sweep.syncMarkerDurationMs / 1000.0
        val rate = (sweep.syncMarkerEndHz - sweep.syncMarkerStartHz) / (sweep.syncMarkerDurationMs / 1000.0)
        val phase = 2.0 * PI * (sweep.syncMarkerStartHz * time + 0.5 * rate * time * time)
        val window = if (frameCount <= 1) 1.0 else sin(PI * progress)
        return 10.0.pow(sweep.levelDbfs / 20.0) * window * sin(phase)
    }
}
