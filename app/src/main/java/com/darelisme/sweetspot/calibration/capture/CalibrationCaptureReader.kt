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
import java.nio.charset.StandardCharsets
import org.json.JSONObject

fun interface CalibrationCaptureMetadataParser {
    fun parse(metadataJson: String, pcmBytes: Int): CaptureUploadMetadata
}

internal class CalibrationCaptureReader(
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
        require(pcmBytes > 0) { "PCM payload is empty" }
        val value = JSONObject(json)
        val settings = parseSettings(value)
        val sampleCount = exactLong(value.opt("sampleCount"), "sampleCount")
        val byteCount = exactLong(value.opt("byteCount"), "byteCount")
        val microphoneProfile = (value.opt("microphoneProfile") as? JSONObject)
            ?.let(::parseMicrophoneProfile)
            ?: throw IllegalArgumentException("microphoneProfile must be an object")
        val metadata = CaptureUploadMetadata(
            jobId = CalibrationJobId(requiredString(value, "jobId")),
            captureId = CaptureId(requiredString(value, "captureId")),
            position = enumValue(requiredString(value, "positionId", "position"), CalibrationPosition.entries),
            attemptIndex = exactInt(value.opt("attemptIndex"), "attemptIndex"),
            channel = enumValue(requiredString(value, "channel"), CaptureChannel.entries),
            sampleRateHz = exactInt(value.opt("sampleRate"), "sampleRate"),
            channelCount = exactInt(value.opt("channelCount"), "channelCount"),
            sampleCount = sampleCount,
            browserCaptureSettings = settings,
            userAgent = requiredString(value, "userAgent", "browserUserAgent"),
            microphoneProfileId = requiredString(value, "microphoneProfileId", "micProfileId"),
            microphoneProfileRevision = requiredString(value, "microphoneProfileRevision", "micProfileRevision"),
            microphoneProfile = microphoneProfile,
            capturedAtMs = exactLong(value.opt("capturedAtMs") ?: value.opt("captureTimestamp"), "capturedAtMs"),
            contentSha256 = requiredString(value, "contentSha256"),
            byteCount = byteCount,
        ).normalized()
        require(metadata.byteCount == pcmBytes.toLong()) { "PCM byte count does not match payload" }
        return metadata
    }

    private fun parseSettings(value: JSONObject): Map<String, String> {
        val settingsValue = when {
            value.has("settings") -> value.opt("settings")
            value.has("browserCaptureSettings") -> value.opt("browserCaptureSettings")
            else -> throw IllegalArgumentException("Capture settings are required")
        } as? JSONObject ?: throw IllegalArgumentException("Capture settings must be an object")
        val keys = settingsValue.keys()
        val result = LinkedHashMap<String, String>()
        while (keys.hasNext()) {
            val key = keys.next()
            if (key.isBlank() || key.length > 4_096 || key.toByteArray(StandardCharsets.UTF_8).size > 4_096) {
                throw IllegalArgumentException("Capture setting name is invalid")
            }
            if (result.size >= 64) throw IllegalArgumentException("Too many capture settings")
            val raw = settingsValue.opt(key)
            val setting = when (raw) {
                JSONObject.NULL -> ""
                is String -> raw
                is Boolean -> raw.toString()
                is Number -> raw.toString().takeIf { raw.toDouble().isFinite() }
                else -> null
            } ?: throw IllegalArgumentException("Capture setting $key is invalid")
            if (setting.length > 4_096 || setting.toByteArray(StandardCharsets.UTF_8).size > 4_096) {
                throw IllegalArgumentException("Capture setting $key is too large")
            }
            result[key] = setting
        }
        return result
    }

    private fun parseMicrophoneProfile(value: JSONObject): CalibrationMicrophoneProfilePayload {
        val frequenciesValue = value.opt("frequenciesHz") as? org.json.JSONArray
            ?: throw IllegalArgumentException("Microphone profile frequencies are required")
        val responseValue = value.opt("responseDb") as? org.json.JSONArray
            ?: throw IllegalArgumentException("Microphone profile response is required")
        require(frequenciesValue.length() == responseValue.length()) {
            "Microphone profile frequency and response arrays must match"
        }
        require(frequenciesValue.length() in CalibrationMicrophoneProfilePayload.MIN_POINTS..CalibrationMicrophoneProfilePayload.MAX_POINTS) {
            "Microphone profile point count is outside the supported range"
        }
        return CalibrationMicrophoneProfilePayload(
            id = requiredString(value, "id"),
            revision = requiredString(value, "revision"),
            frequenciesHz = FloatArray(frequenciesValue.length()) { finiteFloat(frequenciesValue.opt(it), "frequency") },
            responseDb = FloatArray(responseValue.length()) { finiteFloat(responseValue.opt(it), "response") },
            normalizeAtHz = finiteFloat(value.opt("normalizeAtHz"), "normalizeAtHz"),
            trustMinHz = finiteFloat(value.opt("trustMinHz"), "trustMinHz"),
            trustFullMaxHz = finiteFloat(value.opt("trustFullMaxHz"), "trustFullMaxHz"),
            trustTaperToHz = finiteFloat(value.opt("trustTaperToHz"), "trustTaperToHz"),
            capturePathStatus = requiredString(value, "capturePathStatus"),
        )
    }

    private fun requiredString(value: JSONObject, vararg keys: String): String {
        for (key in keys) {
            if (!value.has(key)) continue
            val text = value.opt(key) as? String
                ?: throw IllegalArgumentException("$key must be a string")
            if (text.isBlank()) throw IllegalArgumentException("$key must not be blank")
            return text
        }
        throw IllegalArgumentException("${keys.first()} is required")
    }

    private fun exactLong(value: Any?, key: String): Long {
        val number = value as? Number ?: throw IllegalArgumentException("$key must be an integer")
        val decimal = try { number.toString().toBigDecimal() } catch (_: NumberFormatException) {
            throw IllegalArgumentException("$key must be an integer")
        }
        if (decimal < java.math.BigDecimal.ZERO
            || decimal.remainder(java.math.BigDecimal.ONE).signum() != 0
        ) throw IllegalArgumentException("$key must be an integer")
        return try {
            decimal.longValueExact()
        } catch (_: ArithmeticException) {
            throw IllegalArgumentException("$key is outside the supported range")
        }
    }

    private fun exactInt(value: Any?, key: String): Int {
        val number = exactLong(value, key)
        if (number > Int.MAX_VALUE) throw IllegalArgumentException("$key is outside the supported range")
        return number.toInt()
    }

    private fun finiteFloat(value: Any?, key: String): Float {
        val number = value as? Number ?: throw IllegalArgumentException("$key must be numeric")
        val converted = number.toDouble().toFloat()
        if (!number.toDouble().isFinite() || !converted.isFinite()) {
            throw IllegalArgumentException("$key must be finite")
        }
        return converted
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
