package com.darelisme.sweetspot.calibration.model

import com.darelisme.sweetspot.ui.calibration.CalibrationPositionAssets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MeasurementContextTest {
    private fun target(positionId: String): Triple<Double, Double, Double> = when (positionId) {
        "center" -> Triple(0.0, 0.0, 0.0)
        "left" -> Triple(-35.0, 0.0, 0.0)
        "right" -> Triple(35.0, 0.0, 0.0)
        "forward" -> Triple(0.0, 10.0, 35.0)
        "backward" -> Triple(0.0, -10.0, -35.0)
        else -> Triple(Double.NaN, Double.NaN, Double.NaN)
    }

    private fun context(
        positionId: String = "center",
        positionIndex: Int = 0,
        positionCount: Int = 3,
        repairChannel: String = "both",
        attemptIndex: Int = 0,
        attemptCount: Int = 2,
        phase: String = "measurement",
        captureKind: String = "position-composite",
    ): MeasurementContext {
        val (xCm, yCm, zCm) = target(positionId)
        return MeasurementContext(
            positionId = positionId,
            reference = "center",
            xCm = xCm,
            yCm = yCm,
            zCm = zCm,
            positionIndex = positionIndex,
            positionCount = positionCount,
            channel = "both",
            captureKind = captureKind,
            repairChannel = repairChannel,
            attemptIndex = attemptIndex,
            attemptCount = attemptCount,
            phase = phase,
        )
    }

    @Test
    fun parsesAndLabelsAValidCompositePosition() {
        val value = context(positionId = "right", positionIndex = 2, positionCount = 5, repairChannel = "right")

        assertEquals(true, value.isValid())
        assertEquals("Position 3 of 5", value.label())
    }

    @Test
    fun acceptsTheProductionSpacingMarkerDiagnosticCaptureKind() {
        assertEquals(true, context(captureKind = "marker-production-spacing").isValid())
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
                "Position ready. Keep the phone still.\n" +
                "Press Continue on the TV.",
            value.readyStatus(),
        )
    }

    @Test
    fun centerReadyStatusDoesNotAskForASecondConfirmation() {
        val value = context()

        assertEquals(true, value.readyStatus().contains("The TV will start the measurement"))
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

    @Test
    fun positionInstructionsUseTheOriginalCenterAsTheirReference() {
        val left = context(positionId = "left")
        val right = context(positionId = "right")
        val forward = context(positionId = "forward")
        val backward = context(positionId = "backward")

        assertEquals(true, left.instruction().contains("original center"))
        assertEquals(true, right.instruction().contains("original center"))
        assertEquals(true, right.instruction().contains("70 cm"))
        assertEquals(true, forward.instruction().contains("TOWARD THE TV"))
        assertEquals(true, forward.instruction().contains("original center"))
        assertEquals(true, forward.instruction().contains("10 cm"))
        assertEquals(true, backward.instruction().contains("AWAY FROM THE TV"))
        assertEquals(true, backward.instruction().contains("original center"))
        assertEquals(true, backward.instruction().contains("10 cm"))
    }

    @Test
    fun retryInstructionsTellTheUserToStayAtTheSamePosition() {
        val retry = context(positionId = "right", attemptIndex = 1)

        assertEquals(true, retry.instruction().contains("same right-side position"))
        assertEquals(true, retry.instruction().contains("Do not move"))
    }

    @Test
    fun validationInstructionKeepsTheRequestedPhysicalPosition() {
        val expected = mapOf(
            "center" to "normal listening position",
            "left" to "LEFT",
            "right" to "RIGHT",
            "forward" to "TOWARD THE TV",
            "backward" to "AWAY FROM THE TV",
        )

        expected.forEach { (positionId, phrase) ->
            val validation = context(positionId = positionId, phase = "validation")
            assertEquals(true, validation.instruction().startsWith("VALIDATION"))
            assertEquals(true, validation.instruction().contains(phrase))
        }
    }

    @Test
    fun validationRetryKeepsValidationPrefixAndPositionIdentity() {
        val validation = context(positionId = "right", phase = "validation", attemptIndex = 1)

        assertEquals(true, validation.instruction().startsWith("VALIDATION"))
        assertEquals(true, validation.instruction().contains("same right-side position"))
        assertEquals(true, validation.readyStatus().contains("right position"))
    }

    @Test
    fun positionIdsUseTheExplicitBundledSvgMapping() {
        assertEquals("calibration_position/center.svg", CalibrationPositionAssets.pathFor("center"))
        assertEquals("calibration_position/left.svg", CalibrationPositionAssets.pathFor("left"))
        assertEquals("calibration_position/right.svg", CalibrationPositionAssets.pathFor("right"))
        assertEquals("calibration_position/forward.svg", CalibrationPositionAssets.pathFor("forward"))
        assertEquals("calibration_position/backward.svg", CalibrationPositionAssets.pathFor("backward"))
    }

    @Test
    fun missingOrBrokenPositionArtworkFallsBackWithoutABlockingLoad() {
        assertNull(CalibrationPositionAssets.pathFor("unknown"))
        assertNull(CalibrationPositionAssets.loadOrNull("right") { error("broken SVG") })
    }

    @Test
    fun targetGeometryIsRequiredForAValidContext() {
        val original = context(positionId = "forward")

        assertEquals(0.0, original.xCm, 0.0)
        assertEquals(10.0, original.yCm, 0.0)
        assertEquals(35.0, original.zCm, 0.0)
        assertEquals(false, original.copy(reference = "previous").isValid())
        assertEquals(false, original.copy(xCm = 35.0).isValid())
    }

    @Test
    fun parsesAContextWithoutWireGeometryUsingThePositionIdentity() {
        val parsed = MeasurementContext.fromWire(
            MeasurementContextWire(
                positionId = "center",
                positionIndex = 0,
                positionCount = 3,
                channel = "both",
                captureKind = "position-composite",
                repairChannel = "both",
                attemptIndex = 0,
                attemptCount = 2,
                phase = "measurement",
            ),
        )

        assertEquals(true, parsed?.isValid())
        assertEquals("center", parsed?.reference)
        assertEquals(0.0, parsed?.xCm ?: Double.NaN, 0.0)
        assertEquals(0.0, parsed?.yCm ?: Double.NaN, 0.0)
        assertEquals(0.0, parsed?.zCm ?: Double.NaN, 0.0)
    }

    @Test
    fun rejectsContradictoryWireGeometry() {
        val parsed = MeasurementContext.fromWire(
            MeasurementContextWire(
                positionId = "center",
                positionIndex = 0,
                positionCount = 3,
                channel = "both",
                captureKind = "position-composite",
                repairChannel = "both",
                attemptIndex = 0,
                attemptCount = 2,
                phase = "measurement",
                geometry = MeasurementGeometry("center", 35.0, 0.0, 0.0),
            ),
        )

        assertNull(parsed)
    }
}
