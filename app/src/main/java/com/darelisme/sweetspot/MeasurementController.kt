package com.darelisme.sweetspot

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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

class MeasurementController(
    private val context: Context,
    private val engine: AudioEngine,
    private val rollbackCandidate: (String) -> Boolean = { false },
    private val stateSnapshotProvider: () -> JSONObject = { JSONObject() },
    private val finalStateVerifier: () -> Boolean = { true },
) {
    companion object {
        private const val TAG = "SweetSpotMeasurement"
        private const val WATCHDOG_MS = 60_000L
        private const val PCM_CHUNK_FRAMES = 4_096
        private const val PCM_CHUNK_SAMPLES = PCM_CHUNK_FRAMES * 2
        private const val PCM_WRITE_RETRY_MS = 2L
    }

    private sealed interface SessionState {
        data object Idle : SessionState
        data class AwaitingUi(val session: Session) : SessionState
        data class Ready(
            val session: Session,
            val sweep: MeasurementSweep,
            val channel: String,
            val context: MeasurementContext?
        ) : SessionState
        data class Playing(
            val session: Session,
            val sweep: MeasurementSweep,
            val channel: String,
            val context: MeasurementContext?
        ) : SessionState
        data class Loudness(val session: Session) : SessionState
        data class AwaitingValidationFinalization(val session: Session) : SessionState
        data class ValidationFinalized(val session: Session) : SessionState
        data class Finishing(val session: Session) : SessionState
    }

    private data class Session(
        val id: String,
        val channel: String,
        val phase: String,
        val candidateId: String?,
        var emit: (String, org.json.JSONObject, String?) -> Unit,
        var replyTo: String?,
        var continuedPositionContext: MeasurementContext? = null,
        var validationFinalizationBlocked: Boolean = false,
        var validationFatal: Boolean = false,
        var validationOverrideApplied: Boolean = false,
        var validationOverrideRestored: Boolean = false,
        val validationRecoveryGate: ValidationRecoveryGate = ValidationRecoveryGate(),
        var validationRecoveryResult: ValidationRecoveryResult? = null,
        var validationRecoveryEventsPublished: Boolean = false,
        var endedEventPublished: Boolean = false,
        var validationAbortError: Pair<String, String>? = null,
        var cancellationRequested: Boolean = false,
    )

    private class PlaybackResources(val track: AudioTrack) {
        @Volatile
        var stopped = false
    }

    private data class PreparedLoudness(
        val resources: PlaybackResources,
        val stream: PinkNoiseGenerator.StereoStream
    )

    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "sweetspot-measurement").apply { isDaemon = true }
    }

    @Volatile
    private var activeSession: Session? = null
    private val sessionFence = MeasurementSessionFence()
    private var state: SessionState = SessionState.Idle
    private var focusRequest: AudioFocusRequest? = null
    @Volatile
    private var playbackResources: PlaybackResources? = null
    private val playbackLock = Any()
    private var bypassState: MeasurementAudioState? = null
    private var watchdog: ScheduledFuture<*>? = null
    @Volatile
    private var closed = false

    private val audioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    fun begin(
        sessionId: String,
        channel: String,
        phase: String,
        candidateId: String?,
        replyTo: String?,
        emit: (String, org.json.JSONObject, String?) -> Unit
    ) {
        submit {
            if (!validSessionId(sessionId) || !validChannel(channel) || !validPhase(phase)
                || (phase == "validation" && candidateId.isNullOrBlank())
                || (phase == "measurement" && candidateId != null)
            ) {
                emitError(emit, replyTo, sessionId, "invalid_session", "Invalid session, channel, or phase")
                return@submit
            }
            if (activeSession != null || state !is SessionState.Idle || !sessionFence.begin(sessionId)) {
                emitError(emit, replyTo, sessionId, "already_measuring", "Another calibration session is active")
                return@submit
            }

            val session = Session(sessionId, channel, phase, candidateId, emit, replyTo)
            activeSession = session
            state = SessionState.AwaitingUi(session)
            touchWatchdog()
            CalibrationActivity.updateStatus(sessionId, "Opening calibration mode…")
            CalibrationActivity.updatePrimaryAction(sessionId, null)
            try {
                mainHandler.post {
                    if (closed || activeSession !== session) return@post
                    try {
                        context.startActivity(Intent(context, CalibrationActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            putExtra(CalibrationActivity.EXTRA_SESSION_ID, sessionId)
                        })
                    } catch (error: Throwable) {
                        submit {
                            if (activeSession === session) {
                                finishWithError(session, "calibration_ui_failed", error.message ?: "Unable to open calibration UI")
                            }
                        }
                    }
                }
            } catch (error: Throwable) {
                finishWithError(session, "calibration_ui_failed", error.message ?: "Unable to open calibration UI")
            }
        }
    }

    fun activityReady(sessionId: String) {
        submit {
            val session = activeSession ?: return@submit
            if (session.id != sessionId || state !is SessionState.AwaitingUi) return@submit
            touchWatchdog()
            CalibrationActivity.updateStatus(sessionId, "Requesting exclusive audio…")
            if (!requestExclusiveFocus()) {
                finishWithError(session, "audio_focus_denied", "Exclusive audio focus was denied")
                return@submit
            }
            try {
                val overrideResult = if (session.phase == "validation") {
                    engine.beginCalibrationValidation(session.candidateId)
                } else {
                    engine.beginMeasurementBypass()
                }
                bypassState = when (overrideResult) {
                    is MeasurementAudioOverrideResult.Applied -> overrideResult.previousState.also {
                        session.validationOverrideApplied = session.phase == "validation"
                    }
                    is MeasurementAudioOverrideResult.Failed -> {
                        finishWithError(
                            session,
                            if (overrideResult.restored) "dsp_state_unverified" else "dsp_restore_failed",
                            overrideResult.error,
                        )
                        return@submit
                    }
                }
                val sweep = prepareSweep(session.channel)
                state = SessionState.Ready(session, sweep, session.channel, null)
                session.emit("calibrationSession.started", JSONObjectPayload.session(session), session.replyTo)
                session.emit("measurement.ready", JSONObjectPayload.ready(session, sweep), session.replyTo)
                CalibrationActivity.updateStatus(
                    sessionId,
                    if (session.phase == "validation") {
                        "Validating the applied correction.\nKeep the iPhone still."
                    } else {
                        "Calibration is ready.\nKeep the iPhone still."
                    }
                )
                touchWatchdog()
            } catch (error: Throwable) {
                finishWithError(session, "sweep_playback_failed", error.message ?: "Unable to prepare sweep")
            }
        }
    }

    fun activityClosed(sessionId: String) {
        submit {
            val session = activeSession ?: return@submit
            if (session.id == sessionId) {
                when (state) {
                    is SessionState.ValidationFinalized -> closeValidationUi(session)
                    is SessionState.AwaitingValidationFinalization ->
                        finishCancelled(session, "calibration_ui_closed", "Calibration UI closed unexpectedly")
                    else -> finishCancelled(session, "calibration_ui_closed", "Calibration UI closed unexpectedly")
                }
            }
        }
    }

    fun cancel(
        sessionId: String,
        code: String,
        message: String?,
        replyTo: String?,
        emit: (String, org.json.JSONObject, String?) -> Unit,
    ) {
        submit {
            val session = activeSession
            if (sessionFence.shouldIgnore(sessionId)) return@submit
            if (session == null || session.id != sessionId) {
                emitError(emit, replyTo, sessionId, "invalid_session", "No matching calibration session")
                return@submit
            }
            session.emit = emit
            session.replyTo = replyTo
            when (state) {
                is SessionState.ValidationFinalized -> closeValidationUi(session)
                else -> if (isUserCalibrationCancellation(code)) {
                    finishCancelled(session, code, message ?: defaultCalibrationAbortMessage(code))
                } else {
                    finishWithError(session, code, message ?: defaultCalibrationAbortMessage(code))
                }
            }
        }
    }

    fun cancelFromActivity(sessionId: String) {
        submit {
            activeSession?.let { session ->
                if (session.id != sessionId) return@let
                when (state) {
                    is SessionState.ValidationFinalized -> closeValidationUi(session)
                    else -> finishCancelled(session, "calibration_aborted", "Calibration cancelled")
                }
            }
        }
    }

    fun clientPresenceChanged(present: Boolean) {
        if (present) return
        submit {
            activeSession?.let { session ->
                when (state) {
                    is SessionState.ValidationFinalized -> closeValidationUi(session)
                    else -> finishCancelled(session, "calibration_aborted", "Calibration dashboard disconnected")
                }
            }
        }
    }

    fun continueFromActivity(sessionId: String) {
        submit {
            val session = activeSession ?: return@submit
            if (session.id != sessionId) return@submit
            when (val current = state) {
                is SessionState.Loudness -> stopLoudnessInternal(session)
                is SessionState.Ready -> {
                    val context = current.context ?: return@submit
                    if (!context.requiresRemoteContinue() || context.sameLogicalTake(session.continuedPositionContext)) return@submit
                    session.continuedPositionContext = context
                    CalibrationActivity.updatePrimaryAction(session.id, null)
                    session.emit(
                        "calibrationSession.position.continued",
                        JSONObjectPayload.positionContinued(session, context),
                        session.replyTo,
                    )
                    touchWatchdog()
                }
                is SessionState.ValidationFinalized -> closeValidationUi(session)
                is SessionState.AwaitingValidationFinalization -> {
                    if (session.validationFatal) closeValidationUi(session)
                }
                else -> Unit
            }
        }
    }

    fun validationFinalized(candidateId: String, outcome: String, reason: String? = null) {
        submit {
            val session = activeSession ?: return@submit
            if (session.phase != "validation" || session.candidateId != candidateId ||
                state !is SessionState.AwaitingValidationFinalization
            ) return@submit
            val abortOutcome = session.validationAbortError?.let { error ->
                if (isUserCalibrationCancellation(error.first)) "cancelled" else "error"
            }
            val finalOutcome = abortOutcome ?: outcome
            if (finalOutcome == "improved" && session.validationFinalizationBlocked) {
                showValidationFailure(session, "The TV could not verify restoration before accepting the candidate")
                return@submit
            }
            state = SessionState.ValidationFinalized(session)
            watchdog?.cancel(false)
            watchdog = null
            val result = calibrationResultText(
                finalOutcome,
                if (finalOutcome == "error") session.validationAbortError?.second ?: reason else reason,
            )
            CalibrationActivity.updateStatus(session.id, "${result.title}\n${result.body}")
            CalibrationActivity.updatePrimaryAction(session.id, "Done")
        }
    }

    fun validationFinalizationFailed(candidateId: String, message: String) {
        submit {
            val session = activeSession ?: return@submit
            if (session.phase != "validation" || session.candidateId != candidateId ||
                state !is SessionState.AwaitingValidationFinalization
            ) return@submit
            showValidationFailure(session, message)
        }
    }

    fun end(sessionId: String, replyTo: String?, emit: (String, org.json.JSONObject, String?) -> Unit) {
        submit {
            val session = activeSession
            if (session == null || session.id != sessionId) {
                emitError(emit, replyTo, sessionId, "invalid_session", "No matching calibration session")
                return@submit
            }
            session.emit = emit
            session.replyTo = replyTo
            finishSession(session, null)
        }
    }

    fun startLoudness(
        sessionId: String,
        replyTo: String?,
        emit: (String, org.json.JSONObject, String?) -> Unit
    ) {
        submit {
            val session = activeSession
            if (session == null || session.id != sessionId || session.phase != "measurement") {
                emitError(emit, replyTo, sessionId, "invalid_session", "Loudness preflight is not available")
                return@submit
            }
            session.emit = emit
            session.replyTo = replyTo
            if (state is SessionState.Loudness) {
                touchWatchdog()
                return@submit
            }
            if (state !is SessionState.Ready) {
                emitError(emit, replyTo, sessionId, "invalid_session", "Session is not ready for loudness preflight")
                return@submit
            }
            try {
                stopAudioTrack()
                val prepared = prepareLoudness()
                state = SessionState.Loudness(session)
                playTrack(prepared.resources)
                session.emit(
                    "calibrationSession.loudness.started",
                    JSONObjectPayload.loudnessStarted(session, prepared.resources.track.sampleRate),
                    replyTo
                )
                CalibrationActivity.updateStatus(
                    sessionId,
                    "Set your normal listening volume.\nPink noise is playing at ${PinkNoiseGenerator.DEFAULT_LEVEL_DBFS} dBFS.\nLeave the volume unchanged, then press Continue on the TV."
                )
                CalibrationActivity.updatePrimaryAction(sessionId, "Continue")
                touchWatchdog()
                Thread {
                    playLoudness(session, prepared)
                }.apply {
                    name = "sweetspot-loudness-playback"
                    isDaemon = true
                    start()
                }
            } catch (error: Throwable) {
                finishWithError(session, "sweep_playback_failed", error.message ?: "Unable to play loudness reference")
            }
        }
    }

    fun stopLoudness(
        sessionId: String,
        replyTo: String?,
        emit: (String, org.json.JSONObject, String?) -> Unit
    ) {
        submit {
            val session = activeSession
            if (session == null || session.id != sessionId || state !is SessionState.Loudness) {
                emitError(emit, replyTo, sessionId, "invalid_session", "Loudness preflight is not active")
                return@submit
            }
            session.emit = emit
            session.replyTo = replyTo
            stopLoudnessInternal(session)
        }
    }

    private fun stopLoudnessInternal(session: Session) {
        state = SessionState.Finishing(session)
        try {
            stopAudioTrack()
            val sweep = prepareSweep(session.channel)
            state = SessionState.Ready(session, sweep, session.channel, null)
            session.emit("calibrationSession.loudness.stopped", JSONObjectPayload.session(session), session.replyTo)
            CalibrationActivity.updateStatus(session.id, "Volume locked.\nThe TV will guide the next measurement.")
            CalibrationActivity.updatePrimaryAction(session.id, null)
            touchWatchdog()
        } catch (error: Throwable) {
            finishWithError(session, "sweep_playback_failed", error.message ?: "Unable to prepare measurement sweep")
        }
    }

    @Suppress("UNUSED_PARAMETER")
    fun updateProgress(
        sessionId: String,
        stage: String,
        current: Int,
        total: Int,
        estimatedRemainingSeconds: Int?,
        message: String?
    ) {
        submit {
            val session = activeSession ?: return@submit
            if (session.id != sessionId || current < 0 || total < 1 || current > total) return@submit
            val stageLabel = when (stage) {
                "loudness" -> "Set listening volume"
                "preparing" -> "Preparing next sweep"
                "recording" -> "Recording measurement"
                "analyzing" -> "Analyzing measurement"
                "position-pause" -> "Move to the next position, then press Continue on the TV"
                "validation" -> "Validating correction"
                "ending" -> "Finishing calibration"
                else -> "Calibration"
            }
            val progress = "Calibration progress: $current of $total"
            val estimate = estimatedRemainingSeconds?.takeIf { it >= 0 }?.let { seconds ->
                val minutes = seconds / 60
                val remainder = seconds % 60
                if (minutes > 0) "Approx. ${minutes}m ${remainder}s remaining" else "Approx. ${remainder}s remaining"
            }
            val contextLabel = when (val current = state) {
                is SessionState.Ready -> current.context?.label()
                is SessionState.Playing -> current.context?.label()
                else -> null
            }
            val instruction = message?.takeIf { stage == "position-pause" && it.isNotBlank() }
            val text = listOfNotNull(progress, stageLabel, contextLabel, estimate, instruction).joinToString("\n")
            CalibrationActivity.updateStatus(sessionId, text)
            if (stage == "position-pause") CalibrationActivity.updatePrimaryAction(sessionId, "Continue")
            touchWatchdog()
        }
    }

    @Suppress("UNUSED_PARAMETER")
    fun updateDiagnostics(
        sessionId: String,
        context: MeasurementContext?,
        current: Int,
        total: Int,
        diagnostics: org.json.JSONObject
    ) {
        submit {
            val session = activeSession ?: return@submit
            if (session.id != sessionId || context == null || !context.isValid()) return@submit
            val readyContext = (state as? SessionState.Ready)?.context
            if (readyContext != null && readyContext != context) return@submit
            val stage = if (session.phase == "validation") "Validating correction" else "Analyzing measurement"
            CalibrationActivity.updateStatus(
                sessionId,
                "Calibration progress: $current of $total\n$stage\n${context.label()}\nKeep the iPhone still.",
            )
            touchWatchdog()
        }
    }

    internal fun updateResponse(response: MeasurementResponse) {
        submit {
            val session = activeSession ?: return@submit
            if (!shouldForwardMeasurementResponse(session.id, response) || state is SessionState.Finishing) return@submit
            CalibrationActivity.updateGraph(session.id, response)
            touchWatchdog()
        }
    }

    fun prepare(
        sessionId: String,
        channel: String?,
        context: MeasurementContext?,
        replyTo: String?,
        emit: (String, org.json.JSONObject, String?) -> Unit
    ) {
        submit {
            val session = activeSession
            if (sessionFence.shouldIgnore(sessionId)) return@submit
            if (session == null || session.id != sessionId) {
                emitError(emit, replyTo, sessionId, "invalid_session", "No matching calibration session")
                return@submit
            }
            session.emit = emit
            session.replyTo = replyTo
            when (val current = state) {
                is SessionState.Ready -> {
                    val route = context?.channel ?: channel ?: session.channel
                    if (!validChannel(route) || (context != null && !context.isValid())) {
                        emitError(emit, replyTo, sessionId, "invalid_session", "Invalid measurement channel or context")
                        return@submit
                    }
                    try {
                        stopAudioTrack()
                        val sweep = prepareSweep(route)
                        if (context == null || !context.sameLogicalTake(session.continuedPositionContext)) {
                            session.continuedPositionContext = null
                        }
                        state = SessionState.Ready(session, sweep, route, context)
                        emit("measurement.ready", JSONObjectPayload.ready(session, sweep, context), replyTo)
                        CalibrationActivity.updateStatus(sessionId, context?.readyStatus() ?: "TV ready. Follow the instructions shown here.")
                        CalibrationActivity.updatePrimaryAction(
                            sessionId,
                            context?.takeIf { it.requiresRemoteContinue() && !it.sameLogicalTake(session.continuedPositionContext) }
                                ?.let { "Continue" },
                        )
                        touchWatchdog()
                    } catch (error: Throwable) {
                        finishWithError(session, "sweep_playback_failed", error.message ?: "Unable to prepare routed sweep")
                    }
                }
                is SessionState.AwaitingUi -> touchWatchdog()
                is SessionState.Finishing -> Unit
                else -> emitError(emit, replyTo, sessionId, "invalid_session", "Session is not ready to prepare")
            }
        }
    }

    fun playSweep(
        sessionId: String,
        context: MeasurementContext?,
        replyTo: String?,
        emit: (String, org.json.JSONObject, String?) -> Unit
    ) {
        submit {
            val current = state
            val session = activeSession
            if (sessionFence.shouldIgnore(sessionId) || current is SessionState.Finishing) return@submit
            if (session == null || session.id != sessionId || current !is SessionState.Ready) {
                emitError(emit, replyTo, sessionId, "invalid_session", "Session is not ready for playback")
                return@submit
            }
            if (context != null && !context.isValid()) {
                emitError(emit, replyTo, sessionId, "invalid_session", "Invalid measurement context")
                return@submit
            }
            if ((current.context == null) != (context == null) ||
                (current.context != null && current.context != context)) {
                emitError(emit, replyTo, sessionId, "invalid_session", "Playback context does not match prepared sweep")
                return@submit
            }
            session.emit = emit
            session.replyTo = replyTo
            val playback = playbackResources
            if (playback == null) {
                finishWithError(session, "sweep_playback_failed", "Sweep AudioTrack is unavailable")
                return@submit
            }
            try {
                playTrack(playback)
                val playbackContext = context ?: current.context
                state = SessionState.Playing(session, current.sweep, current.channel, playbackContext)
                CalibrationActivity.updatePrimaryAction(sessionId, null)
                emit("measurement.started", JSONObjectPayload.started(session, current.sweep, playbackContext), replyTo)
                CalibrationActivity.updateStatus(sessionId, playbackContext?.let { "${it.label()}\nPlaying measurement sweep…" } ?: "Playing measurement sweep…")
                touchWatchdog()
                Thread {
                    playSweep(session, current.sweep, current.channel, playbackContext, playback)
                }.apply {
                    name = "sweetspot-sweep-playback"
                    isDaemon = true
                    start()
                }
            } catch (error: Throwable) {
                finishWithError(session, "sweep_playback_failed", error.message ?: "Unable to play sweep")
            }
        }
    }

    fun isActive(): Boolean = activeSession != null

    fun shutdown() {
        if (closed) return
        closed = true
        stopAudioTrack()
        val done = CountDownLatch(1)
        try {
            executor.execute {
                try {
                    activeSession?.let { finishCancelled(it, "calibration_aborted", "Service stopped") }
                } finally {
                    stopAudioTrack()
                    done.countDown()
                }
            }
            done.await(2, TimeUnit.SECONDS)
        } catch (_: Throwable) {
            stopAudioTrack()
        } finally {
            stopAudioTrack()
            executor.shutdownNow()
        }
    }

    private fun playSweep(
        session: Session,
        sweep: MeasurementSweep,
        channel: String,
        context: MeasurementContext?,
        playback: PlaybackResources
    ) {
        try {
            val buffer = ShortArray(PCM_CHUNK_SAMPLES)
            var firstFrame = 0
            while (firstFrame < sweep.totalFrames && activeSession === session && !playback.stopped) {
                val frameCount = minOf(PCM_CHUNK_FRAMES, sweep.totalFrames - firstFrame)
                MeasurementSweepGenerator.writeStereoPcm(
                    sweep,
                    channel,
                    firstFrame,
                    frameCount,
                    buffer
                )
                if (!writePcm(playback, buffer, frameCount * 2)) return
                firstFrame += frameCount
            }
            if (firstFrame != sweep.totalFrames || activeSession !== session || playback.stopped) return
            while (activeSession === session && !playback.stopped) {
                val playbackHeadPosition = playbackHeadPosition(playback) ?: return
                if (playbackHeadPosition >= sweep.totalFrames) break
                Thread.sleep(20)
            }
            if (playback.stopped) return
            submit {
                if (activeSession !== session ||
                    state !is SessionState.Playing ||
                    playbackResources !== playback ||
                    playback.stopped
                ) return@submit
                stopAudioTrack()
                state = SessionState.Ready(session, sweep, channel, context)
                touchWatchdog()
                session.emit("measurement.finished", JSONObjectPayload.finished(session, context), session.replyTo)
                CalibrationActivity.updateStatus(session.id, context?.let { "${it.label()}\nSweep finished. Keep the phone still." } ?: "Sweep finished. Keep the phone still or cancel.")
            }
        } catch (error: Throwable) {
            submit {
                if (activeSession === session && playbackResources === playback) {
                    finishWithError(session, "sweep_playback_failed", error.message ?: "Sweep playback failed")
                }
            }
        }
    }

    private fun playLoudness(session: Session, prepared: PreparedLoudness) {
        try {
            val buffer = ShortArray(PCM_CHUNK_SAMPLES)
            while (activeSession === session && !prepared.resources.stopped) {
                val frameCount = minOf(PCM_CHUNK_FRAMES, prepared.stream.remainingFrames)
                if (frameCount == 0) {
                    prepared.stream.reset()
                    continue
                }
                prepared.stream.write(buffer, frameCount)
                if (!writePcm(prepared.resources, buffer, frameCount * 2)) return
                if (prepared.stream.remainingFrames == 0) prepared.stream.reset()
            }
        } catch (error: Throwable) {
            submit {
                if (activeSession === session && playbackResources === prepared.resources) {
                    finishWithError(session, "sweep_playback_failed", error.message ?: "Loudness playback failed")
                }
            }
        }
    }

    private fun writePcm(playback: PlaybackResources, buffer: ShortArray, sampleCount: Int): Boolean {
        var offset = 0
        while (offset < sampleCount) {
            val written = synchronized(playback) {
                if (playback.stopped) null else playback.track.write(
                    buffer,
                    offset,
                    sampleCount - offset,
                    AudioTrack.WRITE_NON_BLOCKING
                )
            } ?: return false
            when {
                written > 0 -> offset += written
                written == 0 -> Thread.sleep(PCM_WRITE_RETRY_MS)
                else -> throw IllegalStateException("AudioTrack wrote $written samples")
            }
        }
        return true
    }

    private fun playTrack(playback: PlaybackResources) {
        synchronized(playback) {
            if (playback.stopped) throw IllegalStateException("AudioTrack is unavailable")
            playback.track.play()
        }
    }

    private fun playbackHeadPosition(playback: PlaybackResources): Int? = synchronized(playback) {
        if (playback.stopped) null else playback.track.playbackHeadPosition
    }

    private fun installPlaybackResources(track: AudioTrack): PlaybackResources {
        val resources = PlaybackResources(track)
        synchronized(playbackLock) {
            if (closed || playbackResources != null) {
                throw IllegalStateException("AudioTrack is unavailable")
            }
            playbackResources = resources
        }
        return resources
    }

    private fun prepareSweep(channel: String): MeasurementSweep {
        val nativeRate = AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_MUSIC)
        val candidateRates = listOf(48_000, nativeRate).filter { it > 0 }.distinct()
        var lastError: Throwable? = null
        for (sampleRate in candidateRates) {
            var track: AudioTrack? = null
            try {
                val minBuffer = AudioTrack.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_OUT_STEREO,
                    AudioFormat.ENCODING_PCM_16BIT
                )
                if (minBuffer == AudioTrack.ERROR_BAD_VALUE || minBuffer == AudioTrack.ERROR) continue
                val sweep = MeasurementSweep(sampleRate)
                val format = AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
                track = AudioTrack.Builder()
                    .setAudioAttributes(audioAttributes)
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(minBuffer)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
                val candidate = requireNotNull(track)
                if (candidate.state != AudioTrack.STATE_INITIALIZED) {
                    throw IllegalStateException("AudioTrack failed to initialize at $sampleRate Hz")
                }
                if (candidate.sampleRate != sampleRate) {
                    throw IllegalStateException("AudioTrack selected ${candidate.sampleRate} Hz for a $sampleRate Hz sweep")
                }
                installPlaybackResources(candidate)
                track = null
                Log.i(TAG, "AudioTrack prepared: rate=${candidate.sampleRate}, buffer=$minBuffer, frames=${sweep.totalFrames}")
                return sweep
            } catch (error: Throwable) {
                try { track?.release() } catch (_: Throwable) {}
                lastError = error
            }
        }
        throw lastError ?: IllegalStateException("No usable stereo PCM output rate")
    }

    private fun prepareLoudness(): PreparedLoudness {
        val nativeRate = AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_MUSIC)
        val candidateRates = listOf(48_000, nativeRate).filter { it > 0 }.distinct()
        var lastError: Throwable? = null
        for (sampleRate in candidateRates) {
            var track: AudioTrack? = null
            try {
                val minBuffer = AudioTrack.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_OUT_STEREO,
                    AudioFormat.ENCODING_PCM_16BIT
                )
                if (minBuffer == AudioTrack.ERROR_BAD_VALUE || minBuffer == AudioTrack.ERROR) continue
                val stream = PinkNoiseGenerator.createStereoStream(sampleRate)
                val format = AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
                track = AudioTrack.Builder()
                    .setAudioAttributes(audioAttributes)
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(minBuffer)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
                val candidate = requireNotNull(track)
                if (candidate.state != AudioTrack.STATE_INITIALIZED) {
                    throw IllegalStateException("AudioTrack failed to initialize at $sampleRate Hz")
                }
                if (candidate.sampleRate != sampleRate) {
                    throw IllegalStateException("AudioTrack selected ${candidate.sampleRate} Hz for pink noise")
                }
                val resources = installPlaybackResources(candidate)
                track = null
                Log.i(TAG, "Pink-noise AudioTrack prepared: rate=${candidate.sampleRate}, buffer=$minBuffer, frames=${stream.frameCount}")
                return PreparedLoudness(resources, stream)
            } catch (error: Throwable) {
                try { track?.release() } catch (_: Throwable) {}
                lastError = error
            }
        }
        throw lastError ?: IllegalStateException("No usable stereo PCM output rate for pink noise")
    }

    private fun requestExclusiveFocus(): Boolean {
        return try {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                .setAudioAttributes(audioAttributes)
                .setOnAudioFocusChangeListener({ change -> onFocusChange(change) }, mainHandler)
                .build()
            focusRequest = request
            val result = audioManager.requestAudioFocus(request)
            if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                focusRequest = null
                false
            } else {
                Log.i(TAG, "Exclusive audio focus granted")
                true
            }
        } catch (error: Throwable) {
            Log.w(TAG, "Exclusive audio focus request failed", error)
            focusRequest = null
            false
        }
    }

    private fun onFocusChange(change: Int) {
        if (change != AudioManager.AUDIOFOCUS_LOSS &&
            change != AudioManager.AUDIOFOCUS_LOSS_TRANSIENT &&
            change != AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK
        ) return
        submit {
            activeSession?.let {
                finishWithError(it, "audio_focus_lost", "Audio focus was lost")
            } ?: stopAudioTrack()
        }
    }

    private fun finishWithError(session: Session, code: String, message: String) {
        finishSession(session, code to message)
    }

    private fun finishCancelled(session: Session, code: String, message: String) {
        session.cancellationRequested = true
        finishSession(session, code to message)
    }

    private fun finishSession(session: Session, error: Pair<String, String>?) {
        if (activeSession !== session) return
        if (state is SessionState.ValidationFinalized) return
        if (state is SessionState.Finishing) return

        if (session.phase == "validation" && session.validationRecoveryResult != null) {
            presentValidationRecovery(session, session.validationAbortError ?: error)
            return
        }

        if (state is SessionState.AwaitingValidationFinalization && error == null) {
            showValidationFailure(session, "Validation finalization timed out")
            return
        }

        state = SessionState.Finishing(session)
        var finalError = error
        watchdog?.cancel(false)
        watchdog = null
        stopAudioTrack()

        focusRequest?.let { request ->
            try {
                audioManager.abandonAudioFocusRequest(request)
            } catch (_: Throwable) {
            }
        }
        focusRequest = null

        if (session.phase == "validation") {
            if (error != null) session.validationAbortError = error
            val originalError = session.validationAbortError
            if (error != null) {
                session.validationRecoveryResult = session.validationRecoveryGate.recover(
                    candidateId = session.candidateId,
                    restoreValidationState = { restoreValidationState(session) },
                    rollbackCandidate = rollbackCandidate,
                    verifyFinalState = finalStateVerifier,
                )
                finalError = validationRecoveryError(originalError, session.validationRecoveryResult!!)
                presentValidationRecovery(session, finalError)
                return
            }

            val restored = restoreValidationState(session)
            if (!restored) {
                val restoreError = "dsp_restore_failed" to
                    "The TV could not verify restoration of its previous audio state"
                session.validationAbortError = restoreError
                session.validationRecoveryResult = session.validationRecoveryGate.recover(
                    candidateId = session.candidateId,
                    restoreValidationState = { false },
                    rollbackCandidate = rollbackCandidate,
                    verifyFinalState = finalStateVerifier,
                )
                finalError = validationRecoveryError(restoreError, session.validationRecoveryResult!!)
                presentValidationRecovery(session, finalError)
                return
            }

            publishSessionEvents(session, null)
            state = SessionState.AwaitingValidationFinalization(session)
            CalibrationActivity.updateStatus(
                session.id,
                "Validation measurements complete.\nWaiting for the TV to confirm the final result.",
            )
            CalibrationActivity.updatePrimaryAction(session.id, null)
            touchWatchdog()
            Log.i(TAG, "Validation measurements ended: ${session.id}")
            return
        }

        if (!restoreMeasurementState()) {
            finalError = if (finalError == null) {
                "dsp_restore_failed" to "The TV could not verify restoration of its previous audio state"
            } else {
                finalError.first to "${finalError.second}. The TV could not verify restoration of its previous audio state."
            }
        }
        publishSessionEvents(session, finalError)
        CalibrationActivity.finishForSession(session.id)
        sessionFence.terminate(session.id)
        activeSession = null
        state = SessionState.Idle
        val outcome = if (session.cancellationRequested) "cancelled" else "error=${finalError?.first}"
        Log.i(TAG, "Calibration session ended: ${session.id}, outcome=$outcome")
    }

    private fun restoreValidationState(session: Session): Boolean {
        if (!session.validationOverrideApplied || session.validationOverrideRestored) return true
        val saved = bypassState ?: return false
        val restored = try {
            engine.endCalibrationValidation(saved)
        } catch (restoreError: Throwable) {
            Log.e(TAG, "Failed to restore validation DSP state", restoreError)
            false
        }
        bypassState = null
        if (restored) session.validationOverrideRestored = true
        return restored
    }

    private fun restoreMeasurementState(): Boolean {
        val saved = bypassState ?: return true
        val restored = try {
            engine.endMeasurementBypass(saved)
        } catch (restoreError: Throwable) {
            Log.e(TAG, "Failed to restore measurement DSP state", restoreError)
            false
        }
        bypassState = null
        return restored
    }

    private fun validationRecoveryError(
        originalError: Pair<String, String>?,
        recovery: ValidationRecoveryResult,
    ): Pair<String, String>? {
        if (recovery.finalStateVerified) return originalError
        val recoveryReason = when {
            !recovery.validationStateRestored ->
                "The TV could not verify restoration of its previous audio state"
            !recovery.candidateRolledBack ->
                "The previous calibration could not be verified after rollback"
            else ->
                "The final calibration state could not be verified"
        }
        val code = if (!recovery.validationStateRestored) {
            "dsp_restore_failed"
        } else {
            "candidate_rollback_failed"
        }
        val prefix = originalError?.let { "${it.first}: ${it.second}. " }.orEmpty()
        return code to prefix + recoveryReason
    }

    private fun presentValidationRecovery(session: Session, finalError: Pair<String, String>?) {
        if (activeSession !== session) return
        if (!session.validationRecoveryEventsPublished) {
            publishStateSnapshot(session)
            finalError?.let { (code, message) ->
                session.emit("measurement.error", JSONObjectPayload.error(session.id, code, message), session.replyTo)
            }
            publishSessionEnded(session)
            session.validationRecoveryEventsPublished = true
        }

        val recovery = session.validationRecoveryResult ?: return
        if (!recovery.finalStateVerified) {
            showValidationFailure(session, finalError?.second ?: "Calibration recovery could not be verified")
            return
        }

        val original = session.validationAbortError ?: finalError
        val outcome = if (original != null && isUserCalibrationCancellation(original.first)) {
            "cancelled"
        } else {
            "error"
        }
        val result = calibrationResultText(outcome, if (outcome == "error") original?.second else null)
        session.validationFinalizationBlocked = false
        session.validationFatal = false
        state = SessionState.ValidationFinalized(session)
        watchdog?.cancel(false)
        watchdog = null
        CalibrationActivity.updateStatus(session.id, "${result.title}\n${result.body}")
        CalibrationActivity.updatePrimaryAction(session.id, "Done")
        Log.i(TAG, "Validation recovery completed: ${session.id}, outcome=$outcome, code=${original?.first}")
    }

    private fun publishSessionEvents(session: Session, error: Pair<String, String>?) {
        error?.let { (code, message) ->
            session.emit("measurement.error", JSONObjectPayload.error(session.id, code, message), session.replyTo)
        }
        publishSessionEnded(session)
    }

    private fun publishSessionEnded(session: Session) {
        if (session.endedEventPublished) return
        session.emit("calibrationSession.ended", JSONObjectPayload.session(session), session.replyTo)
        session.endedEventPublished = true
    }

    private fun publishStateSnapshot(session: Session) {
        try {
            session.emit("state.snapshot", stateSnapshotProvider(), session.replyTo)
        } catch (error: Throwable) {
            Log.e(TAG, "Failed to publish final calibration state", error)
        }
    }

    private fun defaultCalibrationAbortMessage(code: String): String = when {
        isUserCalibrationCancellation(code) -> "Calibration cancelled"
        else -> "Calibration could not be validated"
    }

    private fun showValidationFailure(session: Session, message: String) {
        if (activeSession !== session) return
        session.validationFinalizationBlocked = true
        session.validationFatal = true
        state = SessionState.AwaitingValidationFinalization(session)
        watchdog?.cancel(false)
        watchdog = null
        CalibrationActivity.updateStatus(
            session.id,
            "Calibration recovery error.\n$message\nThe candidate transaction remains available for recovery.",
        )
        CalibrationActivity.updatePrimaryAction(session.id, "Close")
    }

    private fun closeValidationUi(session: Session) {
        if (activeSession !== session) return
        watchdog?.cancel(false)
        watchdog = null
        CalibrationActivity.finishForSession(session.id)
        sessionFence.terminate(session.id)
        activeSession = null
        state = SessionState.Idle
    }

    private fun stopAudioTrack() {
        val resources = synchronized(playbackLock) {
            val current = playbackResources
            playbackResources = null
            current?.stopped = true
            current
        } ?: return
        synchronized(resources) {
            try { resources.track.stop() } catch (_: Throwable) {}
            try { resources.track.flush() } catch (_: Throwable) {}
            try { resources.track.release() } catch (_: Throwable) {}
        }
    }

    private fun touchWatchdog() {
        watchdog?.cancel(false)
        watchdog = executor.schedule({
            activeSession?.let { session ->
                if (state is SessionState.AwaitingValidationFinalization) {
                    showValidationFailure(session, "The TV did not receive a final calibration result in time")
                } else {
                    finishWithError(session, "measurement_timeout", "Calibration session timed out")
                }
            }
        }, WATCHDOG_MS, TimeUnit.MILLISECONDS)
    }

    private fun validSessionId(value: String): Boolean = isValidMeasurementSessionId(value)

    private fun validChannel(value: String): Boolean = value == "both" || value == "left" || value == "right"

    private fun validPhase(value: String): Boolean = value == "measurement" || value == "validation"

    private fun emitError(
        emit: (String, org.json.JSONObject, String?) -> Unit,
        replyTo: String?,
        sessionId: String,
        code: String,
        message: String
    ) {
        emit("measurement.error", JSONObjectPayload.error(sessionId, code, message), replyTo)
    }

    private fun submit(block: () -> Unit) {
        if (closed) return
        try {
            executor.execute(block)
        } catch (error: Throwable) {
            Log.e(TAG, "Measurement command rejected", error)
        }
    }

    private object JSONObjectPayload {
        fun session(session: Session): org.json.JSONObject =
            org.json.JSONObject()
                .put("sessionId", session.id)
                .put("channel", session.channel)
                .put("phase", session.phase)

        fun ready(
            session: Session,
            sweep: MeasurementSweep,
            context: MeasurementContext? = null
        ): org.json.JSONObject =
            session(session).put("sweep", sweep(sweep)).also { payload ->
                context?.let { payload.put("context", it.toJson()) }
            }

        fun started(
            session: Session,
            sweep: MeasurementSweep,
            context: MeasurementContext? = null
        ): org.json.JSONObject =
            session(session).put("sweep", sweep(sweep)).also { payload ->
                context?.let { payload.put("context", it.toJson()) }
            }

        fun finished(session: Session, context: MeasurementContext? = null): org.json.JSONObject =
            org.json.JSONObject().put("sessionId", session.id).also { payload ->
                context?.let { payload.put("context", it.toJson()) }
            }

        fun error(sessionId: String, code: String, message: String): org.json.JSONObject =
            org.json.JSONObject().put("sessionId", sessionId).put("code", code).put("message", message)

        fun loudnessStarted(session: Session, sampleRate: Int): org.json.JSONObject =
            session(session)
                .put("sampleRate", sampleRate)
                .put("levelDbfs", PinkNoiseGenerator.DEFAULT_LEVEL_DBFS)
                .put("loopDurationMs", PinkNoiseGenerator.DEFAULT_LOOP_DURATION_MS)

        fun positionContinued(session: Session, context: MeasurementContext): org.json.JSONObject =
            org.json.JSONObject()
                .put("sessionId", session.id)
                .put("context", context.toJson())

        private fun sweep(sweep: MeasurementSweep): org.json.JSONObject =
            org.json.JSONObject()
                .put("algorithm", sweep.algorithm)
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
                .put("levelDbfs", sweep.levelDbfs)
                .put("fadeInMs", sweep.fadeInMs)
                .put("fadeOutMs", sweep.fadeOutMs)
    }
}
