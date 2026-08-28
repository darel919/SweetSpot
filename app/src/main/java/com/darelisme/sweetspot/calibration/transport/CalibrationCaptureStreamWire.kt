package com.darelisme.sweetspot.calibration.transport

import org.json.JSONObject
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

sealed interface CalibrationCaptureStreamFrame {
    val sessionId: String
    val captureId: String
    val captureAttemptId: String

    data class Begin(
        override val sessionId: String,
        override val captureId: String,
        val metadataJson: String,
        val expectedSampleCount: Long?,
        val expectedByteCount: Long?,
        override val captureAttemptId: String = "legacy-attempt",
    ) : CalibrationCaptureStreamFrame

    data class Chunk(
        override val sessionId: String,
        override val captureId: String,
        val sequence: Long,
        val sampleCount: Long,
        val pcm: ByteArray,
        override val captureAttemptId: String = "legacy-attempt",
    ) : CalibrationCaptureStreamFrame

    data class End(
        override val sessionId: String,
        override val captureId: String,
        val chunkCount: Long,
        val finalSampleCount: Long,
        val finalByteCount: Long,
        val finalSha256: String,
        val metadataJson: String,
        override val captureAttemptId: String = "legacy-attempt",
    ) : CalibrationCaptureStreamFrame
}

object CalibrationCaptureStreamWire {
    const val VERSION = 1
    const val MAX_FRAME_BYTES = 32 * 1024
    const val MAX_CHUNK_BYTES = 16 * 1024
    const val HEADER_BYTES = 16

    private val MAGIC = byteArrayOf(0x53, 0x53, 0x43, 0x53)

    fun decode(bytes: ByteArray): CalibrationCaptureStreamFrame {
        if (bytes.size < HEADER_BYTES) throw IOException("Capture stream frame header is incomplete")
        if (bytes.size > MAX_FRAME_BYTES) throw IOException("Capture stream frame exceeds the size limit")
        if (!bytes.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) {
            throw IOException("Capture stream magic does not match")
        }
        val header = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        if (header.getShort(4).toInt() and 0xffff != VERSION) {
            throw IOException("Capture stream version is unsupported")
        }
        val kind = header.get(6).toInt() and 0xff
        if (header.get(7).toInt() != 0) throw IOException("Capture stream reserved bits are not zero")
        val headerLength = header.getInt(8).toLong() and 0xffff_ffffL
        val payloadLength = header.getInt(12).toLong() and 0xffff_ffffL
        if (headerLength > MAX_FRAME_BYTES || payloadLength > MAX_FRAME_BYTES) {
            throw IOException("Capture stream lengths exceed the size limit")
        }
        val payloadOffset = HEADER_BYTES + headerLength
        if (payloadOffset > bytes.size.toLong() || payloadOffset + payloadLength != bytes.size.toLong()) {
            throw IOException("Capture stream payload length does not match the frame")
        }
        val value = try {
            val headerText = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, HEADER_BYTES, headerLength.toInt()))
                .toString()
            JSONObject(headerText)
        } catch (error: Exception) {
            throw IOException("Capture stream header is not valid UTF-8 JSON", error)
        }
        val sessionId = boundedId(requiredString(value, "sessionId"))
        val captureId = boundedId(requiredString(value, "captureId"))
        val captureAttemptId = boundedId(requiredString(value, "captureAttemptId"))
        val declaredKind = requiredString(value, "kind")
        val expectedKind = when (kind) {
            1 -> "begin"
            2 -> "chunk"
            3 -> "end"
            else -> throw IOException("Capture stream frame kind is unsupported")
        }
        if (declaredKind != expectedKind) throw IOException("Capture stream header has the wrong kind")
        return when (kind) {
            1 -> decodeBegin(value, sessionId, captureId, captureAttemptId, payloadLength)
            2 -> decodeChunk(value, sessionId, captureId, captureAttemptId, bytes, payloadOffset, payloadLength)
            else -> decodeEnd(value, sessionId, captureId, captureAttemptId, payloadLength)
        }
    }

    private fun decodeBegin(
        value: JSONObject,
        sessionId: String,
        captureId: String,
        captureAttemptId: String,
        payloadLength: Long,
    ): CalibrationCaptureStreamFrame.Begin {
        if (payloadLength != 0L || !value.has("metadata") || value.opt("metadata") !is JSONObject) {
            throw IOException("Capture stream begin header is invalid")
        }
        val metadata = value.getJSONObject("metadata")
        if ((metadata.opt("captureId") as? String) != captureId) {
            throw IOException("Capture stream begin capture ID does not match its metadata")
        }
        val expectedSampleCount = optionalCount(value, "expectedSampleCount")
        val expectedByteCount = optionalCount(value, "expectedByteCount")
        if ((expectedSampleCount == null) != (expectedByteCount == null)) {
            throw IOException("Capture stream expected counts must be provided together")
        }
        if (expectedSampleCount != null && expectedByteCount != sampleByteCount(expectedSampleCount)) {
            throw IOException("Capture stream expected byte count does not match samples")
        }
        return CalibrationCaptureStreamFrame.Begin(
            sessionId = sessionId,
            captureId = captureId,
            metadataJson = metadata.toString(),
            expectedSampleCount = expectedSampleCount,
            expectedByteCount = expectedByteCount,
            captureAttemptId = captureAttemptId,
        )
    }

    private fun decodeChunk(
        value: JSONObject,
        sessionId: String,
        captureId: String,
        captureAttemptId: String,
        bytes: ByteArray,
        payloadOffset: Long,
        payloadLength: Long,
    ): CalibrationCaptureStreamFrame.Chunk {
        val sequence = requiredCount(value, "sequence")
        val sampleCount = requiredCount(value, "sampleCount")
        if (sampleCount <= 0 || sampleByteCount(sampleCount) != payloadLength || payloadLength > MAX_CHUNK_BYTES) {
            throw IOException("Capture stream chunk payload is invalid")
        }
        return CalibrationCaptureStreamFrame.Chunk(
            sessionId = sessionId,
            captureId = captureId,
            sequence = sequence,
            sampleCount = sampleCount,
            pcm = bytes.copyOfRange(payloadOffset.toInt(), bytes.size),
            captureAttemptId = captureAttemptId,
        )
    }

    private fun decodeEnd(
        value: JSONObject,
        sessionId: String,
        captureId: String,
        captureAttemptId: String,
        payloadLength: Long,
    ): CalibrationCaptureStreamFrame.End {
        if (payloadLength != 0L || !value.has("metadata") || value.opt("metadata") !is JSONObject) {
            throw IOException("Capture stream end header is invalid")
        }
        val chunkCount = requiredCount(value, "chunkCount")
        val finalSampleCount = requiredCount(value, "finalSampleCount")
        val finalByteCount = requiredCount(value, "finalByteCount")
        val finalSha256 = requiredString(value, "finalSha256")
        val metadata = value.getJSONObject("metadata")
        if ((metadata.opt("captureId") as? String) != captureId
            || finalByteCount != sampleByteCount(finalSampleCount)
            || !finalSha256.matches(Regex("[a-fA-F0-9]{64}"))
            || (metadata.opt("sampleCount") as? Number)?.let(::exactCount) != finalSampleCount
            || (metadata.opt("byteCount") as? Number)?.let(::exactCount) != finalByteCount
            || (metadata.opt("contentSha256") as? String)?.equals(finalSha256, ignoreCase = true) != true
        ) {
            throw IOException("Capture stream end header is invalid")
        }
        return CalibrationCaptureStreamFrame.End(
            sessionId = sessionId,
            captureId = captureId,
            chunkCount = chunkCount,
            finalSampleCount = finalSampleCount,
            finalByteCount = finalByteCount,
            finalSha256 = finalSha256.lowercase(),
            metadataJson = metadata.toString(),
            captureAttemptId = captureAttemptId,
        )
    }

    private fun boundedId(value: String): String {
        if (value.isBlank() || value.length > 128) throw IOException("Capture stream identity is invalid")
        return value
    }

    private fun requiredString(value: JSONObject, key: String): String = value.opt(key) as? String
        ?: throw IOException("Capture stream $key is invalid")

    private fun requiredCount(value: JSONObject, key: String): Long {
        val number = value.opt(key) as? Number
            ?: throw IOException("Capture stream $key is invalid")
        return exactCount(number) ?: throw IOException("Capture stream $key is invalid")
    }

    private fun exactCount(number: Number): Long? = try {
        val decimal = number.toString().toBigDecimal()
        if (decimal < java.math.BigDecimal.ZERO
            || decimal.remainder(java.math.BigDecimal.ONE).signum() != 0
        ) return null
        decimal.longValueExact()
    } catch (_: ArithmeticException) {
        null
    } catch (_: NumberFormatException) {
        null
    }

    private fun optionalCount(value: JSONObject, key: String): Long? {
        if (!value.has(key) || value.isNull(key)) return null
        return requiredCount(value, key)
    }

    private fun sampleByteCount(sampleCount: Long): Long {
        if (sampleCount > Long.MAX_VALUE / 4L) throw IOException("Capture stream sample count is too large")
        return sampleCount * 4L
    }
}
