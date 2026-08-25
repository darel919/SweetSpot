package com.darelisme.sweetspot

import org.junit.Assert.assertEquals
import org.junit.Test

class MeasurementContextTest {
    private fun context(
        positionId: String = "center",
        positionIndex: Int = 0,
        positionCount: Int = 3,
        repairChannel: String = "both",
        attemptIndex: Int = 0,
        attemptCount: Int = 2,
    ) = MeasurementContext(
        positionId = positionId,
        positionIndex = positionIndex,
        positionCount = positionCount,
        channel = "both",
        captureKind = "position-composite",
        repairChannel = repairChannel,
        attemptIndex = attemptIndex,
        attemptCount = attemptCount,
        phase = "measurement",
    )

    @Test
    fun parsesAndLabelsAValidCompositePosition() {
        val value = context(positionId = "right", positionIndex = 2, positionCount = 5, repairChannel = "right")

        assertEquals(true, value.isValid())
        assertEquals("Position 3 of 5", value.label())
    }

    @Test
    fun rejectsOutOfRangeContext() {
        assertEquals(false, context(positionIndex = 5, positionCount = 5).isValid())
    }

    @Test
    fun readyStatusKeepsInstructionsOnTheTv() {
        val value = context(positionId = "left", positionIndex = 1)

        assertEquals(
            "Position 2 of 3\n" +
                "Move the iPhone about 30–40 cm left. Keep the bottom edge pointed toward the center of the TV.\n" +
                "Keep this orientation, then press Continue on the TV.",
            value.readyStatus(),
        )
    }

    @Test
    fun centerReadyStatusDoesNotAskForASecondConfirmation() {
        val value = context()

        assertEquals(true, value.readyStatus().contains("while the TV starts the measurement"))
        assertEquals(false, value.readyStatus().contains("Continue"))
    }

    @Test
    fun channelRepairAtTheSamePositionDoesNotAskForRemoteConfirmation() {
        val value = context(positionId = "left", positionIndex = 1, repairChannel = "right")

        assertEquals(false, value.requiresRemoteContinue())
        assertEquals(false, value.readyStatus().contains("Continue"))
    }

    @Test
    fun retryOfTheSameCaptureHasTheSamePositionButASeparateOperationIdentity() {
        val original = context(positionId = "left", positionIndex = 1)
        val retry = context(positionId = "left", positionIndex = 1, attemptIndex = 1)

        assertEquals(false, retry.sameCapture(original))
        assertEquals(original.positionId, retry.positionId)
    }

    @Test
    fun rejectsAttemptsBeyondTheSingleRetryBound() {
        assertEquals(false, context(attemptIndex = 2).isValid())
        assertEquals(false, context(attemptCount = 3).isValid())
        assertEquals(false, context(repairChannel = "single").isValid())
    }

    @Test
    fun rejectsAChannelSpecificWireRouteForACompositeCapture() {
        assertEquals(false, context().copy(channel = "left").isValid())
    }
}
