package com.darelisme.sweetspot

import org.json.JSONObject

/**
 * Identity for one browser-owned acoustic measurement. The TV uses this only
 * to route the sweep and render progress; it never performs DSP policy.
 */
data class MeasurementContext(
    val positionId: String,
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
            positionIndex in 0 until positionCount &&
            positionCount in 1..16 &&
            channel == COMPOSITE_CHANNEL &&
            captureKind == CAPTURE_KIND &&
            repairChannel in CHANNELS &&
            attemptIndex in 0 until attemptCount &&
            attemptCount in 1..2 &&
            phase in PHASES

    fun label(): String =
        "Position ${positionIndex + 1} of $positionCount"

    fun instruction(): String = when (positionId) {
        "center" -> "Hold the iPhone upright at your normal listening position. Point the bottom edge toward the center of the TV."
        "left" -> "Move the iPhone about 30–40 cm left. Keep the bottom edge pointed toward the center of the TV."
        "right" -> "Move the iPhone about 30–40 cm right. Keep the bottom edge pointed toward the center of the TV."
        "forward" -> "Move the iPhone about 30–40 cm forward and slightly up. Keep the bottom edge pointed toward the center of the TV."
        "backward" -> "Move the iPhone about 30–40 cm backward and slightly down. Keep the bottom edge pointed toward the center of the TV."
        else -> "Hold the iPhone at the instructed position."
    }

    fun requiresRemoteContinue(): Boolean = positionIndex > 0 && attemptIndex == 0 && repairChannel == "both"

    fun sameCapture(other: MeasurementContext?): Boolean = other != null &&
        positionId == other.positionId &&
        positionIndex == other.positionIndex &&
        positionCount == other.positionCount &&
        channel == other.channel &&
        captureKind == other.captureKind &&
        repairChannel == other.repairChannel &&
        attemptIndex == other.attemptIndex &&
        attemptCount == other.attemptCount &&
        phase == other.phase

    fun readyStatus(): String = "${label()}\n${instruction()}\n" + if (!requiresRemoteContinue()) {
        "Keep this orientation while the TV starts the measurement."
    } else {
        "Keep this orientation, then press Continue on the TV."
    }

    fun toJson(): JSONObject = JSONObject()
        .put("positionId", positionId)
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
        private const val CAPTURE_KIND = "position-composite"
        private const val COMPOSITE_CHANNEL = "both"

        fun fromJson(value: JSONObject?): MeasurementContext? {
            if (value == null) return null
            val context = MeasurementContext(
                positionId = value.optString("positionId"),
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
