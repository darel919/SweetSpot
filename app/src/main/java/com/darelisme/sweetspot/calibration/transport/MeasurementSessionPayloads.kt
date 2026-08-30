package com.darelisme.sweetspot.calibration.transport

import com.darelisme.sweetspot.calibration.model.MeasurementContext
import com.darelisme.sweetspot.calibration.playback.MeasurementSweep
import com.darelisme.sweetspot.calibration.playback.PinkNoiseGenerator
import org.json.JSONObject

internal data class MeasurementSessionPayloadState(
    val sessionId: String,
    val channel: String,
    val phase: String,
    val finalOutcome: String?,
    val cancellationRequested: Boolean,
)

internal object MeasurementSessionPayloads {
    fun session(state: MeasurementSessionPayloadState): JSONObject =
        JSONObject()
            .put("sessionId", state.sessionId)
            .put("channel", state.channel)
            .put("phase", state.phase)
            .put("outcome", state.finalOutcome ?: if (state.cancellationRequested) "cancelled" else "error")
            .put("completedSessionId", state.sessionId)

    fun ready(
        state: MeasurementSessionPayloadState,
        sweep: MeasurementSweep,
        context: MeasurementContext? = null,
    ): JSONObject = session(state).put("sweep", sweep(sweep)).also { payload ->
        context?.let { payload.put("context", it.toJson()) }
    }

    fun started(
        state: MeasurementSessionPayloadState,
        sweep: MeasurementSweep,
        context: MeasurementContext? = null,
    ): JSONObject = ready(state, sweep, context)

    fun finished(sessionId: String, context: MeasurementContext? = null): JSONObject =
        JSONObject().put("sessionId", sessionId).also { payload ->
            context?.let { payload.put("context", it.toJson()) }
        }

    fun error(sessionId: String, code: String, message: String): JSONObject =
        JSONObject().put("sessionId", sessionId).put("code", code).put("message", message)

    fun loudnessStarted(state: MeasurementSessionPayloadState, sampleRate: Int): JSONObject =
        session(state)
            .put("sampleRate", sampleRate)
            .put("levelDbfs", PinkNoiseGenerator.DEFAULT_LEVEL_DBFS)
            .put("loopDurationMs", PinkNoiseGenerator.DEFAULT_LOOP_DURATION_MS)

    fun positionContinued(sessionId: String, context: MeasurementContext): JSONObject =
        JSONObject()
            .put("sessionId", sessionId)
            .put("context", context.toJson())

    private fun sweep(sweep: MeasurementSweep): JSONObject =
        JSONObject()
            .put("algorithm", sweep.algorithm)
            .put("sweepRevision", sweep.sweepRevision)
            .put("sampleRate", sweep.sampleRate)
            .put("startHz", sweep.startHz)
            .put("endHz", sweep.endHz)
            .put("durationMs", sweep.durationMs)
            .put("preRollMs", sweep.preRollMs)
            .put("postRollMs", sweep.postRollMs)
            .put("syncMarkerStartHz", sweep.syncMarkerStartHz)
            .put("syncMarkerEndHz", sweep.syncMarkerEndHz)
            .put("syncMarkerDurationMs", sweep.syncMarkerDurationMs)
            .put("syncMarkerGapMs", sweep.syncMarkerGapMs)
            .put("endMarkerStartHz", sweep.endMarkerStartHz)
            .put("endMarkerEndHz", sweep.endMarkerEndHz)
            .put("endMarkerDurationMs", sweep.endMarkerDurationMs)
            .put("interSweepGapMs", sweep.interSweepGapMs)
            .put("sweepLevelDbfs", sweep.sweepLevelDbfs)
            .put("markerLevelDbfs", sweep.markerLevelDbfs)
            .put("fadeInMs", sweep.fadeInMs)
            .put("fadeOutMs", sweep.fadeOutMs)
            .put("captureKind", sweep.captureKind)
            .put("markerChannel", sweep.markerChannel)
}
