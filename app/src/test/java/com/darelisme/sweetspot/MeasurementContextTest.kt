package com.darelisme.sweetspot

import org.junit.Assert.assertEquals
import org.junit.Test

class MeasurementContextTest {
    @Test
    fun parsesAndLabelsAValidRoutedTake() {
        val context = MeasurementContext("right", 2, 5, "left", 1, 3, 0, 2, "measurement")

        assertEquals(true, context.isValid())
        assertEquals("Position 3 of 5 · left channel · Take 2 of 3", context.label())
    }

    @Test
    fun rejectsOutOfRangeContext() {
        val context = MeasurementContext("center", 5, 5, "right", 0, 3, 0, 2, "measurement")

        assertEquals(false, context.isValid())
    }

    @Test
    fun readyStatusKeepsInstructionsOnTheTv() {
        val context = MeasurementContext("left", 1, 3, "both", 0, 2, 0, 2, "measurement")

        assertEquals(
            "Position 2 of 3 · both channels · Take 1 of 2\n" +
                "Move the iPhone about 20 cm left.\n" +
                "Keep the same bottom-mic orientation, then press Continue on the TV.",
            context.readyStatus(),
        )
    }

    @Test
    fun centerReadyStatusDoesNotAskForASecondConfirmation() {
        val context = MeasurementContext("center", 0, 3, "both", 0, 2, 0, 2, "measurement")

        assertEquals(true, context.readyStatus().contains("while the TV starts the measurement"))
        assertEquals(false, context.readyStatus().contains("Continue"))
    }

    @Test
    fun repeatedTakeAtTheSamePositionDoesNotAskForRemoteConfirmation() {
        val context = MeasurementContext("left", 1, 3, "both", 1, 2, 0, 2, "measurement")

        assertEquals(false, context.requiresRemoteContinue())
        assertEquals(false, context.readyStatus().contains("Continue"))
    }

    @Test
    fun retryOfTheSameLogicalTakeKeepsRemotePositionConfirmation() {
        val original = MeasurementContext("left", 1, 3, "both", 0, 2, 0, 2, "measurement")
        val retry = MeasurementContext("left", 1, 3, "both", 0, 2, 1, 2, "measurement")

        assertEquals(true, retry.sameLogicalTake(original))
    }

    @Test
    fun labelsAReplayAsASeparateRetryWithoutChangingTheLogicalTake() {
        val context = MeasurementContext("center", 0, 1, "left", 0, 2, 1, 2, "measurement")

        assertEquals("Position 1 of 1 · left channel · Take 1 of 2 · Retry 1 of 1", context.label())
        assertEquals(true, context.isValid())
    }

    @Test
    fun rejectsAttemptsBeyondTheSingleRetryBound() {
        assertEquals(false, MeasurementContext("center", 0, 1, "left", 0, 2, 2, 2, "measurement").isValid())
        assertEquals(false, MeasurementContext("center", 0, 1, "left", 0, 2, 0, 3, "measurement").isValid())
        assertEquals(false, MeasurementContext("center", 0, 1, "left", 0, 4, 0, 2, "measurement").isValid())
    }
}
