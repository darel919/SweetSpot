package com.darelisme.sweetspot.calibration.playback

import com.darelisme.sweetspot.calibration.model.MeasurementContext

internal class MeasurementAudioRunner(
    private val audioPlayback: MeasurementAudioPlayback,
    private val isSessionActive: (Session) -> Boolean,
) {
    fun playSweep(
        session: Session,
        sweep: MeasurementSweep,
        channel: String,
        context: MeasurementContext?,
        playback: PlaybackResources,
        onFinished: () -> Unit,
        onFailure: (Throwable) -> Unit,
    ) {
        try {
            val buffer = ShortArray(MeasurementAudioPlayback.PCM_CHUNK_SAMPLES)
            var firstFrame = 0
            while (firstFrame < sweep.totalFrames && isSessionActive(session) && !playback.stopped) {
                val frameCount = minOf(MeasurementAudioPlayback.PCM_CHUNK_FRAMES, sweep.totalFrames - firstFrame)
                MeasurementSweepGenerator.writeStereoPcm(
                    sweep,
                    channel,
                    firstFrame,
                    frameCount,
                    buffer,
                )
                if (!audioPlayback.writePcm(playback, buffer, frameCount * 2)) return
                firstFrame += frameCount
            }
            if (firstFrame != sweep.totalFrames || !isSessionActive(session) || playback.stopped) return
            while (isSessionActive(session) && !playback.stopped) {
                val playbackHeadPosition = audioPlayback.playbackHeadPosition(playback) ?: return
                if (playbackHeadPosition >= sweep.totalFrames) break
                Thread.sleep(20)
            }
            if (playback.stopped) return
            onFinished()
        } catch (error: Throwable) {
            onFailure(error)
        }
    }

    fun playLoudness(
        session: Session,
        prepared: PreparedLoudness,
        onFailure: (Throwable) -> Unit,
    ) {
        try {
            val buffer = ShortArray(MeasurementAudioPlayback.PCM_CHUNK_SAMPLES)
            while (isSessionActive(session) && !prepared.resources.stopped) {
                val frameCount = minOf(MeasurementAudioPlayback.PCM_CHUNK_FRAMES, prepared.stream.remainingFrames)
                if (frameCount == 0) {
                    prepared.stream.reset()
                    continue
                }
                prepared.stream.write(buffer, frameCount)
                if (!audioPlayback.writePcm(prepared.resources, buffer, frameCount * 2)) return
                if (prepared.stream.remainingFrames == 0) prepared.stream.reset()
            }
        } catch (error: Throwable) {
            onFailure(error)
        }
    }
}
