package com.darelisme.sweetspot.calibration.playback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import com.darelisme.sweetspot.audio.engine.AudioEngine
import com.darelisme.sweetspot.audio.engine.AudioOperationGate
import com.darelisme.sweetspot.audio.engine.MeasurementAudioOverrideResult
import com.darelisme.sweetspot.audio.engine.MeasurementAudioState
import com.darelisme.sweetspot.calibration.model.*
import com.darelisme.sweetspot.calibration.playback.MeasurementSweep
import com.darelisme.sweetspot.calibration.playback.MeasurementSweepGenerator
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Plays TV-owned calibration sweeps while the browser records the microphone. */
internal class TvCalibrationPlayback(
    private val context: Context,
    private val audioEngine: AudioEngine,
    private val audioOperationGate: AudioOperationGate,
    private val sweep: MeasurementSweep,
) : CalibrationPlaybackPort, AutoCloseable {
    private data class Active(
        val request: CaptureRequest,
        val candidateId: String?,
        val track: AudioTrack,
        val savedState: MeasurementAudioState,
        val finished: () -> Unit,
        val failed: (CalibrationAudioResult.Failure) -> Unit,
        var stopped: Boolean = false,
        var notifyFinished: Boolean = true,
    )

    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "sweetspot-tv-calibration-playback").apply { isDaemon = true }
    }
    private val lock = Any()
    private var active: Active? = null
    private var closed = false

    override fun start(request: CaptureRequest, onFinished: () -> Unit): CalibrationAudioResult =
        startInternal(request, null, onFinished, {})

    override fun startWithFailure(
        request: CaptureRequest,
        onFinished: () -> Unit,
        onFailure: (CalibrationAudioResult.Failure) -> Unit,
    ): CalibrationAudioResult = startInternal(request, null, onFinished, onFailure)

    override fun startValidation(action: CalibrationAction.Validate, onFinished: () -> Unit): CalibrationAudioResult =
        startInternal(
            CaptureRequest(
                captureId = action.captureId,
                position = action.position,
                channel = CaptureChannel.BOTH,
                attemptIndex = action.attemptIndex,
                optional = false,
            ),
            action.candidateId.value,
            onFinished,
            {},
        )

    override fun startValidationWithFailure(
        action: CalibrationAction.Validate,
        onFinished: () -> Unit,
        onFailure: (CalibrationAudioResult.Failure) -> Unit,
    ): CalibrationAudioResult = startInternal(
        CaptureRequest(
            captureId = action.captureId,
            position = action.position,
            channel = CaptureChannel.BOTH,
            attemptIndex = action.attemptIndex,
            optional = false,
        ),
        action.candidateId.value,
        onFinished,
        onFailure,
    )

    override fun cancel(request: CaptureRequest): CalibrationAudioResult {
        val current = synchronized(lock) {
            if (active?.request?.captureId != request.captureId) return CalibrationAudioResult.Success()
            active?.apply {
                stopped = true
                notifyFinished = false
                try { track.pause() } catch (_: Throwable) {}
                try { track.flush() } catch (_: Throwable) {}
            }
        }
        val restored = current?.let(::finish) ?: true
        return if (restored) {
            CalibrationAudioResult.Success()
        } else {
            CalibrationAudioResult.Failure(
                "The TV could not restore its previous audio state",
                "dsp_restore_failed",
                false,
            )
        }
    }

    override fun close() {
        val current = synchronized(lock) {
            if (closed) return
            closed = true
            active?.apply {
                stopped = true
                notifyFinished = false
            }
            active
        }
        current?.track?.let { track ->
            try { track.pause() } catch (_: Throwable) {}
            try { track.flush() } catch (_: Throwable) {}
        }
        executor.shutdownNow()
        current?.let { finish(it) }
    }

    private fun startInternal(
        request: CaptureRequest,
        candidateId: String?,
        onFinished: () -> Unit,
        onFailure: (CalibrationAudioResult.Failure) -> Unit,
    ): CalibrationAudioResult {
        synchronized(lock) {
            if (closed || active != null) return CalibrationAudioResult.Failure("Calibration playback is already active")
        }
        if (!audioOperationGate.tryAcquireTransient()) {
            return CalibrationAudioResult.Failure("Another audio operation is active")
        }
        val override = try {
            if (candidateId == null) audioEngine.beginMeasurementBypass()
            else audioEngine.beginCalibrationValidation(candidateId)
        } catch (error: Throwable) {
            audioOperationGate.releaseTransient()
            return CalibrationAudioResult.Failure(error.message ?: "Calibration audio state could not be prepared")
        }
        val savedState = when (override) {
            is MeasurementAudioOverrideResult.Applied -> override.previousState
            is MeasurementAudioOverrideResult.Failed -> {
                audioOperationGate.releaseTransient()
                return CalibrationAudioResult.Failure(
                    message = override.error,
                    code = if (override.restored) "playback_prepare_failed" else "dsp_restore_failed",
                    retryable = override.restored,
                )
            }
        }
        val track = try { createTrack() } catch (error: Throwable) {
            val restored = restore(savedState, candidateId)
            audioOperationGate.releaseTransient()
            return CalibrationAudioResult.Failure(
                message = error.message ?: "Calibration playback could not start",
                code = if (restored) "playback_prepare_failed" else "dsp_restore_failed",
                retryable = restored,
            )
        }
        val next = Active(request, candidateId, track, savedState, onFinished, onFailure)
        synchronized(lock) {
            if (closed || active != null) {
                try { track.release() } catch (_: Throwable) {}
                val restored = restore(savedState, candidateId)
                audioOperationGate.releaseTransient()
                return CalibrationAudioResult.Failure(
                    message = "Calibration playback is unavailable",
                    code = if (restored) "playback_unavailable" else "dsp_restore_failed",
                    retryable = restored,
                )
            }
            active = next
        }
        try {
            track.play()
            executor.execute { play(next) }
            return CalibrationAudioResult.Success()
        } catch (error: Throwable) {
            synchronized(lock) {
                next.stopped = true
                next.notifyFinished = false
            }
            val restored = finish(next)
            return CalibrationAudioResult.Failure(
                message = error.message ?: "Calibration playback could not start",
                code = if (restored) "playback_start_failed" else "dsp_restore_failed",
                retryable = restored,
            )
        }
    }

    private fun createTrack(): AudioTrack {
        val minBuffer = AudioTrack.getMinBufferSize(
            sweep.sampleRate,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        require(minBuffer > 0) { "AudioTrack rejected the calibration sample rate" }
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sweep.sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
            .build()
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setAudioFormat(format)
            .setBufferSizeInBytes(minBuffer.coerceAtLeast(sweep.sampleRate / 5))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
            .also { require(it.state == AudioTrack.STATE_INITIALIZED) { "AudioTrack failed to initialize" } }
    }

    private fun play(current: Active) {
        var failure: String? = null
        try {
            val buffer = ShortArray(4_096 * 2)
            var firstFrame = 0
            val channel = current.request.channel.name.lowercase()
            while (firstFrame < sweep.totalFrames && isRunning(current)) {
                val frameCount = minOf(4_096, sweep.totalFrames - firstFrame)
                MeasurementSweepGenerator.writeStereoPcm(sweep, channel, firstFrame, frameCount, buffer)
                var offset = 0
                while (offset < frameCount * 2 && isRunning(current)) {
                    val written = current.track.write(buffer, offset, frameCount * 2 - offset, AudioTrack.WRITE_NON_BLOCKING)
                    when {
                        written > 0 -> offset += written
                        written == 0 -> Thread.sleep(2)
                        else -> throw IllegalStateException("AudioTrack wrote $written samples")
                    }
                }
                firstFrame += frameCount
            }
            while (isRunning(current) && current.track.playbackHeadPosition < sweep.totalFrames) Thread.sleep(20)
        } catch (error: Throwable) {
            if (isRunning(current)) {
                failure = error.message ?: "Calibration playback failed"
                Log.w(TAG, "TV calibration sweep failed: $failure")
            }
        } finally {
            finish(current, failure)
        }
    }

    private fun isRunning(current: Active): Boolean = synchronized(lock) {
        active === current && !current.stopped && !closed
    }

    private fun finish(current: Active, failure: String? = null): Boolean {
        synchronized(lock) {
            if (active !== current) return true
            active = null
        }
        try { current.track.stop() } catch (_: Throwable) {}
        try { current.track.release() } catch (_: Throwable) {}
        val restored = restore(current.savedState, current.candidateId)
        audioOperationGate.releaseTransient()
        if (!restored) Log.e(TAG, "Calibration audio state could not be restored")
        val effectiveFailure = failure?.let {
            CalibrationAudioResult.Failure(it, "playback_failed", restored)
        } ?: if (!restored) {
            CalibrationAudioResult.Failure(
                "The TV could not restore its previous audio state",
                "dsp_restore_failed",
                false,
            )
        } else null
        if (effectiveFailure != null && current.notifyFinished) current.failed(effectiveFailure)
        else if (current.notifyFinished) current.finished()
        return restored
    }

    private fun restore(state: MeasurementAudioState, candidateId: String?): Boolean = try {
        if (candidateId == null) audioEngine.endMeasurementBypass(state)
        else audioEngine.endCalibrationValidation(state)
    } catch (_: Throwable) {
        false
    }

    private companion object {
        const val TAG = "SweetSpotCalibration"
    }
}
