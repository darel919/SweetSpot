package com.darelisme.sweetspot.service

import com.darelisme.sweetspot.audio.diagnostics.DynamicsProcessingProbe
import com.darelisme.sweetspot.audio.engine.DynamicsProcessingEq
import org.json.JSONArray

internal fun parseStrictCalibrationArray(value: JSONArray?): FloatArray? {
    if (value == null || value.length() != DynamicsProcessingEq.INTERNAL_BANDS) return null
    return try {
        FloatArray(DynamicsProcessingEq.INTERNAL_BANDS) { index ->
            val parsed = value.getDouble(index)
            require(parsed.isFinite())
            parsed.toFloat().also { require(it.isFinite()) }
        }
    } catch (_: Throwable) {
        null
    }
}

internal fun parseStrictProbeArray(value: JSONArray?, expectedBands: Int): FloatArray? {
    if (value == null || expectedBands <= 0 || value.length() != expectedBands) return null
    return try {
        FloatArray(expectedBands) { index ->
            val parsed = value.getDouble(index)
            require(parsed.isFinite())
            parsed.toFloat().also {
                require(it.isFinite())
                require(it >= DynamicsProcessingProbe.MIN_PROBE_GAIN_DB)
                require(it <= DynamicsProcessingProbe.MAX_PROBE_GAIN_DB)
            }
        }
    } catch (_: Throwable) {
        null
    }
}
