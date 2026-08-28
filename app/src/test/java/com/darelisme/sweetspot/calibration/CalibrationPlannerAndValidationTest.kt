package com.darelisme.sweetspot.calibration

import com.darelisme.sweetspot.calibration.analysis.CalibrationValidationFallbackPolicy
import com.darelisme.sweetspot.calibration.model.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CalibrationPlannerAndValidationTest {
    private val machine = CalibrationStateMachine()

    @Test
    fun plannerRequestsCenterThenLeftThenRight() {
        var job = CalibrationTestFixtures.newJob()
        assertCapture(job, CalibrationPosition.CENTER, CaptureChannel.LEFT)

        job = machine.reduce(job, CalibrationTestFixtures.accepted(CalibrationPosition.CENTER, CaptureChannel.LEFT)).job
        assertCapture(job, CalibrationPosition.CENTER, CaptureChannel.RIGHT)
        job = machine.reduce(job, CalibrationTestFixtures.accepted(CalibrationPosition.CENTER, CaptureChannel.RIGHT)).job
        assertCapture(job, CalibrationPosition.LEFT, CaptureChannel.LEFT)
        job = machine.reduce(job, CalibrationTestFixtures.accepted(CalibrationPosition.LEFT, CaptureChannel.LEFT)).job
        job = machine.reduce(job, CalibrationTestFixtures.accepted(CalibrationPosition.LEFT, CaptureChannel.RIGHT)).job
        assertCapture(job, CalibrationPosition.RIGHT, CaptureChannel.LEFT)
    }

    @Test
    fun exhaustedOptionalPositionProceedsWithTheExistingBest() {
        var job = CalibrationTestFixtures.usableJob(machine)
        val best = (job.usability as CalibrationUsability.Usable).best
        CaptureChannel.entries.forEach { channel ->
            repeat(2) { attempt ->
                job = machine.reduce(
                    job,
                    CalibrationEvent.CaptureRejected(
                        request(CalibrationPosition.FORWARD, channel, attempt),
                        CaptureRejectionReason.DIRECT_ARRIVAL_WEAK,
                    ),
                ).job
            }
        }

        val next = job.nextAction as CalibrationAction.Capture
        assertEquals(CalibrationPosition.BACKWARD, next.request.position)
        assertEquals(best.id, job.usability.best.id)
    }

    @Test
    fun advancedModeContinuesOptionalScanAfterSufficientSolution() {
        var job = CalibrationJob.new(
            id = CalibrationJobId("advanced-job"),
            createdAtMs = 1L,
            analyzerRevision = AnalyzerRevision("android-response-v1"),
            sweepRevision = SweepRevision("android-sweep-v3"),
            mode = CalibrationJobMode.ADVANCED,
        )
        PositionLedger.MANDATORY_POSITIONS.sortedBy { it.ordinal }.forEach { position ->
            job = machine.reduce(job, CalibrationTestFixtures.accepted(position, CaptureChannel.LEFT)).job
            job = machine.reduce(job, CalibrationTestFixtures.accepted(position, CaptureChannel.RIGHT)).job
        }
        job = machine.reduce(job, CalibrationTestFixtures.accepted(CalibrationPosition.FORWARD, CaptureChannel.LEFT)).job
        job = machine.reduce(job, CalibrationTestFixtures.accepted(CalibrationPosition.FORWARD, CaptureChannel.RIGHT)).job

        assertEquals(UsabilityGrade.SUFFICIENT, (job.usability as CalibrationUsability.Usable).grade)
        assertEquals(CalibrationPhase.Refining, job.phase)
        assertCapture(job, CalibrationPosition.BACKWARD, CaptureChannel.LEFT)
    }

    @Test
    fun validationFallbackOrderEndsByRestoringThePreviousCalibration() {
        assertEquals(
            CorrectionMode.GENTLE,
            CalibrationValidationFallbackPolicy.nextModeAfterWorse(CorrectionMode.NORMAL),
        )
        assertEquals(
            CorrectionMode.RESTRICTED_BAND,
            CalibrationValidationFallbackPolicy.nextModeAfterWorse(CorrectionMode.GENTLE),
        )
        assertEquals(
            null,
            CalibrationValidationFallbackPolicy.nextModeAfterWorse(CorrectionMode.RESTRICTED_BAND),
        )
    }

    @Test
    fun improvedValidationCompletesWithThePersistedBest() {
        val usable = CalibrationTestFixtures.usableJob(machine)
        val best = (usable.usability as CalibrationUsability.Usable).best
        val validating = machine.reduce(
            usable,
            CalibrationEvent.CandidateStaged(
                CalibrationCandidateState(
                    CandidateId("candidate-improved"),
                    best.id,
                    CorrectionMode.NORMAL,
                    0,
                ),
            ),
        ).job

        val complete = machine.reduce(
            validating,
            CalibrationEvent.ValidationClassified(ValidationOutcome.IMPROVED),
        ).job

        assertEquals(CalibrationPhase.Complete, complete.phase)
        assertEquals(best.id, (complete.nextAction as CalibrationAction.Complete).solutionId)
        assertTrue(complete.validationHistory.last().outcome == ValidationOutcome.IMPROVED)
    }

    private fun assertCapture(
        job: CalibrationJob,
        position: CalibrationPosition,
        channel: CaptureChannel,
    ) {
        val capture = job.nextAction as CalibrationAction.Capture
        assertEquals(position, capture.request.position)
        assertEquals(channel, capture.request.channel)
    }

    private fun request(
        position: CalibrationPosition,
        channel: CaptureChannel,
        attempt: Int,
    ) = CaptureRequest(
        CaptureId("reject-${position.name}-${channel.name}-$attempt"),
        position,
        channel,
        attempt,
        position.optional,
    )
}
