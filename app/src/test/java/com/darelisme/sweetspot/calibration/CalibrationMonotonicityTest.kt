package com.darelisme.sweetspot.calibration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CalibrationMonotonicityTest {
    @Test
    fun threePositionBoundedSolutionSurvivesOptionalRejection() {
        val machine = CalibrationStateMachine()
        var job = CalibrationJob.new(
            id = CalibrationJobId("job-1"),
            createdAtMs = 1L,
            analyzerRevision = AnalyzerRevision("android-response-v1"),
            sweepRevision = SweepRevision("android-sweep-v3"),
        )

        listOf(
            CalibrationPosition.CENTER,
            CalibrationPosition.LEFT,
            CalibrationPosition.RIGHT,
        ).forEachIndexed { index, position ->
            job = machine.reduce(job, accepted(position, CaptureChannel.LEFT, index * 2)).job
            job = machine.reduce(job, accepted(position, CaptureChannel.RIGHT, index * 2 + 1)).job
        }

        val usable = job.usability as CalibrationUsability.Usable
        assertEquals(UsabilityGrade.BOUNDED_USABLE, usable.grade)
        assertEquals(
            setOf(CalibrationPosition.CENTER, CalibrationPosition.LEFT, CalibrationPosition.RIGHT),
            usable.best.sourcePositions,
        )

        job = machine.reduce(
            job,
            CalibrationEvent.CaptureRejected(
                request = CaptureRequest(
                    captureId = CaptureId("backward-0"),
                    position = CalibrationPosition.BACKWARD,
                    channel = CaptureChannel.LEFT,
                    attemptIndex = 0,
                    optional = true,
                ),
                reason = CaptureRejectionReason.DIRECT_ARRIVAL_WEAK,
            ),
        ).job

        val retained = job.usability as CalibrationUsability.Usable
        assertEquals(usable.best.id, retained.best.id)
        assertEquals(UsabilityGrade.BOUNDED_USABLE, retained.grade)
        assertTrue(job.ledger.rejectedAttempts.any { it.request.position == CalibrationPosition.BACKWARD })
    }

    private fun accepted(
        position: CalibrationPosition,
        channel: CaptureChannel,
        attemptIndex: Int,
    ) = CalibrationEvent.ChannelAccepted(
        AcceptedChannelEvidence(
            request = CaptureRequest(
                captureId = CaptureId("${position.name.lowercase()}-${channel.name.lowercase()}"),
                position = position,
                channel = channel,
                attemptIndex = attemptIndex,
                optional = position.optional,
            ),
            responseDb = BandCurve.of(FloatArray(CalibrationBandGrid.BAND_COUNT)),
            quality = CaptureQuality(snrDb = 30f, markerConfidence = 0.95f, directArrivalConfidence = 0.95f),
        ),
    )
}
