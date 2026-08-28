package com.darelisme.sweetspot.service

import android.util.Log
import com.darelisme.sweetspot.BuildConfig
import com.darelisme.sweetspot.audio.engine.DynamicsProcessingEq
import com.darelisme.sweetspot.calibration.CalibrationEngineResult
import com.darelisme.sweetspot.calibration.transport.CalibrationCaptureStreamFrame
import com.darelisme.sweetspot.calibration.transport.CalibrationCaptureStreamReceiver
import com.darelisme.sweetspot.calibration.model.*
import com.darelisme.sweetspot.calibration.model.CalibrationPackageCodec
import com.darelisme.sweetspot.calibration.model.CalibrationPackageParseResult
import com.darelisme.sweetspot.calibration.model.CalibrationPackageSourceDevice
import com.darelisme.sweetspot.calibration.model.CalibrationValidationStatus
import com.darelisme.sweetspot.calibration.model.MeasurementContext
import com.darelisme.sweetspot.calibration.model.MeasurementResponsePayload
import com.darelisme.sweetspot.calibration.model.parseCalibrationSessionAbortPayload
import com.darelisme.sweetspot.calibration.model.rollbackOutcome
import com.darelisme.sweetspot.transport.PeerTransport
import org.json.JSONObject
import java.io.FileInputStream
import kotlin.math.roundToInt

internal class SweetSpotPeerCommandHandler(
    private val host: SweetSpotPeerCommandHost,
) : PeerTransport.CommandHandler {
    private val diagnostics = SweetSpotDiagnosticsCommandHandler(host)

    companion object {
        private const val TAG = "SweetSpot"
    }

    override fun onCommand(type: String, payload: JSONObject, replyTo: (String, JSONObject) -> Unit) {
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
                host.replyCalibrationJobResult(host.commandCalibrationEngine?.startNewJob(), replyTo)
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
                        val currentJob = host.commandCalibrationEngine?.currentJob()
                        val currentCaptureId = when (val action = currentJob?.nextAction) {
                            is CalibrationAction.Capture -> action.request.captureId.value
                            is CalibrationAction.Validate -> action.captureId.value
                            else -> null
                        }
                        if (jobId != null && captureId != null
                            && currentJob?.id?.value == jobId.value
                            && currentCaptureId == captureId.value
                        ) host.commandCaptureStreamReceiver?.cancel()
                        if (jobId != null && captureId != null) host.commandCalibrationEngine?.cancelCapture(jobId, captureId) else null
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
                host.replyCalibrationJobResult(
                    if (jobId != null && captureId != null) host.commandCalibrationEngine?.captureReady(jobId, captureId)
                    else CalibrationEngineResult.Rejected(null, "invalid_capture", "A calibration job and capture ID are required"),
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
                        val value = arr.optDouble(i, Double.NaN)
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
            "profile.save" -> payload.optString("name").takeIf { it.isNotBlank() }?.let { engine?.saveCurrentProfile(it) }
            "profile.load" -> {
                commandOk = payload.optString("name").takeIf { it.isNotBlank() }?.let { host.loadProfileWithFeedback(it) } ?: false
                if (!commandOk) commandError = "Live DSP rejected profile load"
            }
            "profile.delete" -> payload.optString("name").takeIf { it.isNotBlank() }?.let { engine?.deleteProfile(it) }
            "calibration.applyCandidate" -> {
                val arr = payload.optJSONArray("bandsDb")
                val leftArr = payload.optJSONArray("leftBandsDb")
                val rightArr = payload.optJSONArray("rightBandsDb")
                val left = parseStrictCalibrationArray(leftArr)
                val right = parseStrictCalibrationArray(rightArr)
                val common = parseStrictCalibrationArray(arr)
                commandOk = if (leftArr != null || rightArr != null) {
                    val target = host.dpEq()
                    common != null && left != null && right != null && target?.applyCalibrationCandidate(common, left, right) == true
                } else {
                    common != null && host.dpEq()?.applyCalibrationCandidate(common) == true
                }
                if (!commandOk) commandError = host.dpEq()?.getLastCalibrationApplyError() ?: "Calibration candidate was rejected"
                if (!commandOk) host.showCalibrationErrorToast(commandError ?: "Calibration candidate was rejected")
                replyTo("state.snapshot", host.stateSnapshotJson().put("ok", commandOk).apply { commandError?.let { put("error", it) } })
                return
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
            "calibration.acceptCandidate" -> {
                val candidateId = payload.optString("candidateId")
                val transaction = host.dpEq()?.getCalibrationTransaction()
                commandOk = candidateId.isNotBlank() && (host.dpEq()?.acceptCalibrationCandidate(candidateId) == true)
                if (!commandOk) commandError = "Calibration candidate is not available for acceptance"
                if (commandOk) {
                    host.commandMeasurementController?.validationFinalized(candidateId, "improved", transaction?.reason)
                } else if (candidateId.isNotBlank()) {
                    host.commandMeasurementController?.validationFinalizationFailed(candidateId, commandError ?: "Calibration candidate acceptance failed")
                }
                replyTo("state.snapshot", host.stateSnapshotJson().put("ok", commandOk).apply { commandError?.let { put("error", it) } })
                return
            }
            "calibration.rollbackCandidate" -> {
                val candidateId = payload.optString("candidateId")
                val transaction = host.dpEq()?.getCalibrationTransaction()
                val result = transaction?.validationStatus?.rollbackOutcome() ?: "inconclusive"
                commandOk = candidateId.isNotBlank() && host.rollbackCalibrationCandidate(candidateId)
                if (!commandOk) commandError = host.dpEq()?.getLastCalibrationApplyError() ?: "Calibration candidate rollback failed"
                if (commandOk) {
                    host.commandMeasurementController?.validationFinalized(candidateId, result, transaction?.reason)
                } else if (candidateId.isNotBlank()) {
                    host.commandMeasurementController?.validationFinalizationFailed(candidateId, commandError ?: "Calibration candidate rollback failed")
                }
                replyTo("state.snapshot", host.stateSnapshotJson().put("ok", commandOk).apply { commandError?.let { put("error", it) } })
                return
            }
            "calibration.validation.result" -> {
                val candidateId = payload.optString("candidateId")
                val status = when (payload.optString("status")) {
                    "passed" -> CalibrationValidationStatus.PASSED
                    "worse" -> CalibrationValidationStatus.WORSE
                    "inconclusive" -> CalibrationValidationStatus.INCONCLUSIVE
                    "failed" -> CalibrationValidationStatus.FAILED
                    else -> null
                }
                val before = if (payload.has("beforeDb")) payload.optDouble("beforeDb", Double.NaN).toFloat().takeIf { it.isFinite() } else null
                val after = if (payload.has("afterDb")) payload.optDouble("afterDb", Double.NaN).toFloat().takeIf { it.isFinite() } else null
                val reason = payload.optString("reason").takeIf { it.isNotBlank() }
                commandOk = candidateId.isNotBlank() && status != null && host.dpEq()?.recordCalibrationValidation(
                    candidateId,
                    status,
                    before,
                    after,
                    reason,
                ) == true
                if (!commandOk) commandError = "Calibration validation result was rejected"
                if (!commandOk && candidateId.isNotBlank()) {
                    host.commandMeasurementController?.validationFinalizationFailed(candidateId, commandError ?: "Calibration validation result was rejected")
                }
                replyTo("state.snapshot", host.stateSnapshotJson().put("ok", commandOk).apply { commandError?.let { put("error", it) } })
                return
            }
            "calibration.reset" -> {
                commandOk = host.resetCalibration()
            }
            "calibrationSession.begin" -> {
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
            "calibrationSession.end" -> {
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

    override fun onCaptureFrame(frame: CalibrationCaptureStreamFrame) {
        val receiver = host.commandCaptureStreamReceiver ?: return
        var completed: CalibrationCaptureStreamReceiver.Completed? = null
        try {
            val finished = receiver.accept(frame) ?: return
            completed = finished
            val result = FileInputStream(finished.pcmFile).use { input ->
                host.commandCalibrationEngine?.submitCaptureStream(
                    metadataJson = finished.metadataJson,
                    pcm = input,
                    pcmBytes = finished.byteCount,
                )
            }
            if (result != null) host.publishCalibrationCaptureResult(result, finished)
            receiver.delete(finished)
        } catch (error: Throwable) {
            Log.e(TAG, "Direct calibration capture failed", error)
            completed?.let { finished ->
                try { receiver.delete(finished) } catch (_: Throwable) {}
            }
            receiver.cancel()
            host.publishCalibrationCaptureRejection(frame.captureId, error.message ?: "The TV rejected this calibration recording")
        }
    }

}
