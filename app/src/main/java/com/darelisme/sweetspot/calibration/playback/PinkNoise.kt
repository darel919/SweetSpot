package com.darelisme.sweetspot.calibration.playback

import java.util.Random
import kotlin.math.pow
import kotlin.math.sqrt

object PinkNoiseGenerator {
    private const val RANDOM_SEED = 0x53504F54504B4E47L
    const val DEFAULT_LOOP_DURATION_MS = 2_000
    const val DEFAULT_LEVEL_DBFS = -24f

    fun generateStereoPcm(
        sampleRate: Int,
        durationMs: Int = DEFAULT_LOOP_DURATION_MS,
        levelDbfs: Float = DEFAULT_LEVEL_DBFS
    ): ShortArray {
        require(sampleRate > 0)
        require(durationMs > 0)
        require(levelDbfs <= 0f)

        val frameCount = (sampleRate * durationMs / 1000f).toInt().coerceAtLeast(1)
        val stream = createStereoStream(sampleRate, durationMs, levelDbfs)
        val output = ShortArray(frameCount * 2)
        check(stream.write(output, frameCount) == frameCount)
        return output
    }

    internal fun createStereoStream(
        sampleRate: Int,
        durationMs: Int = DEFAULT_LOOP_DURATION_MS,
        levelDbfs: Float = DEFAULT_LEVEL_DBFS
    ): StereoStream {
        require(sampleRate > 0)
        require(durationMs > 0)
        require(levelDbfs <= 0f)

        val frameCount = (sampleRate * durationMs / 1000f).toInt().coerceAtLeast(1)
        val state = PinkNoiseState()
        var sumSquares = 0.0

        repeat(frameCount) {
            val pink = state.next()
            sumSquares += pink * pink
        }

        val measuredRms = sqrt(sumSquares / frameCount).coerceAtLeast(1e-12)
        val targetRms = 10.0.pow(levelDbfs / 20.0)
        return StereoStream(frameCount, targetRms / measuredRms)
    }

    internal class StereoStream(
        val frameCount: Int,
        private val scale: Double
    ) {
        private val state = PinkNoiseState()
        private var generatedFrames = 0

        val remainingFrames: Int
            get() = frameCount - generatedFrames

        fun write(output: ShortArray, frameCount: Int): Int {
            require(frameCount in 0..remainingFrames)
            require(output.size >= frameCount * 2)

            for (index in 0 until frameCount) {
                val sample = (state.next().toFloat() * scale * Short.MAX_VALUE)
                    .toInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    .toShort()
                output[index * 2] = sample
                output[index * 2 + 1] = sample
            }
            generatedFrames += frameCount
            return frameCount
        }

        fun reset() {
            state.reset()
            generatedFrames = 0
        }
    }

    private class PinkNoiseState {
        private val random = Random(RANDOM_SEED)
        private var b0 = 0.0
        private var b1 = 0.0
        private var b2 = 0.0
        private var b3 = 0.0
        private var b4 = 0.0
        private var b5 = 0.0
        private var b6 = 0.0

        fun next(): Double {
            val white = random.nextDouble() * 2.0 - 1.0
            b0 = 0.99886 * b0 + white * 0.0555179
            b1 = 0.99332 * b1 + white * 0.0750759
            b2 = 0.96900 * b2 + white * 0.1538520
            b3 = 0.86650 * b3 + white * 0.3104856
            b4 = 0.55000 * b4 + white * 0.5329522
            b5 = -0.7616 * b5 - white * 0.0168980
            val pink = (b0 + b1 + b2 + b3 + b4 + b5 + b6 + white * 0.5362) * 0.11
            b6 = white * 0.115926
            return pink
        }

        fun reset() {
            random.setSeed(RANDOM_SEED)
            b0 = 0.0
            b1 = 0.0
            b2 = 0.0
            b3 = 0.0
            b4 = 0.0
            b5 = 0.0
            b6 = 0.0
        }
    }
}
