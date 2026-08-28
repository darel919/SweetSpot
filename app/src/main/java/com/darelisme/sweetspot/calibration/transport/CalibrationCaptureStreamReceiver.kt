package com.darelisme.sweetspot.calibration.transport

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.math.BigDecimal
import java.security.MessageDigest
import java.util.LinkedHashMap

/**
 * Receives one bounded capture stream without retaining the complete PCM in memory.
 * The completed file is handed to the calibration engine, then deleted by the
 * owner after the engine has persisted or rejected it.
 */
class CalibrationCaptureStreamReceiver(
    private val rootDirectory: File,
    private val maxBytesPerCapture: Long = 2L * 1024L * 1024L,
) {
    data class Completed(
        val sessionId: String,
        val captureId: String,
        val captureAttemptId: String,
        val metadataJson: String,
        val pcmFile: File,
        val sampleCount: Long,
        val byteCount: Long,
        val sha256: String,
        val duplicate: Boolean = false,
    )

    private var active: Active? = null
    private val finalized = LinkedHashMap<StreamKey, Finalized>()

    init {
        require(maxBytesPerCapture > 0)
    }

    @Synchronized
    fun accept(frame: CalibrationCaptureStreamFrame): Completed? = when (frame) {
        is CalibrationCaptureStreamFrame.Begin -> begin(frame)
        is CalibrationCaptureStreamFrame.Chunk -> append(frame)
        is CalibrationCaptureStreamFrame.End -> finish(frame)
    }

    @Synchronized
    fun cancel() {
        active?.let(::deleteActive)
        active = null
    }

    /** Cancels only the stream identified by the frame that caused a failure. */
    @Synchronized
    fun cancel(sessionId: String, captureId: String): Boolean {
        return cancel(sessionId, captureId, null)
    }

    /** Cancels only the stream identified by its authenticated capture attempt. */
    @Synchronized
    fun cancel(sessionId: String, captureId: String, captureAttemptId: String?): Boolean {
        val current = active ?: return false
        if (current.sessionId != sessionId || current.captureId != captureId
            || (captureAttemptId != null && current.captureAttemptId != captureAttemptId)
        ) return false
        deleteActive(current)
        active = null
        return true
    }

    @Synchronized
    fun delete(completed: Completed) {
        if (completed.pcmFile.exists() && !completed.pcmFile.delete()) {
            throw IOException("Unable to delete temporary streamed capture")
        }
    }

    @Synchronized
    fun cleanup() {
        cancel()
        finalized.clear()
        if (!rootDirectory.isDirectory) return
        rootDirectory.listFiles()?.forEach { file ->
            if (file.name.endsWith(PARTIAL_SUFFIX) || file.name.endsWith(READY_SUFFIX)) file.delete()
        }
    }

    private fun begin(frame: CalibrationCaptureStreamFrame.Begin): Completed? {
        val key = StreamKey(frame.sessionId, frame.captureId, frame.captureAttemptId)
        finalized[key]?.let { return duplicateBegin(it, frame) }
        val current = active
        if (current != null) {
            if (current.sessionId != frame.sessionId || current.captureId != frame.captureId) {
                throw IOException("Another calibration capture is already streaming")
            }
            if (current.captureAttemptId != frame.captureAttemptId) {
                deleteActive(current)
                active = null
            } else {
                if (current.metadataJson != frame.metadataJson
                    || current.expectedSampleCount != frame.expectedSampleCount
                    || current.expectedByteCount != frame.expectedByteCount
                ) {
                    throw IOException("Calibration capture begin conflicts with the active stream")
                }
                return null
            }
        }
        if (frame.expectedByteCount != null && frame.expectedByteCount > maxBytesPerCapture) {
            throw IOException("Calibration capture exceeds the temporary capture limit")
        }
        if (frame.metadataJson.toByteArray(Charsets.UTF_8).size > MAX_METADATA_BYTES) {
            throw IOException("Calibration capture metadata exceeds the size limit")
        }
        frame.expectedSampleCount?.let(::validatePositiveCount)
        frame.expectedByteCount?.let(::validatePositiveCount)
        if (frame.expectedSampleCount != null && frame.expectedByteCount != sampleByteCount(frame.expectedSampleCount)) {
            throw IOException("Calibration capture begin counts do not match")
        }
        ensureDirectory(rootDirectory)
        val token = hash("${frame.sessionId}:${frame.captureId}:${frame.captureAttemptId}")
        val partial = File(rootDirectory, "$token$PARTIAL_SUFFIX")
        val ready = File(rootDirectory, "$token$READY_SUFFIX")
        partial.delete()
        ready.delete()
        validateBeginMetadata(frame.metadataJson, frame.captureId)
        active = Active(
            sessionId = frame.sessionId,
            captureId = frame.captureId,
            captureAttemptId = frame.captureAttemptId,
            metadataJson = frame.metadataJson,
            expectedSampleCount = frame.expectedSampleCount,
            expectedByteCount = frame.expectedByteCount,
            partialFile = partial,
            readyFile = ready,
            output = FileOutputStream(partial),
            digest = MessageDigest.getInstance("SHA-256"),
        )
        return null
    }

    private fun append(frame: CalibrationCaptureStreamFrame.Chunk): Completed? {
        if (finalized.containsKey(StreamKey(frame.sessionId, frame.captureId, frame.captureAttemptId))) return null
        val current = requireActive(frame.sessionId, frame.captureId, frame.captureAttemptId)
        if (frame.sequence < 0L || frame.sequence >= MAX_CAPTURE_CHUNKS) {
            abort("Calibration capture has too many chunks")
        }
        if (frame.sampleCount <= 0) {
            abort("Calibration capture chunk sample count does not match PCM")
        }
        val declaredBytes = try {
            sampleByteCount(frame.sampleCount)
        } catch (_: IOException) {
            abort("Calibration capture chunk sample count is invalid")
        }
        if (declaredBytes != frame.pcm.size.toLong()) {
            abort("Calibration capture chunk sample count does not match PCM")
        }
        val expectedSequence = current.nextSequence
        if (frame.sequence < expectedSequence) {
            val previous = current.chunkDigests[frame.sequence]
            if (previous != null && previous.sampleCount == frame.sampleCount
                && previous.sha256 == digestHex(frame.pcm)
            ) return null
            throw IOException("Calibration capture chunk was duplicated with different data")
        }
        if (frame.sequence != expectedSequence) throw IOException("Calibration capture chunk sequence is not contiguous")
        if (current.byteCount > maxBytesPerCapture
            || frame.pcm.size.toLong() > maxBytesPerCapture - current.byteCount
        ) throw IOException("Calibration capture exceeds the temporary capture limit")
        val nextBytes = current.byteCount + frame.pcm.size
        if (current.expectedByteCount != null && nextBytes > current.expectedByteCount) {
            throw IOException("Calibration capture exceeds its declared byte count")
        }
        try {
            validateFinitePcm(frame.pcm)
            current.output.write(frame.pcm)
        } catch (error: IOException) {
            abort(error.message ?: "Calibration capture chunk is invalid")
        }
        current.digest.update(frame.pcm)
        current.byteCount = nextBytes
        current.sampleCount += frame.sampleCount
        current.nextSequence++
        current.chunkDigests[frame.sequence] = ChunkDigest(frame.sampleCount, digestHex(frame.pcm))
        return null
    }

    private fun finish(frame: CalibrationCaptureStreamFrame.End): Completed {
        val key = StreamKey(frame.sessionId, frame.captureId, frame.captureAttemptId)
        finalized[key]?.let { return duplicateEnd(it, frame) }
        val current = requireActive(frame.sessionId, frame.captureId, frame.captureAttemptId)
        validateCount(frame.chunkCount)
        validateCount(frame.finalSampleCount)
        validateCount(frame.finalByteCount)
        if (frame.finalSampleCount <= 0 || frame.finalByteCount <= 0) {
            abort("Calibration capture final counts are empty")
        }
        val expectedFinalBytes = try {
            sampleByteCount(frame.finalSampleCount)
        } catch (_: IOException) {
            abort("Calibration capture final sample count is invalid")
        }
        if (frame.finalByteCount != expectedFinalBytes) {
            abort("Calibration capture final counts do not match samples")
        }
        if (frame.chunkCount != current.nextSequence
            || frame.finalSampleCount != current.sampleCount
            || frame.finalByteCount != current.byteCount
        ) {
            abort("Calibration capture final counts do not match the received stream")
        }
        if (current.expectedSampleCount != null && current.expectedSampleCount != current.sampleCount) {
            abort("Calibration capture sample count does not match its declaration")
        }
        if (current.expectedByteCount != null && current.expectedByteCount != current.byteCount) {
            abort("Calibration capture byte count does not match its declaration")
        }
        if (!metadataMatchesBegin(current.metadataJson, frame.metadataJson)) {
            abort("Calibration capture end metadata conflicts with its begin metadata")
        }
        val actualHash = current.digest.digest().toHex()
        if (!actualHash.equals(frame.finalSha256, ignoreCase = true)) {
            abort("Calibration capture SHA-256 does not match the received stream")
        }
        try {
            current.output.flush()
            current.output.fd.sync()
            current.output.close()
            moveAtomically(current.partialFile, current.readyFile)
        } catch (error: IOException) {
            abort(error.message ?: "Calibration capture could not be finalized")
        }
        val completed = Completed(
            sessionId = current.sessionId,
            captureId = current.captureId,
            captureAttemptId = current.captureAttemptId,
            metadataJson = frame.metadataJson,
            pcmFile = current.readyFile,
            sampleCount = current.sampleCount,
            byteCount = current.byteCount,
            sha256 = actualHash,
        )
        finalized[key] = Finalized(
            metadataJson = completed.metadataJson,
            sampleCount = completed.sampleCount,
            byteCount = completed.byteCount,
            sha256 = completed.sha256,
        )
        while (finalized.size > MAX_FINALIZED_CAPTURES) {
            finalized.entries.iterator().also { iterator ->
                iterator.next()
                iterator.remove()
            }
        }
        active = null
        return completed
    }

    private fun duplicateBegin(finalized: Finalized, frame: CalibrationCaptureStreamFrame.Begin): Completed {
        if (!metadataMatchesBegin(frame.metadataJson, finalized.metadataJson)
            || (frame.expectedSampleCount != null && finalized.sampleCount != frame.expectedSampleCount)
            || (frame.expectedByteCount != null && finalized.byteCount != frame.expectedByteCount)
        ) {
            throw IOException("Calibration capture begin conflicts with the finalized stream")
        }
        return finalized.completed(rootDirectory, frame.sessionId, frame.captureId, frame.captureAttemptId)
    }

    private fun duplicateEnd(finalized: Finalized, frame: CalibrationCaptureStreamFrame.End): Completed {
        if (finalized.sampleCount != frame.finalSampleCount
            || finalized.byteCount != frame.finalByteCount
            || !finalized.sha256.equals(frame.finalSha256, ignoreCase = true)
            || !metadataMatchesBegin(finalized.metadataJson, frame.metadataJson)
        ) {
            throw IOException("Calibration capture conflicts with the finalized stream")
        }
        return finalized.completed(rootDirectory, frame.sessionId, frame.captureId, frame.captureAttemptId)
    }

    private fun requireActive(sessionId: String, captureId: String, captureAttemptId: String): Active = active?.also {
        if (it.sessionId != sessionId || it.captureId != captureId || it.captureAttemptId != captureAttemptId) {
            throw IOException("Calibration capture does not belong to the active stream")
        }
    } ?: throw IOException("Calibration capture stream has not started")

    private fun abort(message: String): Nothing {
        val current = active
        if (current != null) {
            try { current.output.close() } catch (_: Throwable) {}
            current.partialFile.delete()
            current.readyFile.delete()
        }
        active = null
        throw IOException(message)
    }

    private fun deleteActive(current: Active) {
        try { current.output.close() } catch (_: Throwable) {}
        current.partialFile.delete()
        current.readyFile.delete()
    }

    private fun validateFinitePcm(bytes: ByteArray) {
        if (bytes.isEmpty() || bytes.size % Float32_BYTES != 0) {
            throw IOException("Calibration capture PCM chunk is not Float32 aligned")
        }
        val input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        while (input.hasRemaining()) {
            if (!input.float.isFinite()) throw IOException("Calibration capture PCM contains a non-finite sample")
        }
    }

    private fun ensureDirectory(directory: File) {
        if ((!directory.exists() && !directory.mkdirs()) || !directory.isDirectory) {
            throw IOException("Unable to create streamed capture directory")
        }
    }

    private data class Active(
        val sessionId: String,
        val captureId: String,
        val captureAttemptId: String,
        val metadataJson: String,
        val expectedSampleCount: Long?,
        val expectedByteCount: Long?,
        val partialFile: File,
        val readyFile: File,
        val output: FileOutputStream,
        val digest: MessageDigest,
        var nextSequence: Long = 0,
        var sampleCount: Long = 0,
        var byteCount: Long = 0,
        val chunkDigests: MutableMap<Long, ChunkDigest> = LinkedHashMap(),
    )

    private data class ChunkDigest(val sampleCount: Long, val sha256: String)

    private data class StreamKey(
        val sessionId: String,
        val captureId: String,
        val captureAttemptId: String,
    )

    private data class Finalized(
        val metadataJson: String,
        val sampleCount: Long,
        val byteCount: Long,
        val sha256: String,
    ) {
        fun completed(rootDirectory: File, sessionId: String, captureId: String, captureAttemptId: String): Completed =
            Completed(
                sessionId = sessionId,
                captureId = captureId,
                captureAttemptId = captureAttemptId,
                metadataJson = metadataJson,
                pcmFile = File(rootDirectory, "${hash("$sessionId:$captureId:$captureAttemptId")}$READY_SUFFIX"),
                sampleCount = sampleCount,
                byteCount = byteCount,
                sha256 = sha256,
                duplicate = true,
            )
    }

    private companion object {
        const val Float32_BYTES = 4
        const val MAX_METADATA_BYTES = 64 * 1024
        const val MAX_CAPTURE_CHUNKS = 8_192L
        const val MAX_FINALIZED_CAPTURES = 32
        const val PARTIAL_SUFFIX = ".stream.partial"
        const val READY_SUFFIX = ".stream.ready"
    }

    private fun validateCount(value: Long) {
        if (value < 0L) throw IOException("Calibration capture count is invalid")
    }

    private fun validatePositiveCount(value: Long) {
        if (value <= 0L) throw IOException("Calibration capture count is invalid")
    }

    private fun sampleByteCount(sampleCount: Long): Long {
        if (sampleCount <= 0L || sampleCount > Long.MAX_VALUE / Float32_BYTES) {
            throw IOException("Calibration capture sample count is invalid")
        }
        return sampleCount * Float32_BYTES
    }
}

private fun moveAtomically(from: File, to: File) {
    try {
        Files.move(
            from.toPath(),
            to.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}

private fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .toHex()

private fun digestHex(value: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(value)
    .toHex()

private fun metadataMatchesBegin(beginJson: String, endJson: String): Boolean {
    val begin = parseMetadata(beginJson) ?: return false
    val end = parseMetadata(endJson) ?: return false
    val additionalFields = end.fields.keys - begin.fields.keys
    return additionalFields.all { it == "sampleCount" || it == "byteCount" || it == "contentSha256" }
        && begin.fields.all { (key, value) -> end.fields[key] == value }
}

private fun validateBeginMetadata(metadataJson: String, captureId: String) {
    val metadata = parseMetadata(metadataJson)
    val metadataCaptureId = (metadata?.fields?.get("captureId") as? MetadataValue.StringValue)?.value
    val jobId = (metadata?.fields?.get("jobId") as? MetadataValue.StringValue)?.value
    if (metadata == null || metadataCaptureId != captureId
        || jobId.isNullOrBlank()
        || jobId.length > 128
    ) throw IOException("Calibration capture begin metadata is invalid")
}

private data class MetadataObject(val fields: Map<String, MetadataValue>)

private sealed interface MetadataValue {
    data class Object(val value: MetadataObject) : MetadataValue
    data class Array(val value: List<MetadataValue>) : MetadataValue
    data class StringValue(val value: String) : MetadataValue
    data class NumberValue(val value: BigDecimal) : MetadataValue
    data class BooleanValue(val value: Boolean) : MetadataValue
    object Null : MetadataValue
}

private fun parseMetadata(metadataJson: String): MetadataObject? = try {
    MetadataJsonParser(metadataJson).parse()
} catch (_: Exception) {
    null
}

private class MetadataJsonParser(private val input: String) {
    companion object {
        private const val MAX_DEPTH = 32
        private const val MAX_NODES = 4_096
        private const val MAX_OBJECT_MEMBERS = 4_096
    }

    private var offset = 0
    private var depth = 0
    private var nodes = 0
    private var objectMembers = 0

    fun parse(): MetadataObject {
        skipWhitespace()
        countNode()
        val result = parseObject()
        skipWhitespace()
        require(offset == input.length)
        return result
    }

    private fun parseObject(): MetadataObject {
        depth++
        require(depth <= MAX_DEPTH)
        try {
            expect('{')
            skipWhitespace()
            val fields = LinkedHashMap<String, MetadataValue>()
            if (consume('}')) return MetadataObject(fields)
            while (true) {
                skipWhitespace()
                val key = parseString()
                objectMembers++
                require(objectMembers <= MAX_OBJECT_MEMBERS)
                require(fields.put(key, parseMemberValue()) == null)
                skipWhitespace()
                if (consume('}')) return MetadataObject(fields)
                expect(',')
            }
        } finally {
            depth--
        }
    }

    private fun parseMemberValue(): MetadataValue {
        skipWhitespace()
        expect(':')
        return parseValue()
    }

    private fun parseValue(): MetadataValue {
        countNode()
        skipWhitespace()
        return when (input.getOrNull(offset)) {
            '{' -> MetadataValue.Object(parseObject())
            '[' -> MetadataValue.Array(parseArray())
            '"' -> MetadataValue.StringValue(parseString())
            't' -> { expectLiteral("true"); MetadataValue.BooleanValue(true) }
            'f' -> { expectLiteral("false"); MetadataValue.BooleanValue(false) }
            'n' -> { expectLiteral("null"); MetadataValue.Null }
            '-', in '0'..'9' -> MetadataValue.NumberValue(parseNumber())
            else -> throw IllegalArgumentException("Invalid metadata value")
        }
    }

    private fun parseArray(): List<MetadataValue> {
        depth++
        require(depth <= MAX_DEPTH)
        try {
            expect('[')
            skipWhitespace()
            val values = ArrayList<MetadataValue>()
            if (consume(']')) return values
            while (true) {
                values += parseValue()
                skipWhitespace()
                if (consume(']')) return values
                expect(',')
            }
        } finally {
            depth--
        }
    }

    private fun countNode() {
        nodes++
        require(nodes <= MAX_NODES)
    }

    private fun parseString(): String {
        expect('"')
        val result = StringBuilder()
        while (offset < input.length) {
            when (val character = input[offset++]) {
                '"' -> return result.toString()
                '\\' -> when (val escaped = input.getOrNull(offset++) ?: throw IllegalArgumentException("Invalid escape")) {
                    '"', '\\', '/' -> result.append(escaped)
                    'b' -> result.append('\b')
                    'f' -> result.append('\u000c')
                    'n' -> result.append('\n')
                    'r' -> result.append('\r')
                    't' -> result.append('\t')
                    'u' -> result.append(parseUnicodeEscape())
                    else -> throw IllegalArgumentException("Invalid escape")
                }
                else -> {
                    require(character >= ' ')
                    result.append(character)
                }
            }
        }
        throw IllegalArgumentException("Unterminated string")
    }

    private fun parseUnicodeEscape(): Char {
        require(offset + 4 <= input.length)
        val value = input.substring(offset, offset + 4).toIntOrNull(16)
            ?: throw IllegalArgumentException("Invalid unicode escape")
        offset += 4
        return value.toChar()
    }

    private fun parseNumber(): BigDecimal {
        val start = offset
        if (consume('-')) require(input.getOrNull(offset)?.isDigit() == true)
        if (consume('0')) {
            require(input.getOrNull(offset)?.isDigit() != true)
        } else {
            require(consumeDigits())
        }
        if (consume('.')) {
            require(consumeDigits())
        }
        if (input.getOrNull(offset) == 'e' || input.getOrNull(offset) == 'E') {
            offset++
            if (input.getOrNull(offset) == '+' || input.getOrNull(offset) == '-') offset++
            require(consumeDigits())
        }
        return BigDecimal(input.substring(start, offset)).stripTrailingZeros()
    }

    private fun consumeDigits(): Boolean {
        val start = offset
        while (input.getOrNull(offset)?.isDigit() == true) offset++
        return offset > start
    }

    private fun expectLiteral(value: String) {
        require(input.regionMatches(offset, value, 0, value.length))
        offset += value.length
    }

    private fun expect(character: Char) {
        require(input.getOrNull(offset) == character)
        offset++
    }

    private fun consume(character: Char): Boolean {
        if (input.getOrNull(offset) != character) return false
        offset++
        return true
    }

    private fun skipWhitespace() {
        while (input.getOrNull(offset)?.let { it == ' ' || it == '\n' || it == '\r' || it == '\t' } == true) offset++
    }
}

private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
