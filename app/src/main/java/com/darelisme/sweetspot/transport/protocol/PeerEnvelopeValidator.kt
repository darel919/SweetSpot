package com.darelisme.sweetspot.transport.protocol

import org.json.JSONObject
import java.nio.charset.StandardCharsets
import kotlin.math.floor

internal object PeerEnvelopeValidator {
    private const val VERSION = 1
    private const val MAX_ID_LENGTH = 256
    private const val MAX_TYPE_LENGTH = 128
    private const val MAX_SESSION_ID_LENGTH = 128
    private const val MAX_REPLY_TO_LENGTH = 256
    private const val MAX_EXPIRY_AFTER_TIMESTAMP_MS = 120_000.0
    private const val MAX_CONTROL_BYTES = 16 * 1024

    private val clientToDeviceTypes = setOf(
        "state.get",
        "engine.enable",
        "engine.bypass",
        "engine.setBands",
        "engine.applyPreset",
        "virtualizer.on",
        "virtualizer.off",
        "profile.list",
        "profile.save",
        "profile.load",
        "profile.delete",
        "calibration.get",
        "calibration.reset",
        "calibration.export",
        "calibration.import",
        "calibration.job.start",
        "calibration.job.get",
        "calibration.job.cancel",
        "calibration.job.discard",
        "calibration.job.finish",
        "calibration.capture.ready",
        "calibration.validation.capture.ready",
        "calibrationSession.begin",
        "diagnostics.calibrationSession.end",
        "calibrationSession.abort",
        "calibrationSession.loudness.start",
        "calibrationSession.loudness.stop",
        "calibrationSession.progress",
        "measurement.prepare",
        "measurement.playSweep",
        "measurement.abort",
        "measurement.diagnostics",
        "measurement.response",
        "probe.run",
        "probe.status",
        "probe.persistent.start",
        "probe.persistent.release",
        "probe.curve.apply",
        "diagnostics.deviceInfo",
        "diagnostics.effects",
        "diagnostics.transport",
        "ping",
    )

    private val emptyPayloadTypes = setOf(
        "ping",
        "state.get",
        "engine.enable",
        "engine.bypass",
        "virtualizer.on",
        "virtualizer.off",
        "profile.list",
        "calibration.get",
        "calibration.reset",
        "calibration.export",
        "probe.status",
        "probe.persistent.release",
        "diagnostics.deviceInfo",
        "diagnostics.effects",
        "diagnostics.transport",
    )

    private val calibrationErrorCodes = setOf(
        "audio_focus_denied",
        "audio_focus_lost",
        "calibration_ui_failed",
        "calibration_ui_closed",
        "measurement_timeout",
        "sweep_playback_failed",
        "invalid_session",
        "already_measuring",
        "capture_clipped",
        "capture_too_short",
        "capture_sample_rate_changed",
        "sync_marker_not_found",
        "clock_drift_unreliable",
        "signal_too_low",
        "measurement_unstable",
        "direct_arrival_low_confidence",
        "impulse_not_found",
        "response_not_generated",
        "dsp_state_unverified",
        "dsp_restore_failed",
        "candidate_rollback_failed",
        "calibration_aborted",
    )

    private val measurementCaptureKinds = setOf(
        "position-composite",
        "marker-only",
        "marker-production-spacing",
    )

    private val measurementPositions = mapOf(
        "center" to doubleArrayOf(0.0, 0.0, 0.0),
        "left" to doubleArrayOf(-35.0, 0.0, 0.0),
        "right" to doubleArrayOf(35.0, 0.0, 0.0),
        "forward" to doubleArrayOf(0.0, 10.0, 35.0),
        "backward" to doubleArrayOf(0.0, -10.0, -35.0),
    )

    /** Validates client command payloads before they cross into service/domain code. */
    fun validateClientPayload(type: String, payload: JSONObject): String? {
        if (type !in clientToDeviceTypes) return null
        val valid = when {
            type in emptyPayloadTypes -> isEmpty(payload)
            type == "calibration.job.start" -> optionalText(payload, "mode", 16)
                && (!payload.has("mode") || payload.optString("mode") in setOf("auto", "advanced"))
            type == "calibration.job.get" -> optionalText(payload, "jobId", 128)
            type == "calibration.job.cancel" -> isJobCancel(payload)
            type == "calibration.job.discard" || type == "calibration.job.finish" -> isJobId(payload)
            type == "calibration.capture.ready" -> isCaptureId(payload)
            type == "calibration.validation.capture.ready" -> isCaptureId(payload) && boundedText(payload.opt("candidateId"), 128)
            type == "engine.applyPreset" -> exactInt(payload.opt("preset"), 0, 128)
            type == "engine.setBands" -> isDbArray(payload.optJSONArray("bandsDb"), 24, -15.0, 15.0)
            type == "profile.save" || type == "profile.load" || type == "profile.delete" ->
                boundedText(payload.opt("name"), 128) && payload.optString("name").isNotBlank()
            type == "calibration.import" -> true
            type == "calibrationSession.begin" -> isSessionWithChannel(payload)
            type == "diagnostics.calibrationSession.end" -> isSession(payload) && isOutcome(payload.opt("outcome"))
            type == "calibrationSession.abort" -> isAbort(payload)
            type == "calibrationSession.loudness.start" || type == "calibrationSession.loudness.stop" -> isSession(payload)
            type == "calibrationSession.progress" -> isProgress(payload)
            type == "measurement.prepare" -> isSessionWithChannel(payload) && optionalContext(payload)
            type == "measurement.playSweep" -> isSession(payload) && optionalContext(payload)
            type == "measurement.abort" -> isSession(payload)
            type == "measurement.diagnostics" -> isMeasurementDiagnostics(payload)
            type == "measurement.response" -> isMeasurementResponse(payload)
            type == "probe.run" -> exactInt(payload.opt("bands"), 1, 128)
            type == "probe.persistent.start" -> exactInt(payload.opt("bands"), 64, 64)
            type == "probe.curve.apply" -> isProbeCurve(payload)
            else -> false
        }
        return if (valid) null else "$type contains an invalid payload"
    }

    fun isValid(value: JSONObject, sessionId: String): Boolean {
        val version = value.opt("v") as? Number
        val id = value.opt("id") as? String
        val type = value.opt("type") as? String
        val timestamp = value.opt("ts") as? Number
        val transportSessionId = value.opt("transportSessionId") as? String
        val payload = value.opt("payload") as? JSONObject
        if (value.toString().toByteArray(StandardCharsets.UTF_8).size > MAX_CONTROL_BYTES) return false
        if (version == null || version.toDouble() != VERSION.toDouble()
            || id.isNullOrBlank() || id.length > MAX_ID_LENGTH
            || type.isNullOrBlank() || type.length > MAX_TYPE_LENGTH || type !in clientToDeviceTypes
            || timestamp == null || !timestamp.toDouble().isFinite()
            || transportSessionId != sessionId || transportSessionId.length > MAX_SESSION_ID_LENGTH
            || payload == null
        ) return false

        val replyTo = if (value.has("replyTo")) value.opt("replyTo") as? String else null
        if (value.has("replyTo") && (replyTo == null || replyTo.length > MAX_REPLY_TO_LENGTH)) return false

        val timestampMs = timestamp.toDouble()
        val nowMs = System.currentTimeMillis().toDouble()
        if (timestampMs > nowMs + MAX_EXPIRY_AFTER_TIMESTAMP_MS) return false
        if (!value.has("expiresAt")) return true
        val expiresAt = value.opt("expiresAt") as? Number ?: return false
        val expiryMs = expiresAt.toDouble()
        return expiryMs.isFinite()
            && expiryMs > timestampMs
            && expiryMs <= timestampMs + MAX_EXPIRY_AFTER_TIMESTAMP_MS
    }

    private fun isEmpty(value: JSONObject): Boolean = !value.keys().hasNext()

    private fun isJobId(value: JSONObject): Boolean = boundedText(value.opt("jobId"), 128)

    private fun isCaptureId(value: JSONObject): Boolean = isJobId(value)
        && boundedText(value.opt("captureId"), 128)
        && captureAttempt(value)

    private fun isJobCancel(value: JSONObject): Boolean {
        if (!isJobId(value)) return false
        return when (value.opt("scope")) {
            "capture" -> boundedText(value.opt("captureId"), 128) && captureAttempt(value, optional = true)
            "optional_refinement" -> !value.has("captureId")
            else -> false
        }
    }

    private fun captureAttempt(value: JSONObject, optional: Boolean = false): Boolean {
        if (!value.has("captureAttemptId")) return optional
        val attempt = value.opt("captureAttemptId") as? String ?: return false
        return Regex("^[A-Za-z0-9_-]{1,128}$").matches(attempt)
    }

    private fun isSession(value: JSONObject): Boolean = sessionId(value.opt("sessionId"))
        && optionalEnum(value, "channel", setOf("both", "left", "right"))
        && optionalEnum(value, "phase", setOf("measurement", "validation"))

    private fun isSessionWithChannel(value: JSONObject): Boolean {
        if (!isSession(value) || value.opt("channel") !in setOf("both", "left", "right")) return false
        val phase = value.opt("phase") ?: "measurement"
        if (phase !in setOf("measurement", "validation")) return false
        val hasCandidate = value.has("candidateId")
        if (hasCandidate && !boundedText(value.opt("candidateId"), 128)) return false
        return (phase == "measurement") == !hasCandidate
    }

    private fun isAbort(value: JSONObject): Boolean = isSession(value)
        && value.opt("code") in calibrationErrorCodes
        && (!value.has("message") || boundedText(value.opt("message"), 1_024, allowEmpty = true))

    private fun isOutcome(value: Any?): Boolean = value in setOf("sufficient", "bounded", "insufficient", "cancelled", "error")

    private fun isProgress(value: JSONObject): Boolean = isSession(value)
        && value.opt("stage") in setOf("loudness", "preparing", "recording", "analyzing", "position-pause", "validation", "ending")
        && exactInt(value.opt("current"), 0, 256)
        && exactInt(value.opt("total"), 1, 256)
        && exactInt(value.opt("current"), 0, exactIntValue(value.opt("total")) ?: -1)
        && (!value.has("estimatedRemainingSeconds") || exactInt(value.opt("estimatedRemainingSeconds"), 0, 3_600))
        && (!value.has("message") || boundedText(value.opt("message"), 1_024, allowEmpty = true))

    private fun isMeasurementDiagnostics(value: JSONObject): Boolean = isSession(value)
        && validContext(value.opt("context"))
        && exactInt(value.opt("current"), 0, 256)
        && exactInt(value.opt("total"), 1, 256)
        && exactInt(value.opt("current"), 0, exactIntValue(value.opt("total")) ?: -1)
        && value.opt("diagnostics") is JSONObject

    private fun isMeasurementResponse(value: JSONObject): Boolean {
        if (!isSession(value) || !exactInt(value.opt("current"), 0, 256)) return false
        val total = exactIntValue(value.opt("total")) ?: return false
        if (total !in 1..256 || exactIntValue(value.opt("current")) !in 0..total) return false
        if (!value.has("left") || !value.has("right")) return false
        return optionalTrace(value.opt("left")) && optionalTrace(value.opt("right"))
            && (value.opt("left") != JSONObject.NULL || value.opt("right") != JSONObject.NULL)
    }

    private fun optionalTrace(value: Any?): Boolean {
        if (value == JSONObject.NULL) return true
        val trace = value as? JSONObject ?: return false
        val frequencies = trace.optJSONArray("frequenciesHz") ?: return false
        val magnitudes = trace.optJSONArray("magnitudesDb") ?: return false
        if (frequencies.length() !in 2..64 || frequencies.length() != magnitudes.length()) return false
        var previous = 0.0
        for (index in 0 until frequencies.length()) {
            val frequency = finiteNumber(frequencies.opt(index)) ?: return false
            val magnitude = finiteNumber(magnitudes.opt(index)) ?: return false
            if (frequency <= 0.0 || frequency <= previous || !magnitude.isFinite()) return false
            previous = frequency
        }
        return true
    }

    private fun isProbeCurve(value: JSONObject): Boolean {
        if (value.has("curve")) return value.opt("curve") == "flat" || value.opt("curve") == "hollow"
        val common = value.optJSONArray("bandsDb")
        if (!isDbArray(common, 64, -18.0, 6.0)) return false
        val hasLeft = value.has("leftBandsDb")
        val hasRight = value.has("rightBandsDb")
        return hasLeft == hasRight && (!hasLeft
            || (isDbArray(value.optJSONArray("leftBandsDb"), 64, -18.0, 6.0)
                && isDbArray(value.optJSONArray("rightBandsDb"), 64, -18.0, 6.0)))
    }

    private fun isDbArray(value: org.json.JSONArray?, expectedSize: Int, min: Double, max: Double): Boolean {
        if (value == null || value.length() != expectedSize) return false
        for (index in 0 until value.length()) {
            val number = finiteNumber(value.opt(index)) ?: return false
            if (number < min || number > max) return false
        }
        return true
    }

    private fun optionalContext(value: JSONObject): Boolean = !value.has("context") || validContext(value.opt("context"))

    private fun validContext(value: Any?): Boolean {
        val context = value as? JSONObject ?: return false
        val positionId = context.opt("positionId") as? String ?: return false
        val target = measurementPositions[positionId] ?: return false
        val reference = context.opt("reference") as? String ?: return false
        if (reference != "center") return false
        if (finiteNumber(context.opt("xCm")) != target[0]
            || finiteNumber(context.opt("yCm")) != target[1]
            || finiteNumber(context.opt("zCm")) != target[2]
        ) return false
        val positionCount = exactIntValue(context.opt("positionCount")) ?: return false
        val positionIndex = exactIntValue(context.opt("positionIndex")) ?: return false
        val attemptCount = exactIntValue(context.opt("attemptCount")) ?: return false
        val attemptIndex = exactIntValue(context.opt("attemptIndex")) ?: return false
        return positionCount in 1..16
            && positionIndex in 0 until positionCount
            && context.opt("channel") == "both"
            && context.opt("captureKind") in measurementCaptureKinds
            && context.opt("repairChannel") in setOf("both", "left", "right")
            && attemptCount in 1..2
            && attemptIndex in 0 until attemptCount
            && context.opt("phase") in setOf("measurement", "validation")
    }

    private fun optionalEnum(value: JSONObject, key: String, allowed: Set<String>): Boolean =
        !value.has(key) || value.opt(key) in allowed

    private fun optionalText(value: JSONObject, key: String, maxLength: Int): Boolean =
        !value.has(key) || boundedText(value.opt(key), maxLength, allowEmpty = false)

    private fun sessionId(value: Any?): Boolean {
        val text = value as? String ?: return false
        return boundedText(text, 128) && text.none { it.isWhitespace() || it.code <= 0x1f || it.code == 0x7f }
    }

    private fun boundedText(value: Any?, maxLength: Int, allowEmpty: Boolean = false): Boolean {
        val text = value as? String ?: return false
        return (allowEmpty || text.isNotEmpty()) && text.length <= maxLength
            && text.toByteArray(StandardCharsets.UTF_8).size <= maxLength
    }

    private fun finiteNumber(value: Any?): Double? {
        val number = value as? Number ?: return null
        return number.toDouble().takeIf(Double::isFinite)
    }

    private fun exactIntValue(value: Any?): Int? {
        val number = finiteNumber(value) ?: return null
        if (number < Int.MIN_VALUE || number > Int.MAX_VALUE || number != floor(number)) return null
        return number.toInt()
    }

    private fun exactInt(value: Any?, min: Int, max: Int): Boolean =
        exactIntValue(value)?.let { it in min..max } == true
}
