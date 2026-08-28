package com.darelisme.sweetspot.calibration.capture

import com.darelisme.sweetspot.calibration.analysis.AnalysisChannel
import com.darelisme.sweetspot.calibration.model.CalibrationMicrophoneProfilePayload
import com.darelisme.sweetspot.calibration.model.CalibrationPosition
import com.darelisme.sweetspot.calibration.model.CalibrationJobId
import com.darelisme.sweetspot.calibration.model.CaptureId
import com.darelisme.sweetspot.calibration.model.CaptureChannel
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.json.JSONObject

fun interface CalibrationCaptureMetadataParser {
    fun parse(metadataJson: String, pcmBytes: Int): CaptureUploadMetadata
}

internal class CalibrationCaptureReader(
    private val nowMs: () -> Long,
    private val metadataParser: CalibrationCaptureMetadataParser?,
) {
    fun parse(metadataJson: String, pcmBytes: Int): CaptureUploadMetadata =
        metadataParser?.parse(metadataJson, pcmBytes) ?: parseDefault(metadataJson, pcmBytes)

    fun readFloat32(input: InputStream, expectedByteCount: Long): FloatArray {
        require(expectedByteCount > 0L && expectedByteCount % 4L == 0L) {
            "Calibration capture byte count is not aligned to Float32 samples"
        }
        require(expectedByteCount <= Int.MAX_VALUE.toLong()) {
            "Calibration capture is too large to analyze"
        }
        val samples = FloatArray((expectedByteCount / 4L).toInt())
        val buffer = ByteArray(16 * 1024)
        val carry = ByteArray(3)
        var carryBytes = 0
        var bytesRead = 0L
        var sampleIndex = 0
        while (bytesRead < expectedByteCount) {
            val requested = minOf(buffer.size.toLong(), expectedByteCount - bytesRead).toInt()
            val read = input.read(buffer, 0, requested)
            if (read < 0) break
            if (read == 0) continue
            bytesRead += read
            var offset = 0
            if (carryBytes > 0) {
                val needed = 4 - carryBytes
                if (read < needed) {
                    buffer.copyInto(carry, carryBytes, 0, read)
                    carryBytes += read
                    continue
                }
                buffer.copyInto(carry, carryBytes, 0, needed)
                carry.copyInto(buffer, 0, 0, carryBytes)
                samples[sampleIndex++] = ByteBuffer.wrap(buffer, 0, 4)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .float
                carryBytes = 0
                offset = needed
            }
            val completeBytes = (read - offset) / 4 * 4
            if (completeBytes > 0) {
                ByteBuffer.wrap(buffer, offset, completeBytes)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .asFloatBuffer()
                    .get(samples, sampleIndex, completeBytes / 4)
                sampleIndex += completeBytes / 4
            }
            val remainder = read - offset - completeBytes
            if (remainder > 0) {
                buffer.copyInto(carry, 0, offset + completeBytes, offset + completeBytes + remainder)
                carryBytes = remainder
            }
        }
        require(bytesRead == expectedByteCount && carryBytes == 0 && sampleIndex == samples.size) {
            "Calibration capture ended before the expected sample count"
        }
        require(input.read() == -1) { "Calibration capture contains trailing bytes" }
        return samples
    }

    private fun parseDefault(json: String, pcmBytes: Int): CaptureUploadMetadata {
        val value = JSONObject(json)
        val settingsValue = value.optJSONObject("browserCaptureSettings")
            ?: value.optJSONObject("settings")
            ?: JSONObject()
        val settings = buildMap {
            val keys = settingsValue.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                put(key, settingsValue.opt(key)?.toString() ?: "")
            }
        }
        val sampleCount = value.getLong("sampleCount")
        val byteCount = value.optLong("byteCount", pcmBytes.toLong())
        val microphoneProfile = value.getJSONObject("microphoneProfile").let(::parseMicrophoneProfile)
        val metadata = CaptureUploadMetadata(
            jobId = CalibrationJobId(value.getString("jobId")),
            captureId = CaptureId(value.getString("captureId")),
            position = enumValue(value.optString("positionId", value.optString("position")), CalibrationPosition.entries),
            attemptIndex = value.getInt("attemptIndex"),
            channel = enumValue(value.optString("channel"), CaptureChannel.entries),
            sampleRateHz = value.getInt("sampleRate"),
            channelCount = value.getInt("channelCount"),
            sampleCount = sampleCount,
            browserCaptureSettings = settings,
            userAgent = value.optString("userAgent", value.optString("browserUserAgent")),
            microphoneProfileId = value.optString("microphoneProfileId", value.optString("micProfileId")),
            microphoneProfileRevision = value.optString("microphoneProfileRevision", value.optString("micProfileRevision", "unknown")),
            microphoneProfile = microphoneProfile,
            capturedAtMs = value.optLong("capturedAtMs", value.optLong("captureTimestamp", nowMs())),
            contentSha256 = value.getString("contentSha256"),
            byteCount = byteCount,
        ).normalized()
        require(metadata.byteCount == pcmBytes.toLong()) { "PCM byte count does not match payload" }
        return metadata
    }

    private fun parseMicrophoneProfile(value: JSONObject): CalibrationMicrophoneProfilePayload {
        val frequenciesValue = value.getJSONArray("frequenciesHz")
        val responseValue = value.getJSONArray("responseDb")
        require(frequenciesValue.length() == responseValue.length()) {
            "Microphone profile frequency and response arrays must match"
        }
        require(frequenciesValue.length() in CalibrationMicrophoneProfilePayload.MIN_POINTS..CalibrationMicrophoneProfilePayload.MAX_POINTS) {
            "Microphone profile point count is outside the supported range"
        }
        return CalibrationMicrophoneProfilePayload(
            id = value.getString("id"),
            revision = value.getString("revision"),
            frequenciesHz = FloatArray(frequenciesValue.length()) { frequenciesValue.getDouble(it).toFloat() },
            responseDb = FloatArray(responseValue.length()) { responseValue.getDouble(it).toFloat() },
            normalizeAtHz = value.getDouble("normalizeAtHz").toFloat(),
            trustMinHz = value.getDouble("trustMinHz").toFloat(),
            trustFullMaxHz = value.getDouble("trustFullMaxHz").toFloat(),
            trustTaperToHz = value.getDouble("trustTaperToHz").toFloat(),
            capturePathStatus = value.getString("capturePathStatus"),
        )
    }

    private inline fun <reified T : Enum<T>> enumValue(raw: String, values: Iterable<T>): T {
        val normalized = raw.trim().replace('-', '_').uppercase()
        return values.firstOrNull { it.name == normalized }
            ?: throw IllegalArgumentException("Unknown calibration enum $raw")
    }
}

internal fun CaptureUploadMetadata.analysisChannel(): AnalysisChannel = when (channel) {
    CaptureChannel.LEFT -> AnalysisChannel.LEFT
    CaptureChannel.RIGHT -> AnalysisChannel.RIGHT
    CaptureChannel.BOTH -> AnalysisChannel.BOTH
}
