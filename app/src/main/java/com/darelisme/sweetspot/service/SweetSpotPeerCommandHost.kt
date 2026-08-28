package com.darelisme.sweetspot.service

import android.content.Context
import com.darelisme.sweetspot.audio.engine.AudioEngine
import com.darelisme.sweetspot.audio.engine.DynamicsProcessingEq
import com.darelisme.sweetspot.calibration.CalibrationEngine
import com.darelisme.sweetspot.calibration.CalibrationEngineResult
import com.darelisme.sweetspot.calibration.transport.CalibrationCaptureStreamReceiver
import com.darelisme.sweetspot.calibration.playback.MeasurementController
import com.darelisme.sweetspot.diagnostics.SweetSpotDiagnostics
import org.json.JSONObject

internal interface SweetSpotPeerCommandHost {
    val commandContext: Context
    val commandAudioEngine: AudioEngine?
    val commandCalibrationEngine: CalibrationEngine?
    val commandCaptureStreamReceiver: CalibrationCaptureStreamReceiver?
    val commandMeasurementController: MeasurementController?
    val commandDiagnostics: SweetSpotDiagnostics

    fun stateSnapshotJson(): JSONObject
    fun deviceInfoJson(): JSONObject
    fun transportDiagnosticsJson(): JSONObject
    fun replyCalibrationJobResult(result: CalibrationEngineResult?, replyTo: (String, JSONObject) -> Unit)
    fun publishCalibrationCaptureResult(result: CalibrationEngineResult?, capture: CalibrationCaptureStreamReceiver.Completed)
    fun publishCalibrationCaptureStarted(jobId: String, captureId: String, captureAttemptId: String)
    fun publishCalibrationCaptureWindow(captureId: String, captureAttemptId: String, nextSequence: Long, windowSize: Int)
    fun publishCalibrationCaptureRejection(captureId: String, captureAttemptId: String, reason: String)
    fun dpEq(): DynamicsProcessingEq?
    fun applyPresetWithFeedback(preset: Int): Boolean
    fun loadProfileWithFeedback(name: String): Boolean
    fun showCalibrationErrorToast(message: String)
    fun rollbackCalibrationCandidate(candidateId: String): Boolean
    fun resetCalibration(): Boolean
}
