package com.darelisme.sweetspot.calibration.model

import org.json.JSONArray
import org.json.JSONObject

private const val MAX_MEASUREMENT_SESSION_ID_LENGTH = 64

internal data class MeasurementTrace(
    val frequenciesHz: DoubleArray,
    val magnitudesDb: DoubleArray
) {
    init {
        require(frequenciesHz.size == magnitudesDb.size)
        require(frequenciesHz.size in 2..64)
        var previousFrequency = 0.0
        frequenciesHz.forEachIndexed { index, frequency ->
            require(frequency.isFinite() && frequency > 0.0)
            if (index > 0) require(frequency > previousFrequency)
            require(magnitudesDb[index].isFinite())
            previousFrequency = frequency
        }
    }

    override fun equals(other: Any?): Boolean =
        other is MeasurementTrace &&
            frequenciesHz.contentEquals(other.frequenciesHz) &&
            magnitudesDb.contentEquals(other.magnitudesDb)

    override fun hashCode(): Int = 31 * frequenciesHz.contentHashCode() + magnitudesDb.contentHashCode()
}

internal data class MeasurementResponse(
    val sessionId: String,
    val current: Int,
    val total: Int,
    val left: MeasurementTrace?,
    val right: MeasurementTrace?
) {
    init {
        require(isValidMeasurementSessionId(sessionId))
        require(total in 1..256 && current in 0..total)
        require(left != null || right != null)
    }
}

internal object MeasurementResponsePayload {
    fun fromValues(
        sessionId: String,
        current: Int,
        total: Int,
        leftFrequenciesHz: DoubleArray?,
        leftMagnitudesDb: DoubleArray?,
        rightFrequenciesHz: DoubleArray?,
        rightMagnitudesDb: DoubleArray?
    ): MeasurementResponse? = try {
        MeasurementResponse(
            sessionId = sessionId,
            current = current,
            total = total,
            left = traceFromValues(leftFrequenciesHz, leftMagnitudesDb),
            right = traceFromValues(rightFrequenciesHz, rightMagnitudesDb)
        )
    } catch (_: IllegalArgumentException) {
        null
    }

    fun parse(value: JSONObject): MeasurementResponse? = try {
        if (!value.has("sessionId") || !value.has("current") || !value.has("total") ||
            !value.has("left") || !value.has("right")
        ) return null

        val sessionId = value.get("sessionId") as? String ?: return null
        if (!isValidMeasurementSessionId(sessionId)) return null
        val current = jsonInt(value.get("current")) ?: return null
        val total = jsonInt(value.get("total")) ?: return null
        if (total < 1 || current !in 0..total) return null

        val left = if (value.isNull("left")) null else optionalTrace(value, "left")
        val right = if (value.isNull("right")) null else optionalTrace(value, "right")
        fromValues(
            sessionId = sessionId,
            current = current,
            total = total,
            leftFrequenciesHz = left?.frequenciesHz,
            leftMagnitudesDb = left?.magnitudesDb,
            rightFrequenciesHz = right?.frequenciesHz,
            rightMagnitudesDb = right?.magnitudesDb
        )
    } catch (_: Exception) {
        null
    }

    private fun optionalTrace(value: JSONObject, key: String): MeasurementTrace? {
        if (value.isNull(key)) return null
        val channel = value.get(key) as? JSONObject ?: throw IllegalArgumentException("$key is not an object")
        return parseTrace(channel) ?: throw IllegalArgumentException("$key is invalid")
    }

    private fun parseTrace(value: JSONObject): MeasurementTrace? {
        val frequencies = value.get("frequenciesHz") as? JSONArray ?: return null
        val magnitudes = value.get("magnitudesDb") as? JSONArray ?: return null
        if (frequencies.length() !in 2..64 || magnitudes.length() != frequencies.length()) return null

        val frequencyValues = DoubleArray(frequencies.length())
        val magnitudeValues = DoubleArray(magnitudes.length())
        for (index in frequencyValues.indices) {
            frequencyValues[index] = jsonNumber(frequencies.get(index)) ?: return null
            magnitudeValues[index] = jsonNumber(magnitudes.get(index)) ?: return null
        }
        return try {
            MeasurementTrace(frequencyValues, magnitudeValues)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun traceFromValues(
        frequenciesHz: DoubleArray?,
        magnitudesDb: DoubleArray?
    ): MeasurementTrace? {
        if (frequenciesHz == null && magnitudesDb == null) return null
        if (frequenciesHz == null || magnitudesDb == null) throw IllegalArgumentException("incomplete trace")
        return MeasurementTrace(frequenciesHz, magnitudesDb)
    }

    private fun jsonNumber(value: Any): Double? =
        (value as? Number)?.toDouble()?.takeIf { it.isFinite() }

    private fun jsonInt(value: Any): Int? {
        val number = jsonNumber(value) ?: return null
        if (number < Int.MIN_VALUE || number > Int.MAX_VALUE || number != number.toLong().toDouble()) return null
        return number.toInt()
    }
}

internal fun isValidMeasurementSessionId(value: String): Boolean =
    value.isNotBlank() &&
        value.length <= MAX_MEASUREMENT_SESSION_ID_LENGTH &&
        value.none { it.isWhitespace() }

internal fun shouldForwardMeasurementResponse(activeSessionId: String?, response: MeasurementResponse): Boolean =
    activeSessionId != null && activeSessionId == response.sessionId
