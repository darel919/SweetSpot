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
    val levelDbfs: Float = -12f,
    val fadeInMs: Int = 20,
    val fadeOutMs: Int = 20,
    val algorithm: String = "exponential-sine-v1"
) {
    val totalFrames: Int
        get() = ((preRollMs + durationMs + postRollMs) * sampleRate / 1000f).roundToInt()
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
        require(sweep.durationMs > 0)
        require(channel == "both" || channel == "left" || channel == "right")
        require(firstFrame in 0..sweep.totalFrames)
        require(frameCount in 0..(sweep.totalFrames - firstFrame))
        require(output.size >= frameCount * 2)

        val totalFrames = sweep.totalFrames
        val preRollFrames = (sweep.preRollMs * sweep.sampleRate / 1000f).roundToInt()
        val sweepFrames = (sweep.durationMs * sweep.sampleRate / 1000f).roundToInt()
        val fadeInFrames = (sweep.fadeInMs * sweep.sampleRate / 1000f).roundToInt()
        val fadeOutFrames = (sweep.fadeOutMs * sweep.sampleRate / 1000f).roundToInt()
        val amplitude = 10.0.pow(sweep.levelDbfs / 20.0)
        val k = (sweep.durationMs / 1000.0) / ln(sweep.endHz / sweep.startHz)
        val activeEndFrame = min(totalFrames, preRollFrames + sweepFrames)
        output.fill(0, 0, frameCount * 2)

        for (frame in firstFrame until min(firstFrame + frameCount, activeEndFrame)) {
            val sweepFrame = frame - preRollFrames
            if (sweepFrame < 0 || sweepFrame >= sweepFrames) continue

            val progress = if (sweepFrames == 1) 0.0 else sweepFrame.toDouble() / (sweepFrames - 1)
            val t = progress * sweep.durationMs / 1000.0
            val phase = 2.0 * PI * sweep.startHz * k * (exp(t / k) - 1.0)
            val fadeIn = if (fadeInFrames <= 1) 1.0 else {
                min(1.0, sweepFrame.toDouble() / (fadeInFrames - 1))
            }
            val fadeOut = if (fadeOutFrames <= 1) 1.0 else {
                min(1.0, (sweepFrames - 1 - sweepFrame).toDouble() / (fadeOutFrames - 1))
            }
            val sample = (sin(phase) * amplitude * min(fadeIn, fadeOut) * Short.MAX_VALUE)
                .roundToInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                .toShort()
            val outputFrame = frame - firstFrame
            output[outputFrame * 2] = if (channel == "right") 0 else sample
            output[outputFrame * 2 + 1] = if (channel == "left") 0 else sample
        }
    }
}
