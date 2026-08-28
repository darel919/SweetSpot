package com.darelisme.sweetspot.calibration.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MeasurementSweepTest {
    @Test
    fun defaultSweepUsesTheCalibrationTimingContract() {
        val sweep = MeasurementSweep(48_000)

        assertEquals("position-composite", sweep.captureKind)
        assertEquals("android-sweep-v3", sweep.sweepRevision)
        assertEquals("left", sweep.markerChannel)
        assertEquals(1_500, sweep.durationMs)
        assertEquals(500, sweep.preRollMs)
        assertEquals(500, sweep.postRollMs)
        assertEquals(48_000 * 4_250 / 1_000, sweep.totalFrames)
        assertEquals(700f, sweep.syncMarkerStartHz, 0f)
        assertEquals(2_600f, sweep.syncMarkerEndHz, 0f)
        assertEquals(150, sweep.syncMarkerDurationMs)
        assertEquals(50, sweep.syncMarkerGapMs)
        assertEquals(3_500f, sweep.endMarkerStartHz, 0f)
        assertEquals(1_500f, sweep.endMarkerEndHz, 0f)
        assertEquals(150, sweep.endMarkerDurationMs)
        assertEquals(50, sweep.interSweepGapMs)
        assertEquals(20f, sweep.startHz, 0f)
        assertEquals(20_000f, sweep.endHz, 0f)
        assertEquals(-12f, sweep.sweepLevelDbfs, 0f)
        assertEquals(-12f, sweep.markerLevelDbfs, 0f)
        assertEquals(20, sweep.fadeInMs)
        assertEquals(20, sweep.fadeOutMs)
    }

    @Test
    fun defaultSweepHasDeterministicSingleSpeakerMarkersAndStereoSweeps() {
        val sweep = MeasurementSweep(48_000)
        val pcm = MeasurementSweepGenerator.generateStereoPcm(sweep)
        val parts = sweep.parts()
        val totalFrames = sweep.totalFrames

        assertEquals(totalFrames * 2, pcm.size)
        for (frame in 0 until parts.leadingMarkerStartFrame) {
            assertEquals(0, pcm[frame * 2].toInt())
            assertEquals(0, pcm[frame * 2 + 1].toInt())
        }
        assertTrue((parts.leadingMarkerStartFrame until parts.sweepStartFrame).all { frame ->
            pcm[frame * 2 + 1].toInt() == 0
        })
        assertTrue(pcm.slice(parts.sweepStartFrame * 2 until parts.rightSweepStartFrame * 2)
            .any { it.toInt() != 0 })
        assertTrue((parts.sweepStartFrame until parts.rightSweepStartFrame).any { frame ->
            pcm[frame * 2].toInt() != 0 && pcm[frame * 2 + 1].toInt() == 0
        })
        assertTrue((parts.rightSweepStartFrame until parts.trailingMarkerStartFrame).any { frame ->
            pcm[frame * 2].toInt() == 0 && pcm[frame * 2 + 1].toInt() != 0
        })
        assertTrue((parts.trailingMarkerStartFrame until parts.trailingMarkerStartFrame + parts.endMarkerFrames).all { frame ->
            pcm[frame * 2 + 1].toInt() == 0
        })
        assertTrue(pcm.slice(parts.trailingMarkerStartFrame * 2 until (parts.totalFrames - 1) * 2)
            .any { it.toInt() != 0 })
        assertTrue(pcm.maxOf { kotlin.math.abs(it.toInt()) } <= 8_300)
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
        val parts = sweep.parts()
        val firstLeftActiveFrame = parts.sweepStartFrame + 5
        val firstRightActiveFrame = parts.rightSweepStartFrame + 5

        assertTrue(left[firstLeftActiveFrame * 2].toInt() != 0)
        assertEquals(0, left[firstLeftActiveFrame * 2 + 1].toInt())
        assertEquals(0, right[firstRightActiveFrame * 2].toInt())
        assertTrue(right[firstRightActiveFrame * 2 + 1].toInt() != 0)
    }

    @Test
    fun markerChannelRoutesBothMarkersToTheConfiguredSpeaker() {
        val sweep = MeasurementSweep(8_000, markerChannel = "right")
        val pcm = MeasurementSweepGenerator.generateStereoPcm(sweep)
        val parts = sweep.parts()
        val markerFrames = listOf(
            parts.leadingMarkerStartFrame + parts.syncMarkerFrames / 2,
            parts.trailingMarkerStartFrame + parts.endMarkerFrames / 2,
        )

        markerFrames.forEach { frame ->
            assertEquals(0, pcm[frame * 2].toInt())
            assertTrue(pcm[frame * 2 + 1].toInt() != 0)
        }
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
    fun markerOnlySweepContainsOnlyTheTwoSharedMarkers() {
        val sweep = MeasurementSweep(8_000, captureKind = "marker-only")
        val pcm = MeasurementSweepGenerator.generateStereoPcm(sweep)
        val parts = sweep.parts()
        val leadingEnd = parts.sweepStartFrame
        val trailingStart = parts.trailingMarkerStartFrame
        val trailingEnd = trailingStart + parts.endMarkerFrames

        assertEquals("marker-only", sweep.captureKind)
        assertTrue((leadingEnd until trailingStart).all { frame ->
            pcm[frame * 2].toInt() == 0 && pcm[frame * 2 + 1].toInt() == 0
        })
        assertTrue((parts.leadingMarkerStartFrame until leadingEnd).any { frame ->
            pcm[frame * 2].toInt() != 0 && pcm[frame * 2 + 1].toInt() == 0
        })
        assertTrue((trailingStart until trailingEnd).any { frame ->
            pcm[frame * 2].toInt() != 0 && pcm[frame * 2 + 1].toInt() == 0
        })
        assertTrue((trailingEnd until parts.totalFrames).all { frame ->
            pcm[frame * 2].toInt() == 0 && pcm[frame * 2 + 1].toInt() == 0
        })
    }

    @Test
    fun productionSpacingMarkerSweepPreservesProductionSeparationWithoutSweeps() {
        val sweep = MeasurementSweep(48_000, captureKind = "marker-production-spacing")
        val pcm = MeasurementSweepGenerator.generateStereoPcm(sweep)
        val parts = sweep.parts()

        assertEquals("marker-production-spacing", sweep.captureKind)
        assertEquals(158_400, parts.trailingMarkerStartFrame - parts.leadingMarkerStartFrame)
        assertTrue((parts.sweepStartFrame until parts.trailingMarkerStartFrame).all { frame ->
            pcm[frame * 2].toInt() == 0 && pcm[frame * 2 + 1].toInt() == 0
        })
        assertTrue((parts.leadingMarkerStartFrame until parts.sweepStartFrame).any { frame ->
            pcm[frame * 2].toInt() != 0 && pcm[frame * 2 + 1].toInt() == 0
        })
        assertTrue((parts.trailingMarkerStartFrame until parts.trailingMarkerStartFrame + parts.endMarkerFrames)
            .any { frame -> pcm[frame * 2].toInt() != 0 && pcm[frame * 2 + 1].toInt() == 0 })
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
        fun string(name: String): String = Regex("\"$name\"\\s*:\\s*\"([^\"]+)\"")
            .find(fixture)
            ?.groupValues
            ?.get(1)
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
            endMarkerStartHz = number("endMarkerStartHz").toFloat(),
            endMarkerEndHz = number("endMarkerEndHz").toFloat(),
            endMarkerDurationMs = number("endMarkerDurationMs").toInt(),
            interSweepGapMs = number("interSweepGapMs").toInt(),
            sweepLevelDbfs = number("sweepLevelDbfs").toFloat(),
            markerLevelDbfs = number("markerLevelDbfs").toFloat(),
            fadeInMs = number("fadeInMs").toInt(),
            fadeOutMs = number("fadeOutMs").toInt(),
            markerChannel = string("markerChannel"),
        )
        assertEquals("android-sweep-v3", string("sweepRevision"))
        assertEquals("left", sweep.markerChannel)
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
