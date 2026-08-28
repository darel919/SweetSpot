package com.darelisme.sweetspot.transport.protocol

import org.json.JSONObject

internal object PeerEnvelopeValidator {
    private const val VERSION = 1
    private const val MAX_ID_LENGTH = 256
    private const val MAX_TYPE_LENGTH = 128
    private const val MAX_SESSION_ID_LENGTH = 128
    private const val MAX_REPLY_TO_LENGTH = 256
    private const val MAX_EXPIRY_AFTER_TIMESTAMP_MS = 120_000.0

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
        "calibration.applyCandidate",
        "calibration.acceptCandidate",
        "calibration.rollbackCandidate",
        "calibration.validation.result",
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
        "calibrationSession.end",
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

    fun isValid(value: JSONObject, sessionId: String): Boolean {
        val version = value.opt("v") as? Number
        val id = value.opt("id") as? String
        val type = value.opt("type") as? String
        val timestamp = value.opt("ts") as? Number
        val transportSessionId = value.opt("transportSessionId") as? String
        val payload = value.opt("payload") as? JSONObject
        if (version == null || version.toDouble() != VERSION.toDouble()
            || id.isNullOrBlank() || id.length > MAX_ID_LENGTH
            || type.isNullOrBlank() || type.length > MAX_TYPE_LENGTH || type !in clientToDeviceTypes
            || timestamp == null || !timestamp.toDouble().isFinite()
            || transportSessionId != sessionId || transportSessionId.length > MAX_SESSION_ID_LENGTH
            || payload == null
        ) return false

        val replyTo = if (value.has("replyTo")) value.opt("replyTo") as? String else null
        if (value.has("replyTo") && (replyTo == null || replyTo.length > MAX_REPLY_TO_LENGTH)) return false

        if (!value.has("expiresAt")) return true
        val expiresAt = value.opt("expiresAt") as? Number ?: return false
        val timestampMs = timestamp.toDouble()
        val expiryMs = expiresAt.toDouble()
        return expiryMs.isFinite()
            && expiryMs > timestampMs
            && expiryMs <= timestampMs + MAX_EXPIRY_AFTER_TIMESTAMP_MS
    }
}
