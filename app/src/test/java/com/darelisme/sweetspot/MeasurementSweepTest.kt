package com.darelisme.sweetspot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MeasurementSweepTest {
    @Test
    fun defaultSweepHasDeterministicStereoLayoutAndSilence() {
        val sweep = MeasurementSweep(48_000)
        val pcm = MeasurementSweepGenerator.generateStereoPcm(sweep)
        val preRollFrames = sweep.preRollMs * sweep.sampleRate / 1000
        val sweepFrames = sweep.durationMs * sweep.sampleRate / 1000
        val totalFrames = sweep.totalFrames

        assertEquals(totalFrames * 2, pcm.size)
        for (frame in 0 until totalFrames) {
            assertEquals("stereo frame $frame", pcm[frame * 2].toInt(), pcm[frame * 2 + 1].toInt())
        }
        for (frame in 0 until preRollFrames) {
            assertEquals(0, pcm[frame * 2].toInt())
        }
        for (frame in (preRollFrames + sweepFrames) until totalFrames) {
            assertEquals(0, pcm[frame * 2].toInt())
        }
        assertTrue(pcm.maxOf { kotlin.math.abs(it.toInt()) } <= 10_370)
    }

    @Test
    fun routedSweepsSilenceTheOppositeChannel() {
        val sweep = MeasurementSweep(8_000, durationMs = 100, preRollMs = 10, postRollMs = 10)
        val left = MeasurementSweepGenerator.generateStereoPcm(sweep, "left")
        val right = MeasurementSweepGenerator.generateStereoPcm(sweep, "right")
        val firstActiveFrame = sweep.preRollMs * sweep.sampleRate / 1000 + 5

        assertTrue(left[firstActiveFrame * 2].toInt() != 0 || left[firstActiveFrame * 2 + 1].toInt() != 0)
        assertEquals(0, left[firstActiveFrame * 2 + 1].toInt())
        assertEquals(0, right[firstActiveFrame * 2].toInt())
        assertTrue(right[firstActiveFrame * 2 + 1].toInt() != 0)
    }

    @Test
    fun chunkedGenerationMatchesTheCompleteRoutedSweep() {
        val sweep = MeasurementSweep(8_000, durationMs = 257, preRollMs = 13, postRollMs = 17)
        val expected = MeasurementSweepGenerator.generateStereoPcm(sweep, "right")
        val chunk = ShortArray(11 * 2)
        val actual = ShortArray(expected.size)
        var firstFrame = 0

        while (firstFrame < sweep.totalFrames) {
            val frameCount = minOf(11, sweep.totalFrames - firstFrame)
            MeasurementSweepGenerator.writeStereoPcm(sweep, "right", firstFrame, frameCount, chunk)
            chunk.copyInto(actual, firstFrame * 2, 0, frameCount * 2)
            firstFrame += frameCount
        }

        assertArrayEquals(expected, actual)
    }
}
