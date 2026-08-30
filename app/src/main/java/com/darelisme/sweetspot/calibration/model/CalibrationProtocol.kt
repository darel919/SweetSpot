package com.darelisme.sweetspot.calibration.model

import org.json.JSONObject

internal const val MAX_CALIBRATION_ABORT_MESSAGE_LENGTH = 1024

/**
 * Error codes accepted on the calibration session protocol boundary.
 *
 * This mirrors the web protocol's closed set. Keeping the check here prevents
 * an arbitrary transport string from becoming a controller state transition or
 * TV status message.
 */
internal val CALIBRATION_ERROR_CODES: Set<String> = setOf(
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

internal val CALIBRATION_SESSION_OUTCOMES: Set<String> = setOf(
    "sufficient",
    "bounded",
    "insufficient",
    "cancelled",
    "error",
)

internal data class CalibrationSessionAbort(
    val sessionId: String,
    val code: String,
    val message: String?,
)

internal fun isCalibrationErrorCode(value: String): Boolean = value in CALIBRATION_ERROR_CODES

internal fun isCalibrationSessionOutcome(value: String): Boolean = value in CALIBRATION_SESSION_OUTCOMES

internal fun parseCalibrationSessionAbortValues(
    sessionId: String,
    code: String,
    message: String?,
): CalibrationSessionAbort? {
    if (!isValidMeasurementSessionId(sessionId) || !isCalibrationErrorCode(code)) return null
    if (message != null && message.length > MAX_CALIBRATION_ABORT_MESSAGE_LENGTH) return null
    val sanitizedMessage = message
        ?.filter { character ->
            character == '\n' || character == '\r' || character == '\t' || !character.isISOControl()
        }
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
    return CalibrationSessionAbort(sessionId, code, sanitizedMessage)
}

/**
 * Parses the wire payload used by calibrationSession.abort.
 *
 * The message is bounded and stripped of non-display control characters before
 * it reaches the TV UI. A missing or unknown code is a protocol error.
 */
internal fun parseCalibrationSessionAbortPayload(payload: JSONObject): CalibrationSessionAbort? {
    val message = if (!payload.has("message")) null else payload.get("message") as? String ?: return null
    return parseCalibrationSessionAbortValues(
        sessionId = payload.get("sessionId") as? String ?: return null,
        code = payload.get("code") as? String ?: return null,
        message = message,
    )
}

internal fun isUserCalibrationCancellation(code: String): Boolean =
    code == "calibration_aborted" || code == "calibration_ui_closed"
