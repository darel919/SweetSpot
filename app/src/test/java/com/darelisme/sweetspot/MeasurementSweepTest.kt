package com.darelisme.sweetspot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MeasurementSweepTest {
    @Test
    fun defaultSweepUsesTheCalibrationTimingContract() {
        val sweep = MeasurementSweep(48_000)

        assertEquals(4_000, sweep.durationMs)
        assertEquals(500, sweep.preRollMs)
        assertEquals(1_500, sweep.postRollMs)
        assertEquals(48_000 * 6_050 / 1_000, sweep.totalFrames)
        assertEquals(1_000f, sweep.syncMarkerStartHz, 0f)
        assertEquals(3_000f, sweep.syncMarkerEndHz, 0f)
        assertEquals(40, sweep.syncMarkerDurationMs)
        assertEquals(10, sweep.syncMarkerGapMs)
        assertEquals(20f, sweep.startHz, 0f)
        assertEquals(20_000f, sweep.endHz, 0f)
        assertEquals(-12f, sweep.levelDbfs, 0f)
        assertEquals(20, sweep.fadeInMs)
        assertEquals(20, sweep.fadeOutMs)
    }

    @Test
    fun defaultSweepHasDeterministicStereoLayoutAndSilence() {
        val sweep = MeasurementSweep(48_000)
        val pcm = MeasurementSweepGenerator.generateStereoPcm(sweep)
        val parts = sweep.parts()
        val totalFrames = sweep.totalFrames

        assertEquals(totalFrames * 2, pcm.size)
        for (frame in 0 until totalFrames) {
            assertEquals("stereo frame $frame", pcm[frame * 2].toInt(), pcm[frame * 2 + 1].toInt())
        }
        for (frame in 0 until parts.leadingMarkerStartFrame) {
            assertEquals(0, pcm[frame * 2].toInt())
        }
        assertTrue(pcm.slice((parts.leadingMarkerStartFrame + 1) * 2 until (parts.sweepStartFrame - parts.syncMarkerGapFrames - 1) * 2)
            .any { it.toInt() != 0 })
        assertTrue(pcm.slice((parts.trailingMarkerStartFrame + 1) * 2 until (parts.totalFrames - 1) * 2)
            .any { it.toInt() != 0 })
        assertTrue(pcm.maxOf { kotlin.math.abs(it.toInt()) } <= 10_370)
    }

    @Test
    fun routedSweepsSilenceTheOppositeChannel() {
        val sweep = MeasurementSweep(
            8_000,
            durationMs = 100,
            preRollMs = 10,
            postRollMs = 10,
            syncMarkerEndHz = 2_500f,
            syncMarkerDurationMs = 4,
            syncMarkerGapMs = 1,
        )
        val left = MeasurementSweepGenerator.generateStereoPcm(sweep, "left")
        val right = MeasurementSweepGenerator.generateStereoPcm(sweep, "right")
        val firstActiveFrame = sweep.parts().sweepStartFrame + 5

        assertTrue(left[firstActiveFrame * 2].toInt() != 0 || left[firstActiveFrame * 2 + 1].toInt() != 0)
        assertEquals(0, left[firstActiveFrame * 2 + 1].toInt())
        assertEquals(0, right[firstActiveFrame * 2].toInt())
        assertTrue(right[firstActiveFrame * 2 + 1].toInt() != 0)
    }

    @Test
    fun chunkedGenerationMatchesTheCompleteRoutedSweep() {
        val sweep = MeasurementSweep(
            8_000,
            durationMs = 257,
            preRollMs = 13,
            postRollMs = 17,
            syncMarkerEndHz = 2_500f,
            syncMarkerDurationMs = 4,
            syncMarkerGapMs = 1,
        )
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

    @Test
    fun generatedSweepMatchesTheCrossLanguageGoldenVector() {
        val fixture = javaClass.classLoader
            ?.getResourceAsStream("measurement-sweep-golden.json")
            ?.bufferedReader()
            ?.use { it.readText() }
            ?: error("Missing measurement sweep golden vector")
        fun number(name: String): Double = Regex("\"$name\"\\s*:\\s*(-?[0-9]+(?:\\.[0-9]+)?)")
            .find(fixture)
            ?.groupValues
            ?.get(1)
            ?.toDouble()
            ?: error("Missing golden-vector field $name")
        val pcmText = fixture.substringAfter("\"pcm16\": [").substringBefore(']')
        val expected = pcmText.split(',').filter { it.isNotBlank() }.map { it.trim().toInt() }
        val sweep = MeasurementSweep(
            sampleRate = number("sampleRate").toInt(),
            startHz = number("startHz").toFloat(),
            endHz = number("endHz").toFloat(),
            durationMs = number("durationMs").toInt(),
            preRollMs = number("preRollMs").toInt(),
            postRollMs = number("postRollMs").toInt(),
            syncMarkerStartHz = number("syncMarkerStartHz").toFloat(),
            syncMarkerEndHz = number("syncMarkerEndHz").toFloat(),
            syncMarkerDurationMs = number("syncMarkerDurationMs").toInt(),
            syncMarkerGapMs = number("syncMarkerGapMs").toInt(),
            levelDbfs = number("levelDbfs").toFloat(),
            fadeInMs = number("fadeInMs").toInt(),
            fadeOutMs = number("fadeOutMs").toInt(),
        )
        val actual = MeasurementSweepGenerator.generateStereoPcm(sweep)

        assertEquals(expected.size, actual.size)
        for (index in actual.indices) {
            assertTrue(
                "PCM sample $index differed by more than one quantization step",
                kotlin.math.abs(actual[index].toInt() - expected[index]) <= 1,
            )
        }
    }
}
