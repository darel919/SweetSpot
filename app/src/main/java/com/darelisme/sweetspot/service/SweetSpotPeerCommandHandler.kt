package com.darelisme.sweetspot.service

import android.util.Log
import com.darelisme.sweetspot.BuildConfig
import com.darelisme.sweetspot.audio.engine.DynamicsProcessingEq
import com.darelisme.sweetspot.calibration.CalibrationEngineResult
import com.darelisme.sweetspot.calibration.transport.CalibrationCaptureStreamFrame
import com.darelisme.sweetspot.calibration.transport.CalibrationCaptureStreamReceiver
import com.darelisme.sweetspot.calibration.transport.CalibrationCaptureStreamWire
import com.darelisme.sweetspot.calibration.model.*
import com.darelisme.sweetspot.calibration.model.CalibrationPackageCodec
import com.darelisme.sweetspot.calibration.model.CalibrationPackageParseResult
import com.darelisme.sweetspot.calibration.model.CalibrationPackageSourceDevice
import com.darelisme.sweetspot.calibration.model.MeasurementContext
import com.darelisme.sweetspot.calibration.model.MeasurementResponsePayload
import com.darelisme.sweetspot.calibration.model.parseCalibrationSessionAbortPayload
import com.darelisme.sweetspot.transport.PeerTransport
import com.darelisme.sweetspot.transport.protocol.PeerEnvelopeValidator
import org.json.JSONObject
import java.io.FileInputStream
import kotlin.math.roundToInt

internal class SweetSpotPeerCommandHandler(
    private val host: SweetSpotPeerCommandHost,
) : PeerTransport.CommandHandler {
    private val diagnostics = SweetSpotDiagnosticsCommandHandler(host)

    companion object {
        private const val TAG = "SweetSpot"
        private const val CAPTURE_WINDOW_SIZE = 8
        private val CAPTURE_ATTEMPT_ID_PATTERN = Regex("^[A-Za-z0-9_-]{1,128}$")
    }

    override fun onCommand(sessionId: String, type: String, payload: JSONObject, replyTo: (String, JSONObject) -> Unit) {
        PeerEnvelopeValidator.validateClientPayload(type, payload)?.let { error ->
            Log.w(TAG, "Rejected invalid $type payload")
            replyTo(
                "state.snapshot",
                host.stateSnapshotJson()
                    .put("ok", false)
                    .put("errorCode", "invalid_payload")
                    .put("error", error),
            )
            return
        }
        if (diagnostics.handle(type, payload, replyTo)) return
        val engine = host.commandAudioEngine
        var commandOk = true
        var commandError: String? = null
        when (type) {
            "state.get",
            "profile.list",
            "calibration.get" -> {
                replyTo("state.snapshot", host.stateSnapshotJson())
                return
            }
            "calibration.job.start" -> {
                val mode = when (payload.opt("mode")) {
                    null,
                    JSONObject.NULL,
                    "auto" -> CalibrationJobMode.AUTO
                    "advanced" -> CalibrationJobMode.ADVANCED
                    else -> null
                }
                host.replyCalibrationJobResult(
                    mode?.let { host.commandCalibrationEngine?.startNewJob(it) }
                        ?: CalibrationEngineResult.Rejected(null, "invalid_mode", "The calibration mode is not supported"),
                    replyTo,
                )
                return
            }
            "calibration.job.get" -> {
                val requestedId = payload.optString("jobId").takeIf { it.isNotBlank() }
                val current = host.commandCalibrationEngine?.currentJob()
                if (requestedId == null || current?.id?.value == requestedId) {
                    host.replyCalibrationJobResult(host.commandCalibrationEngine?.resumeJob(), replyTo)
                } else {
                    replyTo("state.snapshot", host.stateSnapshotJson().put("ok", false).put("error", "No matching calibration job"))
                }
                return
            }
            "calibration.job.finish" -> {
                val jobId = payload.optString("jobId").takeIf { it.isNotBlank() }?.let(::CalibrationJobId)
                host.replyCalibrationJobResult(
                    jobId?.let { host.commandCalibrationEngine?.finishWithBest(it) }
                        ?: CalibrationEngineResult.Rejected(null, "invalid_job", "A calibration job ID is required"),
                    replyTo,
                )
                return
            }
            "calibration.job.cancel" -> {
                val jobId = payload.optString("jobId").takeIf { it.isNotBlank() }?.let(::CalibrationJobId)
                val result = when (payload.optString("scope")) {
                    "capture" -> {
                        val captureId = payload.optString("captureId").takeIf { it.isNotBlank() }?.let(::CaptureId)
                        val hasCaptureAttempt = payload.has("captureAttemptId")
                        val captureAttemptId = captureAttemptId(payload)
                        val currentJob = host.commandCalibrationEngine?.currentJob()
                        val currentCaptureId = when (val action = currentJob?.nextAction) {
                            is CalibrationAction.Capture -> action.request.captureId.value
                            is CalibrationAction.Validate -> action.captureId.value
                            else -> null
                        }
                        if (hasCaptureAttempt && captureAttemptId == null) {
                            CalibrationEngineResult.Rejected(null, "invalid_capture_attempt", "The capture attempt identity is invalid")
                        } else if (jobId != null && captureId != null) {
                            if (captureAttemptId != null
                                && currentJob?.id?.value == jobId.value
                                && currentCaptureId == captureId.value
                            ) host.commandCaptureStreamReceiver?.cancel(sessionId, captureId.value, captureAttemptId)
                            host.commandCalibrationEngine?.cancelCapture(jobId, captureId, captureAttemptId)
                        } else null
                    }
                    "optional_refinement" -> jobId?.let {
                        if (host.commandCalibrationEngine?.currentJob()?.id?.value == it.value) host.commandCaptureStreamReceiver?.cancel()
                        host.commandCalibrationEngine?.cancelOptionalRefinement(it)
                    }
                    else -> null
                }
                host.replyCalibrationJobResult(result ?: CalibrationEngineResult.Rejected(null, "invalid_cancel", "Invalid calibration cancel scope"), replyTo)
                return
            }
            "calibration.job.discard" -> {
                val jobId = payload.optString("jobId").takeIf { it.isNotBlank() }?.let(::CalibrationJobId)
                if (jobId != null && host.commandCalibrationEngine?.currentJob()?.id == jobId) host.commandCaptureStreamReceiver?.cancel()
                host.replyCalibrationJobResult(
                    jobId?.let { host.commandCalibrationEngine?.discardJob(it) }
                        ?: CalibrationEngineResult.Rejected(null, "invalid_job", "A calibration job ID is required"),
                    replyTo,
                )
                return
            }
            "calibration.capture.ready",
            "calibration.validation.capture.ready" -> {
                val jobId = payload.optString("jobId").takeIf { it.isNotBlank() }?.let(::CalibrationJobId)
                val captureId = payload.optString("captureId").takeIf { it.isNotBlank() }?.let(::CaptureId)
                val captureAttemptId = captureAttemptId(payload)
                host.replyCalibrationJobResult(
                    if (jobId != null && captureId != null && captureAttemptId != null) {
                        host.commandCalibrationEngine?.captureReady(jobId, captureId, captureAttemptId)
                    } else CalibrationEngineResult.Rejected(null, "invalid_capture", "A calibration job, capture ID, and capture attempt are required"),
                    replyTo,
                )
                return
            }
            "engine.enable" -> {
                commandOk = engine?.setEnabled(true) == true
                if (!commandOk) commandError = "Live DSP rejected enable"
            }
            "engine.bypass" -> {
                commandOk = engine?.setEnabled(false) == true
                if (!commandOk) commandError = "Live DSP rejected bypass"
            }
            "engine.setBands" -> {
                val arr = payload.optJSONArray("bandsDb")
                val bandCount = engine?.getCapabilities()?.bandCount ?: 0
                val previous = engine?.getBandLevels()
                val previousPreset = engine?.getActivePreset() ?: 0
                if (arr == null || arr.length() != bandCount) {
                    commandOk = false
                    commandError = "Expected $bandCount user EQ bands"
                } else {
                    for (i in 0 until bandCount) {
                        val value = (arr.opt(i) as? Number)?.toDouble() ?: Double.NaN
                        if (!value.isFinite()
                            || value < DynamicsProcessingEq.MIN_USER_LEVEL_MILLIBELS / 100f
                            || value > DynamicsProcessingEq.MAX_USER_LEVEL_MILLIBELS / 100f
                            || engine?.setBandLevel(i, (value * 100).roundToInt()) != true
                        ) {
                            commandOk = false
                            commandError = "Live DSP rejected user EQ band $i"
                            break
                        }
                    }
                }
                if (!commandOk && previous != null && previous.size == bandCount) {
                    for (i in previous.indices) {
                        if (engine.setBandLevel(i, previous[i]) != true) {
                            commandError = "$commandError; previous user EQ could not be fully restored"
                            break
                        }
                    }
                    if (previousPreset > 0 && engine.applyPreset(previousPreset) != true) {
                        commandError = "$commandError; previous EQ preset could not be fully restored"
                    }
                }
            }
            "engine.applyPreset" -> {
                commandOk = host.applyPresetWithFeedback(payload.optInt("preset", -1))
                if (!commandOk) commandError = "Live DSP rejected preset"
            }
            "profile.save" -> {
                val name = payload.optString("name")
                commandOk = engine?.saveCurrentProfile(name) == true
                if (!commandOk) commandError = "Live DSP rejected profile save"
            }
            "profile.load" -> {
                commandOk = payload.optString("name").takeIf { it.isNotBlank() }?.let { host.loadProfileWithFeedback(it) } ?: false
                if (!commandOk) commandError = "Live DSP rejected profile load"
            }
            "profile.delete" -> {
                val name = payload.optString("name")
                commandOk = engine?.deleteProfile(name) == true
                if (!commandOk) commandError = "Live DSP rejected profile delete"
            }
            "calibration.export" -> {
                val target = host.dpEq()
                val packageValue = target?.exportCalibrationPackage(
                    CalibrationPackageSourceDevice(
                        id = com.darelisme.sweetspot.pairing.DeviceIdentity.get(host.commandContext),
                        name = com.darelisme.sweetspot.pairing.DeviceIdentity.getName(host.commandContext),
                        appVersion = "0.1.0",
                        buildId = BuildConfig.SWEETSPOT_BUILD_ID,
                    ),
                )
                commandOk = packageValue != null
                if (packageValue != null) {
                    replyTo("calibration.exported", CalibrationPackageCodec.serialize(packageValue))
                    return
                }
                commandError = target?.getLastCalibrationApplyError()
                    ?: target?.getLiveDspVerificationError()
                    ?: "No verified active calibration is available"
                replyTo("state.snapshot", host.stateSnapshotJson().put("ok", false).put("error", commandError))
                return
            }
            "calibration.import" -> {
                val target = host.dpEq()
                val expectedFrequencies = target?.getCalibrationFrequenciesHz() ?: IntArray(0)
                val parsed = if (target == null) {
                    CalibrationPackageParseResult.Rejected("The TV audio engine is unavailable")
                } else {
                    CalibrationPackageCodec.parseForImport(
                        payload = payload,
                        expectedFrequenciesHz = expectedFrequencies,
                        independentRoutingVerified = target.supportsIndependentCalibration(),
                    )
                }
                when (parsed) {
                    is CalibrationPackageParseResult.Accepted -> {
                        commandOk = target?.applyImportedCalibrationCandidate(parsed.value) == true
                        if (!commandOk) {
                            commandError = target?.getLastCalibrationApplyError()
                                ?: "Imported calibration was rejected"
                        }
                    }
                    is CalibrationPackageParseResult.Rejected -> {
                        commandOk = false
                        commandError = parsed.error
                    }
                }
                if (!commandOk) host.showCalibrationErrorToast(commandError ?: "Imported calibration was rejected")
                replyTo("state.snapshot", host.stateSnapshotJson().put("ok", commandOk).apply {
                    commandError?.let { put("error", it) }
                })
                return
            }
            "calibration.reset" -> {
                commandOk = host.resetCalibration()
            }
            "calibrationSession.begin" -> {
                if (payload.optString("phase", "measurement") != "measurement" || payload.optString("candidateId").isNotBlank()) {
                    replyTo(
                        "measurement.error",
                        JSONObject()
                            .put("sessionId", payload.optString("sessionId"))
                            .put("code", "invalid_session")
                            .put("message", "Remote validation is owned by the TV calibration job"),
                    )
                    return
                }
                host.commandMeasurementController?.begin(
                    payload.optString("sessionId"),
                    payload.optString("channel", "both"),
                    payload.optString("phase", "measurement"),
                    payload.optString("candidateId").takeIf { it.isNotBlank() },
                    null,
                    emit = { eventType, eventPayload, _ -> replyTo(eventType, eventPayload) },
                )
                return
            }
            "diagnostics.calibrationSession.end" -> {
                host.commandMeasurementController?.end(
                    payload.optString("sessionId"),
                    payload.optString("outcome").takeIf { it.isNotBlank() },
                    null,
                    emit = { eventType, eventPayload, _ -> replyTo(eventType, eventPayload) },
                )
                return
            }
            "calibrationSession.abort" -> {
                val abort = parseCalibrationSessionAbortPayload(payload)
                if (abort == null) {
                    replyTo(
                        "measurement.error",
                        JSONObject()
                            .put("sessionId", payload.optString("sessionId"))
                            .put("code", "invalid_session")
                            .put("message", "calibrationSession.abort requires a valid error code"),
                    )
                } else {
                    host.commandMeasurementController?.cancel(
                        sessionId = abort.sessionId,
                        code = abort.code,
                        message = abort.message,
                        replyTo = null,
                        emit = { eventType, eventPayload, _ -> replyTo(eventType, eventPayload) },
                    )
                }
                return
            }
            "calibrationSession.loudness.start" -> {
                host.commandMeasurementController?.startLoudness(
                    payload.optString("sessionId"),
                    null,
                    emit = { eventType, eventPayload, _ -> replyTo(eventType, eventPayload) },
                )
                return
            }
            "calibrationSession.loudness.stop" -> {
                host.commandMeasurementController?.stopLoudness(
                    payload.optString("sessionId"),
                    null,
                    emit = { eventType, eventPayload, _ -> replyTo(eventType, eventPayload) },
                )
                return
            }
            "calibrationSession.progress" -> {
                host.commandMeasurementController?.updateProgress(
                    payload.optString("sessionId"),
                    payload.optString("stage"),
                    payload.optInt("current", -1),
                    payload.optInt("total", -1),
                    payload.optInt("estimatedRemainingSeconds", -1).takeIf { payload.has("estimatedRemainingSeconds") },
                    payload.optString("message").takeIf { payload.has("message") },
                )
                return
            }
            "measurement.response" -> {
                val response = MeasurementResponsePayload.parse(payload)
                if (response == null) {
                    Log.w(TAG, "Rejected invalid measurement.response payload")
                    return
                }
                host.commandMeasurementController?.updateResponse(response)
                return
            }
            "measurement.prepare" -> {
                val context = MeasurementContext.fromJson(payload.optJSONObject("context"))
                if (payload.has("context") && context == null) {
                    replyTo("measurement.error", JSONObject()
                        .put("sessionId", payload.optString("sessionId"))
                        .put("code", "invalid_session")
                        .put("message", "Invalid measurement context"))
                    return
                }
                host.commandMeasurementController?.prepare(
                    payload.optString("sessionId"),
                    payload.optString("channel", "both"),
                    context,
                    null,
                    emit = { eventType, eventPayload, _ -> replyTo(eventType, eventPayload) },
                )
                return
            }
            "measurement.playSweep" -> {
                val context = MeasurementContext.fromJson(payload.optJSONObject("context"))
                if (payload.has("context") && context == null) {
                    replyTo("measurement.error", JSONObject()
                        .put("sessionId", payload.optString("sessionId"))
                        .put("code", "invalid_session")
                        .put("message", "Invalid measurement context"))
                    return
                }
                host.commandMeasurementController?.playSweep(
                    payload.optString("sessionId"),
                    context,
                    null,
                    emit = { eventType, eventPayload, _ -> replyTo(eventType, eventPayload) },
                )
                return
            }
            "measurement.abort" -> {
                host.commandMeasurementController?.cancel(
                    sessionId = payload.optString("sessionId"),
                    code = "calibration_aborted",
                    message = "Calibration cancelled",
                    replyTo = null,
                    emit = { eventType, eventPayload, _ -> replyTo(eventType, eventPayload) },
                )
                return
            }
            "measurement.diagnostics" -> {
                val context = MeasurementContext.fromJson(payload.optJSONObject("context"))
                if (context == null) return
                host.commandMeasurementController?.updateDiagnostics(
                    payload.optString("sessionId"),
                    context,
                    payload.optInt("current", -1),
                    payload.optInt("total", -1),
                    payload.optJSONObject("diagnostics") ?: return,
                )
                return
            }
            else -> {
                Log.d(TAG, "control: unknown command $type")
                return
            }
        }
        replyTo("state.snapshot", host.stateSnapshotJson().put("ok", commandOk).apply { commandError?.let { put("error", it) } })
    }

    override fun onCaptureData(sessionId: String, data: ByteArray) {
        val receiver = host.commandCaptureStreamReceiver ?: return
        var captureId = ""
        var captureAttemptId = ""
        var frameSessionId: String? = null
        var completed: CalibrationCaptureStreamReceiver.Completed? = null
        try {
            val frame = CalibrationCaptureStreamWire.decode(data)
            captureId = frame.captureId
            captureAttemptId = frame.captureAttemptId
            frameSessionId = frame.sessionId
            if (frame.sessionId != sessionId) throw IllegalArgumentException("Calibration capture belongs to a stale peer session")
            val finished = receiver.accept(frame)
            when (frame) {
                is CalibrationCaptureStreamFrame.Begin -> host.publishCalibrationCaptureWindow(
                    frame.captureId,
                    frame.captureAttemptId,
                    0,
                    CAPTURE_WINDOW_SIZE,
                )
                is CalibrationCaptureStreamFrame.Chunk -> if ((frame.sequence + 1) % CAPTURE_WINDOW_SIZE == 0L) {
                    host.publishCalibrationCaptureWindow(
                        frame.captureId,
                        frame.captureAttemptId,
                        frame.sequence + 1,
                        CAPTURE_WINDOW_SIZE,
                    )
                }
                is CalibrationCaptureStreamFrame.End -> Unit
            }
            if (finished == null) return
            completed = finished
            if (finished.duplicate) {
                host.publishCalibrationCaptureResult(null, finished)
                return
            }
            val result = FileInputStream(finished.pcmFile).use { input ->
                host.commandCalibrationEngine?.submitCaptureStream(
                    metadataJson = finished.metadataJson,
                    pcm = input,
                    pcmBytes = finished.byteCount,
                    captureAttemptId = finished.captureAttemptId,
                )
            }
            if (result != null) host.publishCalibrationCaptureResult(result, finished)
            receiver.delete(finished)
        } catch (error: Throwable) {
            Log.e(TAG, "Direct calibration capture failed", error)
            completed?.let { finished ->
                try { receiver.delete(finished) } catch (_: Throwable) {}
            }
            if (frameSessionId == sessionId && captureId.isNotBlank()) receiver.cancel(sessionId, captureId, captureAttemptId)
            if (frameSessionId == sessionId && captureId.isNotBlank() && captureAttemptId.isNotBlank()) {
                host.publishCalibrationCaptureRejection(captureId, captureAttemptId, error.message ?: "The TV rejected this calibration recording")
            }
        }
    }

    override fun onCaptureDataRejected(sessionId: String, data: ByteArray, reason: String) {
        try {
            val frame = CalibrationCaptureStreamWire.decode(data)
            if (frame.sessionId != sessionId) return
            host.commandCaptureStreamReceiver?.cancel(sessionId, frame.captureId, frame.captureAttemptId)
            host.publishCalibrationCaptureRejection(frame.captureId, frame.captureAttemptId, reason)
        } catch (error: Throwable) {
            Log.w(TAG, "Unable to identify rejected direct capture frame", error)
            host.commandCaptureStreamReceiver?.cancel()
        }
    }

    private fun captureAttemptId(payload: JSONObject): String? {
        val value = payload.optString("captureAttemptId").takeIf { it.isNotBlank() } ?: return null
        return value.takeIf { CAPTURE_ATTEMPT_ID_PATTERN.matches(it) }
    }

}
