package com.darelisme.sweetspot

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class PinkNoiseTest {
    @Test
    fun generatedLoopIsDeterministicStereoAndAtTheRequestedLevel() {
        val first = PinkNoiseGenerator.generateStereoPcm(48_000)
        val second = PinkNoiseGenerator.generateStereoPcm(48_000)

        assertArrayEquals(first, second)
        var sumSquares = 0.0
        var peak = 0
        for (frame in first.indices step 2) {
            assertTrue(first[frame] == first[frame + 1])
            val sample = first[frame].toInt()
            sumSquares += (sample.toDouble() / Short.MAX_VALUE) * (sample.toDouble() / Short.MAX_VALUE)
            peak = maxOf(peak, kotlin.math.abs(sample))
        }
        val rms = sqrt(sumSquares / (first.size / 2))
        assertTrue(rms in 0.055..0.072)
        assertTrue(peak < Short.MAX_VALUE / 2)
    }

    @Test
    fun streamedChunksMatchTheCompleteLoopAndResetDeterministically() {
        val expected = PinkNoiseGenerator.generateStereoPcm(8_000, durationMs = 257, levelDbfs = -18f)
        val stream = PinkNoiseGenerator.createStereoStream(8_000, durationMs = 257, levelDbfs = -18f)
        val chunk = ShortArray(13 * 2)
        val actual = ShortArray(expected.size)
        var firstFrame = 0

        while (firstFrame < stream.frameCount) {
            val frameCount = minOf(13, stream.frameCount - firstFrame)
            assertEquals(frameCount, stream.write(chunk, frameCount))
            chunk.copyInto(actual, firstFrame * 2, 0, frameCount * 2)
            firstFrame += frameCount
        }

        assertArrayEquals(expected, actual)
        stream.reset()
        assertEquals(13, stream.write(chunk, 13))
        assertArrayEquals(expected.copyOfRange(0, 26), chunk)
    }
}
