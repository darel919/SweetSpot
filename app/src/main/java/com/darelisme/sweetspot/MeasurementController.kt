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

class MeasurementController(
    private val context: Context,
    private val engine: AudioEngine
) {
    companion object {
        private const val TAG = "SweetSpotMeasurement"
        private const val WATCHDOG_MS = 60_000L
        private const val MAX_SESSION_ID_LENGTH = 64
    }

    private sealed interface SessionState {
        data object Idle : SessionState
        data class AwaitingUi(val session: Session) : SessionState
        data class Ready(val session: Session, val sweep: MeasurementSweep) : SessionState
        data class Playing(val session: Session, val sweep: MeasurementSweep) : SessionState
        data class Finishing(val session: Session) : SessionState
    }

    private data class Session(
        val id: String,
        val channel: String,
        var emit: (String, org.json.JSONObject, String?) -> Unit,
        var replyTo: String?
    )

    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "sweetspot-measurement").apply { isDaemon = true }
    }

    @Volatile
    private var activeSession: Session? = null
    private var state: SessionState = SessionState.Idle
    private var focusRequest: AudioFocusRequest? = null
    @Volatile
    private var audioTrack: AudioTrack? = null
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
        replyTo: String?,
        emit: (String, org.json.JSONObject, String?) -> Unit
    ) {
        submit {
            if (!validSessionId(sessionId) || !validChannel(channel)) {
                emitError(emit, replyTo, sessionId, "invalid_session", "Invalid session or channel")
                return@submit
            }
            if (activeSession != null || state !is SessionState.Idle) {
                emitError(emit, replyTo, sessionId, "already_measuring", "Another calibration session is active")
                return@submit
            }

            val session = Session(sessionId, channel, emit, replyTo)
            activeSession = session
            state = SessionState.AwaitingUi(session)
            touchWatchdog()
            CalibrationActivity.updateStatus(sessionId, "Opening calibration mode…")
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
                bypassState = engine.beginMeasurementBypass()
                val sweep = prepareSweep()
                state = SessionState.Ready(session, sweep)
                session.emit("calibrationSession.started", JSONObjectPayload.session(session), session.replyTo)
                session.emit("measurement.ready", JSONObjectPayload.ready(session, sweep), session.replyTo)
                CalibrationActivity.updateStatus(sessionId, "TV ready. Follow the instructions shown here.")
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
                finishWithError(session, "calibration_ui_closed", "Calibration UI closed unexpectedly")
            }
        }
    }

    fun cancel(sessionId: String, replyTo: String?, emit: (String, org.json.JSONObject, String?) -> Unit) {
        submit {
            val session = activeSession
            if (session == null || session.id != sessionId) {
                emitError(emit, replyTo, sessionId, "invalid_session", "No matching calibration session")
                return@submit
            }
            session.emit = emit
            session.replyTo = replyTo
            finishWithError(session, "calibration_aborted", "Calibration cancelled")
        }
    }

    fun cancelFromActivity(sessionId: String) {
        submit {
            activeSession?.let { session ->
                if (session.id == sessionId) finishWithError(session, "calibration_aborted", "Calibration cancelled")
            }
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

    fun prepare(sessionId: String, replyTo: String?, emit: (String, org.json.JSONObject, String?) -> Unit) {
        submit {
            val session = activeSession
            if (session == null || session.id != sessionId) {
                emitError(emit, replyTo, sessionId, "invalid_session", "No matching calibration session")
                return@submit
            }
            session.emit = emit
            session.replyTo = replyTo
            when (val current = state) {
                is SessionState.Ready -> {
                    emit("measurement.ready", JSONObjectPayload.ready(session, current.sweep), replyTo)
                    touchWatchdog()
                }
                is SessionState.AwaitingUi -> touchWatchdog()
                else -> emitError(emit, replyTo, sessionId, "invalid_session", "Session is not ready to prepare")
            }
        }
    }

    fun playSweep(sessionId: String, replyTo: String?, emit: (String, org.json.JSONObject, String?) -> Unit) {
        submit {
            val current = state
            val session = activeSession
            if (session == null || session.id != sessionId || current !is SessionState.Ready) {
                emitError(emit, replyTo, sessionId, "invalid_session", "Session is not ready for playback")
                return@submit
            }
            session.emit = emit
            session.replyTo = replyTo
            val track = audioTrack
            if (track == null) {
                finishWithError(session, "sweep_playback_failed", "Sweep AudioTrack is unavailable")
                return@submit
            }
            try {
                track.play()
                state = SessionState.Playing(session, current.sweep)
                emit("measurement.started", JSONObjectPayload.started(session, current.sweep), replyTo)
                CalibrationActivity.updateStatus(sessionId, "Playing measurement sweep…")
                touchWatchdog()
                Thread {
                    waitForPlayback(session, current.sweep, track)
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
        val done = CountDownLatch(1)
        try {
            executor.execute {
                activeSession?.let { finishWithError(it, "calibration_aborted", "Service stopped") }
                done.countDown()
            }
            done.await(2, TimeUnit.SECONDS)
        } catch (_: Throwable) {
            stopAudioTrack()
        } finally {
            stopAudioTrack()
            executor.shutdownNow()
        }
    }

    private fun waitForPlayback(session: Session, sweep: MeasurementSweep, track: AudioTrack) {
        try {
            while (activeSession === session && state is SessionState.Playing) {
                if (track.playbackHeadPosition >= sweep.totalFrames) break
                Thread.sleep(20)
            }
            submit {
                if (activeSession !== session || state !is SessionState.Playing) return@submit
                stopAudioTrack()
                state = SessionState.Ready(session, sweep)
                touchWatchdog()
                session.emit("measurement.finished", JSONObjectPayload.finished(session), session.replyTo)
                CalibrationActivity.updateStatus(session.id, "Sweep finished. Keep the phone still or cancel.")
            }
        } catch (error: Throwable) {
            submit {
                if (activeSession === session) finishWithError(session, "sweep_playback_failed", error.message ?: "Sweep playback failed")
            }
        }
    }

    private fun prepareSweep(): MeasurementSweep {
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
                val pcm = MeasurementSweepGenerator.generateStereoPcm(sweep)
                val format = AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
                track = AudioTrack.Builder()
                    .setAudioAttributes(audioAttributes)
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(maxOf(minBuffer, pcm.size * 2))
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()
                val candidate = requireNotNull(track)
                if (candidate.state != AudioTrack.STATE_INITIALIZED) {
                    throw IllegalStateException("AudioTrack failed to initialize at $sampleRate Hz")
                }
                if (candidate.sampleRate != sampleRate) {
                    throw IllegalStateException("AudioTrack selected ${candidate.sampleRate} Hz for a $sampleRate Hz sweep")
                }
                val written = candidate.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING)
                if (written != pcm.size) {
                    throw IllegalStateException("AudioTrack wrote $written of ${pcm.size} samples")
                }
                audioTrack = candidate
                track = null
                return sweep
            } catch (error: Throwable) {
                try { track?.release() } catch (_: Throwable) {}
                lastError = error
            }
        }
        throw lastError ?: IllegalStateException("No usable stereo PCM output rate")
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
        stopAudioTrack()
        submit {
            activeSession?.let { finishWithError(it, "audio_focus_lost", "Audio focus was lost") }
        }
    }

    private fun finishWithError(session: Session, code: String, message: String) {
        finishSession(session, code to message)
    }

    private fun finishSession(session: Session, error: Pair<String, String>?) {
        if (activeSession !== session) return
        state = SessionState.Finishing(session)
        watchdog?.cancel(false)
        watchdog = null
        stopAudioTrack()
        bypassState?.let { saved ->
            try {
                engine.endMeasurementBypass(saved)
            } catch (restoreError: Throwable) {
                Log.e(TAG, "Failed to restore measurement DSP state", restoreError)
            }
            bypassState = null
        }
        focusRequest?.let { request ->
            try {
                audioManager.abandonAudioFocusRequest(request)
            } catch (_: Throwable) {
            }
        }
        focusRequest = null
        CalibrationActivity.finishForSession(session.id)
        activeSession = null
        state = SessionState.Idle
        error?.let { (code, message) ->
            session.emit("measurement.error", JSONObjectPayload.error(session.id, code, message), session.replyTo)
        }
        session.emit("calibrationSession.ended", JSONObjectPayload.session(session), session.replyTo)
        Log.i(TAG, "Calibration session ended: ${session.id}, error=${error?.first}")
    }

    private fun stopAudioTrack() {
        val track = audioTrack ?: return
        audioTrack = null
        try { track.stop() } catch (_: Throwable) {}
        try { track.flush() } catch (_: Throwable) {}
        try { track.release() } catch (_: Throwable) {}
    }

    private fun touchWatchdog() {
        watchdog?.cancel(false)
        watchdog = executor.schedule({
            activeSession?.let { session ->
                finishWithError(session, "measurement_timeout", "Calibration session timed out")
            }
        }, WATCHDOG_MS, TimeUnit.MILLISECONDS)
    }

    private fun validSessionId(value: String): Boolean =
        value.isNotBlank() && value.length <= MAX_SESSION_ID_LENGTH && value.none { it.isWhitespace() }

    private fun validChannel(value: String): Boolean = value == "both" || value == "left" || value == "right"

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
            org.json.JSONObject().put("sessionId", session.id).put("channel", session.channel)

        fun ready(session: Session, sweep: MeasurementSweep): org.json.JSONObject =
            session(session).put("sweep", sweep(sweep))

        fun started(session: Session, sweep: MeasurementSweep): org.json.JSONObject =
            session(session).put("sweep", sweep(sweep))

        fun finished(session: Session): org.json.JSONObject =
            org.json.JSONObject().put("sessionId", session.id)

        fun error(sessionId: String, code: String, message: String): org.json.JSONObject =
            org.json.JSONObject().put("sessionId", sessionId).put("code", code).put("message", message)

        private fun sweep(sweep: MeasurementSweep): org.json.JSONObject =
            org.json.JSONObject()
                .put("algorithm", sweep.algorithm)
                .put("sampleRate", sweep.sampleRate)
                .put("startHz", sweep.startHz)
                .put("endHz", sweep.endHz)
                .put("durationMs", sweep.durationMs)
                .put("preRollMs", sweep.preRollMs)
                .put("postRollMs", sweep.postRollMs)
                .put("levelDbfs", sweep.levelDbfs)
                .put("fadeInMs", sweep.fadeInMs)
                .put("fadeOutMs", sweep.fadeOutMs)
    }
}
