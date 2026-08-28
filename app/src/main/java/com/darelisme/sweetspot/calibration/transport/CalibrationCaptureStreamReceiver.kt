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
        val metadataJson: String,
        val pcmFile: File,
        val sampleCount: Long,
        val byteCount: Long,
        val sha256: String,
    )

    private var active: Active? = null

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

    @Synchronized
    fun delete(completed: Completed) {
        if (completed.pcmFile.exists() && !completed.pcmFile.delete()) {
            throw IOException("Unable to delete temporary streamed capture")
        }
    }

    @Synchronized
    fun cleanup() {
        cancel()
        if (!rootDirectory.isDirectory) return
        rootDirectory.listFiles()?.forEach { file ->
            if (file.name.endsWith(PARTIAL_SUFFIX) || file.name.endsWith(READY_SUFFIX)) file.delete()
        }
    }

    private fun begin(frame: CalibrationCaptureStreamFrame.Begin): Completed? {
        val current = active
        if (current != null) {
            if (current.sessionId != frame.sessionId || current.captureId != frame.captureId) {
                throw IOException("Another calibration capture is already streaming")
            }
            if (current.metadataJson != frame.metadataJson
                || current.expectedSampleCount != frame.expectedSampleCount
                || current.expectedByteCount != frame.expectedByteCount
            ) {
                throw IOException("Calibration capture begin conflicts with the active stream")
            }
            return null
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
        val token = hash("${frame.sessionId}:${frame.captureId}")
        val partial = File(rootDirectory, "$token$PARTIAL_SUFFIX")
        val ready = File(rootDirectory, "$token$READY_SUFFIX")
        partial.delete()
        ready.delete()
        validateBeginMetadata(frame.metadataJson, frame.captureId)
        active = Active(
            sessionId = frame.sessionId,
            captureId = frame.captureId,
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
        val current = requireActive(frame.sessionId, frame.captureId)
        if (frame.sampleCount <= 0 || sampleByteCount(frame.sampleCount) != frame.pcm.size.toLong()) {
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
        while (current.chunkDigests.size > MAX_REMEMBERED_CHUNKS) {
            current.chunkDigests.entries.iterator().also { iterator ->
                iterator.next()
                iterator.remove()
            }
        }
        return null
    }

    private fun finish(frame: CalibrationCaptureStreamFrame.End): Completed {
        val current = requireActive(frame.sessionId, frame.captureId)
        validateCount(frame.chunkCount)
        validateCount(frame.finalSampleCount)
        validateCount(frame.finalByteCount)
        if (frame.finalByteCount != sampleByteCount(frame.finalSampleCount)) {
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
        current.output.flush()
        current.output.fd.sync()
        current.output.close()
        moveAtomically(current.partialFile, current.readyFile)
        val completed = Completed(
            sessionId = current.sessionId,
            captureId = current.captureId,
            metadataJson = frame.metadataJson,
            pcmFile = current.readyFile,
            sampleCount = current.sampleCount,
            byteCount = current.byteCount,
            sha256 = actualHash,
        )
        active = null
        return completed
    }

    private fun requireActive(sessionId: String, captureId: String): Active = active?.also {
        if (it.sessionId != sessionId || it.captureId != captureId) {
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

    private companion object {
        const val Float32_BYTES = 4
        const val MAX_METADATA_BYTES = 64 * 1024
        const val MAX_REMEMBERED_CHUNKS = 128
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
    val beginIdentity = metadataIdentity(beginJson) ?: return false
    val endIdentity = metadataIdentity(endJson) ?: return false
    return beginIdentity == endIdentity
}

private fun validateBeginMetadata(metadataJson: String, captureId: String) {
    val identity = metadataIdentity(metadataJson)
    if (identity == null || identity.second != captureId) throw IOException("Calibration capture begin metadata is invalid")
}

private fun metadataIdentity(metadataJson: String): Pair<String, String>? {
    fun field(name: String): String? = Regex("\\\"$name\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"")
        .find(metadataJson)?.groupValues?.getOrNull(1)
    val jobId = field("jobId") ?: return null
    val captureId = field("captureId") ?: return null
    if (jobId.length > 128 || captureId.length > 128) return null
    return jobId to captureId
}

private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
