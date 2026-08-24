package com.darelisme.sweetspot

import org.junit.Assert.assertEquals
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
}
