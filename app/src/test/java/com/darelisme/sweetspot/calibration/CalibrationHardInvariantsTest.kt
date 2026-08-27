package com.darelisme.sweetspot.calibration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CalibrationHardInvariantsTest {
    private val machine = CalibrationStateMachine()

    @Test
    fun optionalFailureCannotRemoveMinimumViableStateOrBestSolution() {
        val before = CalibrationTestFixtures.usableJob(machine)
        val best = (before.usability as CalibrationUsability.Usable).best

        val after = machine.reduce(
            before,
            CalibrationEvent.CaptureRejected(
                request(CalibrationPosition.FORWARD, CaptureChannel.LEFT, 0),
                CaptureRejectionReason.MARKER_UNRELIABLE,
            ),
        ).job

        val usable = after.usability as CalibrationUsability.Usable
        assertSame(best, usable.best)
        assertEquals(best.id, usable.best.id)
    }

    @Test
    fun solutionsReferenceOnlyCompleteAcceptedPositions() {
        val job = CalibrationTestFixtures.usableJob(machine)
        val best = (job.usability as CalibrationUsability.Usable).best

        assertTrue(job.ledger.solutionSourcesAreAccepted(best))
        assertEquals(PositionLedger.MANDATORY_POSITIONS, best.sourcePositions)
    }

    @Test
    fun partialPositionCannotEnterStereoAggregation() {
        val ledger = PositionLedger.empty().recordAccepted(
            CalibrationTestFixtures.accepted(
                CalibrationPosition.CENTER,
                CaptureChannel.LEFT,
            ).evidence,
        )

        assertNull(ledger.complete(CalibrationPosition.CENTER))
        assertTrue(ledger.completePositions.isEmpty())
    }

    @Test
    fun browserCommandsCannotCreateEvidenceOrValidationOutcomes() {
        val job = CalibrationTestFixtures.usableJob(machine)
        val commands = listOf(
            BrowserCalibrationCommand.CancelCapture(CaptureId("not-active")),
            BrowserCalibrationCommand.CancelOptionalRefinement,
            BrowserCalibrationCommand.FinishWithBest,
        )

        commands.forEach { command ->
            val after = machine.handleBrowserCommand(job, command).job
            assertEquals(job.ledger, after.ledger)
            assertEquals(job.validationHistory, after.validationHistory)
            assertEquals(job.usability, after.usability)
        }
    }

    @Test
    fun browserDisconnectDoesNotOwnJobLifetime() {
        val job = CalibrationTestFixtures.usableJob(machine)

        val transition = machine.reduce(job, CalibrationEvent.BrowserDisconnected)

        assertSame(job, transition.job)
        assertTrue(transition.effects.isEmpty())
    }

    @Test
    fun acceptedEvidenceIsInsidePersistedSnapshotBeforeNextActionPublishes() {
        val job = CalibrationTestFixtures.newJob()
        val transition = machine.reduce(
            job,
            CalibrationTestFixtures.accepted(CalibrationPosition.CENTER, CaptureChannel.LEFT),
        )

        val persisted = transition.effects[0] as CalibrationEffect.Persist
        assertEquals(1, persisted.job.ledger.attempts.size)
        assertTrue(transition.effects[1] is CalibrationEffect.Publish)
        assertEquals(persisted.job, transition.job)
    }

    @Test
    fun worseValidationPersistsRollbackIntentBeforeExecution() {
        val usableJob = CalibrationTestFixtures.usableJob(machine)
        val solution = (usableJob.usability as CalibrationUsability.Usable).best
        val candidate = CalibrationCandidateState(
            CandidateId("candidate-1"),
            solution.id,
            CorrectionMode.NORMAL,
            validationAttemptIndex = 0,
        )
        val validating = machine.reduce(
            usableJob,
            CalibrationEvent.CandidateStaged(candidate),
        ).job

        val transition = machine.reduce(
            validating,
            CalibrationEvent.ValidationClassified(ValidationOutcome.WORSE),
        )

        val persisted = transition.effects[0] as CalibrationEffect.Persist
        assertTrue(persisted.job.pendingEffect is PendingCalibrationEffect.RollbackThenReoptimize)
        assertTrue(transition.effects[1] is CalibrationEffect.Execute)
        assertEquals(CalibrationPhase.Restoring, transition.job.phase)
        assertEquals(usableJob.ledger, transition.job.ledger)
    }

    @Test
    fun optionalFailureChangesHistoryAndPlanningButNotAcceptedEvidence() {
        val before = CalibrationTestFixtures.usableJob(machine)
        val acceptedBefore = before.ledger.completePositions

        val after = machine.reduce(
            before,
            CalibrationEvent.CaptureRejected(
                request(CalibrationPosition.FORWARD, CaptureChannel.LEFT, 0),
                CaptureRejectionReason.DIRECT_ARRIVAL_WEAK,
            ),
        ).job

        assertEquals(acceptedBefore, after.ledger.completePositions)
        assertEquals(before.usability, after.usability)
        assertEquals(before.ledger.attempts.size + 1, after.ledger.attempts.size)
    }

    @Test
    fun validationCaptureFailureRetriesCenterWithoutChangingRoomEvidence() {
        val usableJob = CalibrationTestFixtures.usableJob(machine)
        val solution = (usableJob.usability as CalibrationUsability.Usable).best
        val validating = machine.reduce(
            usableJob,
            CalibrationEvent.CandidateStaged(
                CalibrationCandidateState(
                    CandidateId("candidate-2"),
                    solution.id,
                    CorrectionMode.NORMAL,
                    validationAttemptIndex = 0,
                ),
            ),
        ).job

        val after = machine.reduce(
            validating,
            CalibrationEvent.ValidationClassified(ValidationOutcome.INCONCLUSIVE_CAPTURE),
        ).job

        assertEquals(usableJob.ledger, after.ledger)
        assertEquals(usableJob.usability, after.usability)
        val retry = after.nextAction as CalibrationAction.Validate
        assertEquals(CalibrationPosition.CENTER, retry.position)
        assertEquals(1, retry.attemptIndex)
        assertFalse(after.phase is CalibrationPhase.MeasuringRequired)
    }

    @Test
    fun reconstructedSnapshotRetainsAcceptedEvidence() {
        val beforeRestart = CalibrationTestFixtures.usableJob(machine)

        val restored = beforeRestart.copy()

        assertEquals(beforeRestart.ledger, restored.ledger)
        assertEquals(beforeRestart.usability, restored.usability)
    }

    private fun request(
        position: CalibrationPosition,
        channel: CaptureChannel,
        attemptIndex: Int,
    ) = CaptureRequest(
        captureId = CaptureId("reject-${position.name.lowercase()}-${channel.name.lowercase()}-$attemptIndex"),
        position = position,
        channel = channel,
        attemptIndex = attemptIndex,
        optional = position.optional,
    )
}
