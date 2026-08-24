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
    val takeIndex: Int,
    val takeCount: Int,
    val phase: String
) {
    fun isValid(): Boolean =
        positionId in POSITION_IDS &&
            positionIndex in 0 until positionCount &&
            positionCount in 1..16 &&
            channel in CHANNELS &&
            takeIndex in 0 until takeCount &&
            takeCount in 1..8 &&
            phase in PHASES

    fun label(): String =
        "Position ${positionIndex + 1} of $positionCount · ${channelLabel(channel)} · " +
            "take ${takeIndex + 1} of $takeCount"

    fun instruction(): String = when (positionId) {
        "center" -> "Hold the iPhone at your normal listening position."
        "left" -> "Move the iPhone about 20 cm left."
        "right" -> "Move the iPhone about 20 cm right."
        "forward" -> "Move the iPhone about 20 cm forward and slightly up."
        "backward" -> "Move the iPhone about 20 cm backward and slightly down."
        else -> "Hold the iPhone at the instructed position."
    }

    fun readyStatus(): String = "${label()}\n${instruction()}\nKeep the same bottom-mic orientation, then continue on the phone."

    fun toJson(): JSONObject = JSONObject()
        .put("positionId", positionId)
        .put("positionIndex", positionIndex)
        .put("positionCount", positionCount)
        .put("channel", channel)
        .put("takeIndex", takeIndex)
        .put("takeCount", takeCount)
        .put("phase", phase)

    companion object {
        private val POSITION_IDS = setOf("center", "left", "right", "forward", "backward")
        private val CHANNELS = setOf("both", "left", "right")
        private val PHASES = setOf("measurement", "validation")

        fun fromJson(value: JSONObject?): MeasurementContext? {
            if (value == null) return null
            val context = MeasurementContext(
                positionId = value.optString("positionId"),
                positionIndex = value.optInt("positionIndex", -1),
                positionCount = value.optInt("positionCount", -1),
                channel = value.optString("channel"),
                takeIndex = value.optInt("takeIndex", -1),
                takeCount = value.optInt("takeCount", -1),
                phase = value.optString("phase")
            )
            return context.takeIf { it.isValid() }
        }

        private fun channelLabel(channel: String): String = when (channel) {
            "left" -> "left channel"
            "right" -> "right channel"
            else -> "both channels"
        }
    }
}
