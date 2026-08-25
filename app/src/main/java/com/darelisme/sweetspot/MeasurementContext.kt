package com.darelisme.sweetspot

import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Identity for one browser-owned acoustic measurement. The TV uses this only
 * to route the sweep and render progress; it never performs DSP policy.
 */
data class MeasurementContext(
    val positionId: String,
    val reference: String,
    val xCm: Double,
    val yCm: Double,
    val zCm: Double,
    val positionIndex: Int,
    val positionCount: Int,
    val channel: String,
    val captureKind: String,
    val repairChannel: String,
    val attemptIndex: Int,
    val attemptCount: Int,
    val phase: String
) {
    fun isValid(): Boolean =
        positionId in POSITION_IDS &&
            reference == REFERENCE_CENTER &&
            xCm.isFinite() &&
            yCm.isFinite() &&
            zCm.isFinite() &&
            hasPositionAxes() &&
            positionCount in 1..16 &&
            positionIndex in 0 until positionCount &&
            channel == COMPOSITE_CHANNEL &&
            captureKind == CAPTURE_KIND &&
            repairChannel in CHANNELS &&
            attemptIndex in 0 until attemptCount &&
            attemptCount in 1..2 &&
            phase in PHASES

    fun label(): String =
        "Position ${positionIndex + 1} of $positionCount"

    fun positionTitle(): String = when (positionId) {
        "center" -> "CENTER POSITION"
        "left" -> "LEFT POSITION"
        "right" -> "RIGHT POSITION"
        "forward" -> "FORWARD POSITION"
        "backward" -> "BACK POSITION"
        else -> "POSITION"
    }

    fun assetPath(): String? = CalibrationPositionAssets.pathFor(positionId)

    fun instruction(): String {
        if (attemptIndex > 0) return retryInstruction()
        if (phase == "validation") {
            return "VALIDATION\n" +
                "Return the iPhone to the ORIGINAL CENTER listening position used at the start.\n" +
                "Use the same height and orientation as the first measurement."
        }

        val horizontalCm = distance(xCm)
        val verticalCm = distance(yCm)
        val depthCm = distance(zCm)
        return when (positionId) {
            "center" -> "Hold the iPhone at your normal listening position.\n" +
                "This is the original center reference point for every measurement.\n" +
                "Keep it upright and point the bottom edge toward the center of the TV."
            "left" -> "From the original center position, place the iPhone about $horizontalCm to the LEFT.\n" +
                "Keep the same height as the center position.\n" +
                "Keep the phone upright and point the bottom edge toward the center of the TV."
            "right" -> "From the original center position, place the iPhone about $horizontalCm to the RIGHT.\n" +
                "If you just measured LEFT, this is about 70 cm across from that position.\n" +
                "Keep the same height as the center position.\n" +
                "Keep the phone upright and point the bottom edge toward the center of the TV."
            "forward" -> "Return to the original center position.\n" +
                "Then place the iPhone about $depthCm TOWARD THE TV and about $verticalCm higher than the center position.\n" +
                "Do not stay on the right side.\n" +
                "Keep the phone upright and point the bottom edge toward the center of the TV."
            "backward" -> "Return to the original center position.\n" +
                "Then place the iPhone about $depthCm AWAY FROM THE TV and about $verticalCm lower than the center position.\n" +
                "Do not measure relative to the previous forward position.\n" +
                "Keep the phone upright and point the bottom edge toward the center of the TV."
            else -> "Hold the iPhone at the instructed position."
        }
    }

    private fun retryInstruction(): String =
        "RETRY — ${positionTitle()}\n" +
            "Keep the iPhone at the same ${retryPositionLabel()} position.\n" +
            "Do not move it.\n" +
            "Keep the phone upright and point the bottom edge toward the center of the TV."

    private fun retryPositionLabel(): String = when (positionId) {
        "center" -> "center"
        "left" -> "left-side"
        "right" -> "right-side"
        "forward" -> "forward"
        "backward" -> "back"
        else -> "target"
    }

    private fun distance(value: Double): String = "${abs(value).roundToInt()} cm"

    private fun hasPositionAxes(): Boolean = when (positionId) {
        "center" -> xCm == 0.0 && yCm == 0.0 && zCm == 0.0
        "left" -> xCm < 0.0 && yCm == 0.0 && zCm == 0.0
        "right" -> xCm > 0.0 && yCm == 0.0 && zCm == 0.0
        "forward" -> xCm == 0.0 && yCm > 0.0 && zCm > 0.0
        "backward" -> xCm == 0.0 && yCm < 0.0 && zCm < 0.0
        else -> false
    }

    fun requiresRemoteContinue(): Boolean = positionIndex > 0 && attemptIndex == 0 && repairChannel == "both"

    fun sameCapture(other: MeasurementContext?): Boolean = other != null &&
        positionId == other.positionId &&
        reference == other.reference &&
        xCm == other.xCm &&
        yCm == other.yCm &&
        zCm == other.zCm &&
        positionIndex == other.positionIndex &&
        positionCount == other.positionCount &&
        channel == other.channel &&
        captureKind == other.captureKind &&
        repairChannel == other.repairChannel &&
        attemptIndex == other.attemptIndex &&
        attemptCount == other.attemptCount &&
        phase == other.phase

    fun readyStatus(): String {
        val state = when {
            phase == "validation" -> "Validation ready. Return to the original center position."
            attemptIndex > 0 -> "Retry ready. Keep the phone here."
            else -> "Position ready. Keep the phone still."
        }
        val action = if (!requiresRemoteContinue()) {
            "The TV will start the measurement."
        } else {
            "Press Continue on the TV."
        }
        return "${label()}\n$state\n$action"
    }

    fun toJson(): JSONObject = JSONObject()
        .put("positionId", positionId)
        .put("reference", reference)
        .put("xCm", xCm)
        .put("yCm", yCm)
        .put("zCm", zCm)
        .put("positionIndex", positionIndex)
        .put("positionCount", positionCount)
        .put("channel", channel)
        .put("captureKind", captureKind)
        .put("repairChannel", repairChannel)
        .put("attemptIndex", attemptIndex)
        .put("attemptCount", attemptCount)
        .put("phase", phase)

    companion object {
        private val POSITION_IDS = setOf("center", "left", "right", "forward", "backward")
        private val CHANNELS = setOf("both", "left", "right")
        private val PHASES = setOf("measurement", "validation")
        private const val REFERENCE_CENTER = "center"
        private const val CAPTURE_KIND = "position-composite"
        private const val COMPOSITE_CHANNEL = "both"

        fun fromJson(value: JSONObject?): MeasurementContext? {
            if (value == null) return null
            val context = MeasurementContext(
                positionId = value.optString("positionId"),
                reference = value.optString("reference"),
                xCm = value.optDouble("xCm", Double.NaN),
                yCm = value.optDouble("yCm", Double.NaN),
                zCm = value.optDouble("zCm", Double.NaN),
                positionIndex = value.optInt("positionIndex", -1),
                positionCount = value.optInt("positionCount", -1),
                channel = value.optString("channel"),
                captureKind = value.optString("captureKind"),
                repairChannel = value.optString("repairChannel"),
                attemptIndex = value.optInt("attemptIndex", -1),
                attemptCount = value.optInt("attemptCount", -1),
                phase = value.optString("phase")
            )
            return context.takeIf { it.isValid() }
        }
    }
}
