package com.darelisme.sweetspot.calibration.playback

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log

internal class PlaybackResources(val track: AudioTrack) {
    @Volatile
    var stopped = false
}

internal data class PreparedLoudness(
    val resources: PlaybackResources,
    val stream: PinkNoiseGenerator.StereoStream,
)

internal class MeasurementAudioPlayback(
    private val audioAttributes: AudioAttributes,
    private val available: () -> Boolean,
) {
    companion object {
        private const val TAG = "SweetSpotMeasurement"
        internal const val PCM_CHUNK_FRAMES = 4_096
        internal const val PCM_CHUNK_SAMPLES = PCM_CHUNK_FRAMES * 2
        private const val PCM_WRITE_RETRY_MS = 2L
    }

    @Volatile
    var resources: PlaybackResources? = null
        private set

    private val lock = Any()
    private var preparedSweep: MeasurementSweep? = null

    fun stop() {
        val current = synchronized(lock) {
            val current = resources
            resources = null
            preparedSweep = null
            current?.stopped = true
            current
        } ?: return
        synchronized(current) {
            try {
                current.track.stop()
            } catch (_: Throwable) {
            }
            try {
                current.track.flush()
            } catch (_: Throwable) {
            }
            try {
                current.track.release()
            } catch (_: Throwable) {
            }
        }
    }

    fun pause() {
        val current = resources ?: return
        synchronized(current) {
            current.stopped = true
            try {
                current.track.stop()
            } catch (_: Throwable) {
            }
            try {
                current.track.flush()
            } catch (_: Throwable) {
            }
            current.stopped = false
        }
    }

    fun play(resources: PlaybackResources) {
        synchronized(resources) {
            if (resources.stopped) throw IllegalStateException("AudioTrack is unavailable")
            resources.track.play()
        }
    }

    fun playbackHeadPosition(resources: PlaybackResources): Int? = synchronized(resources) {
        if (resources.stopped) null else resources.track.playbackHeadPosition
    }

    fun writePcm(resources: PlaybackResources, buffer: ShortArray, sampleCount: Int): Boolean {
        var offset = 0
        while (offset < sampleCount) {
            val written = synchronized(resources) {
                if (resources.stopped) null else resources.track.write(
                    buffer,
                    offset,
                    sampleCount - offset,
                    AudioTrack.WRITE_NON_BLOCKING,
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

    fun prepareSweep(channel: String, captureKind: String = "position-composite"): MeasurementSweep {
        preparedSweep?.let { sweep ->
            if (resources != null && sweep.captureKind == captureKind) return sweep
            if (resources != null && sweep.captureKind != captureKind) stop()
        }
        val nativeRate = AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_MUSIC)
        val candidateRates = listOf(48_000, nativeRate).filter { it > 0 }.distinct()
        var lastError: Throwable? = null
        for (sampleRate in candidateRates) {
            var track: AudioTrack? = null
            try {
                val minBuffer = AudioTrack.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_OUT_STEREO,
                    AudioFormat.ENCODING_PCM_16BIT,
                )
                if (minBuffer == AudioTrack.ERROR_BAD_VALUE || minBuffer == AudioTrack.ERROR) continue
                val sweep = MeasurementSweep(sampleRate, captureKind = captureKind)
                track = createTrack(sampleRate, minBuffer)
                val candidate = requireNotNull(track)
                verifyTrack(candidate, sampleRate) { "AudioTrack selected ${candidate.sampleRate} Hz for a $sampleRate Hz sweep" }
                install(candidate)
                track = null
                preparedSweep = sweep
                Log.i(TAG, "AudioTrack prepared: rate=${candidate.sampleRate}, buffer=$minBuffer, frames=${sweep.totalFrames}")
                return sweep
            } catch (error: Throwable) {
                try {
                    track?.release()
                } catch (_: Throwable) {
                }
                lastError = error
            }
        }
        throw lastError ?: IllegalStateException("No usable stereo PCM output rate")
    }

    fun prepareLoudness(): PreparedLoudness {
        val nativeRate = AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_MUSIC)
        val candidateRates = listOf(48_000, nativeRate).filter { it > 0 }.distinct()
        var lastError: Throwable? = null
        for (sampleRate in candidateRates) {
            var track: AudioTrack? = null
            try {
                val minBuffer = AudioTrack.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_OUT_STEREO,
                    AudioFormat.ENCODING_PCM_16BIT,
                )
                if (minBuffer == AudioTrack.ERROR_BAD_VALUE || minBuffer == AudioTrack.ERROR) continue
                val stream = PinkNoiseGenerator.createStereoStream(sampleRate)
                track = createTrack(sampleRate, minBuffer)
                val candidate = requireNotNull(track)
                verifyTrack(candidate, sampleRate) { "AudioTrack selected ${candidate.sampleRate} Hz for pink noise" }
                val resources = install(candidate)
                track = null
                Log.i(TAG, "Pink-noise AudioTrack prepared: rate=${candidate.sampleRate}, buffer=$minBuffer, frames=${stream.frameCount}")
                return PreparedLoudness(resources, stream)
            } catch (error: Throwable) {
                try {
                    track?.release()
                } catch (_: Throwable) {
                }
                lastError = error
            }
        }
        throw lastError ?: IllegalStateException("No usable stereo PCM output rate for pink noise")
    }

    private fun createTrack(sampleRate: Int, bufferSize: Int): AudioTrack =
        AudioTrack.Builder()
            .setAudioAttributes(audioAttributes)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build(),
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

    private fun verifyTrack(track: AudioTrack, expectedRate: Int, rateError: () -> String) {
        if (track.state != AudioTrack.STATE_INITIALIZED) {
            throw IllegalStateException("AudioTrack failed to initialize at $expectedRate Hz")
        }
        if (track.sampleRate != expectedRate) {
            throw IllegalStateException(rateError())
        }
    }

    private fun install(track: AudioTrack): PlaybackResources {
        val result = PlaybackResources(track)
        synchronized(lock) {
            if (!available() || resources != null) {
                throw IllegalStateException("AudioTrack is unavailable")
            }
            resources = result
        }
        return result
    }
}
