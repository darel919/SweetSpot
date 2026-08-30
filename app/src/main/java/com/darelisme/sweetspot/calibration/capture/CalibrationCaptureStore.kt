package com.darelisme.sweetspot.calibration.capture

import com.darelisme.sweetspot.calibration.model.*
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Locale

private const val FLOAT32_BYTES = 4L

private fun pcmByteCount(sampleCount: Long): Long {
    require(sampleCount > 0) { "Sample count must be positive" }
    require(sampleCount <= Long.MAX_VALUE / FLOAT32_BYTES) { "Sample count is too large" }
    return sampleCount * FLOAT32_BYTES
}

data class CaptureUploadMetadata(
    val jobId: CalibrationJobId,
    val captureId: CaptureId,
    val position: CalibrationPosition,
    val attemptIndex: Int,
    val channel: CaptureChannel,
    val sampleRateHz: Int,
    val channelCount: Int,
    val sampleCount: Long,
    val browserCaptureSettings: Map<String, String>,
    val userAgent: String,
    val microphoneProfileId: String,
    val microphoneProfileRevision: String,
    val capturedAtMs: Long,
    val contentSha256: String,
    val byteCount: Long = pcmByteCount(sampleCount),
    val microphoneProfile: CalibrationMicrophoneProfilePayload,
) {
    init {
        require(attemptIndex >= 0)
        require(sampleRateHz in 8_000..192_000)
        require(channelCount == 1)
        require(byteCount == pcmByteCount(sampleCount))
        require(browserCaptureSettings.size <= MAX_SETTINGS)
        require(browserCaptureSettings.keys.all { it.isNotBlank() && it.length <= MAX_METADATA_TEXT })
        require(browserCaptureSettings.values.all { it.length <= MAX_METADATA_TEXT })
        require(userAgent.isNotBlank() && userAgent.length <= MAX_METADATA_TEXT)
        require(microphoneProfileId.isNotBlank() && microphoneProfileId.length <= MAX_METADATA_TEXT)
        require(microphoneProfileRevision.isNotBlank() && microphoneProfileRevision.length <= MAX_METADATA_TEXT)
        require(microphoneProfile.id == microphoneProfileId)
        require(microphoneProfile.revision == microphoneProfileRevision)
        require(capturedAtMs >= 0)
        require(contentSha256.isSha256())
    }

    fun normalized(): CaptureUploadMetadata = copy(
        browserCaptureSettings = browserCaptureSettings.toMap(),
        contentSha256 = contentSha256.lowercase(Locale.ROOT),
        microphoneProfile = microphoneProfile.copyOf(),
    )

    private companion object {
        const val MAX_SETTINGS = 64
        const val MAX_METADATA_TEXT = 4_096
    }
}

data class StoredCalibrationCapture(
    val metadata: CaptureUploadMetadata,
    val pcmFile: File,
)

sealed interface CaptureStoreResult {
    data class Stored(val capture: StoredCalibrationCapture) : CaptureStoreResult
    data class Duplicate(val capture: StoredCalibrationCapture) : CaptureStoreResult
}

open class CaptureStoreException(message: String, cause: Throwable? = null) : IOException(message, cause)

class InvalidCaptureUploadException(message: String, cause: Throwable? = null) : CaptureStoreException(message, cause)

class CaptureUploadConflictException(message: String) : CaptureStoreException(message)

class CaptureStorageLimitException(message: String) : CaptureStoreException(message)

class CalibrationCaptureStore(
    private val rootDirectory: File,
    private val maxBytesPerJob: Long = 16L * 1024L * 1024L,
    private val maxBytesPerCapture: Long = 2L * 1024L * 1024L,
) {
    init {
        require(maxBytesPerJob > 0)
        require(maxBytesPerCapture > 0)
        require(maxBytesPerCapture <= maxBytesPerJob)
    }

    @Synchronized
    fun store(metadata: CaptureUploadMetadata, pcm: InputStream): CaptureStoreResult {
        val normalized = metadata.normalized()
        if (normalized.byteCount > maxBytesPerCapture) {
            throw CaptureStorageLimitException("Capture exceeds the temporary capture limit")
        }
        ensureDirectory(rootDirectory)
        val directory = jobDirectory(normalized.jobId)
        ensureDirectory(directory)
        val paths = CapturePaths(directory, normalized.captureId)
        val existing = readExisting(paths, normalized)
        if (existing != null) return CaptureStoreResult.Duplicate(existing)
        if (storedBytes(directory) + normalized.byteCount > maxBytesPerJob) {
            throw CaptureStorageLimitException("Calibration job exceeds the temporary capture limit")
        }

        cleanupPartial(paths)
        val pcmPartial = paths.pcmPartial
        val metadataPartial = paths.metadataPartial
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            FileOutputStream(pcmPartial).use { fileOutput ->
                val buffer = ByteArray(BUFFER_SIZE)
                var remaining = normalized.byteCount
                while (remaining > 0) {
                    val requested = minOf(buffer.size.toLong(), remaining).toInt()
                    val read = readAtMost(pcm, buffer, requested)
                    if (read <= 0) throw InvalidCaptureUploadException("PCM payload ended before sample count")
                    fileOutput.write(buffer, 0, read)
                    digest.update(buffer, 0, read)
                    remaining -= read
                }
                if (pcm.read() != -1) throw InvalidCaptureUploadException("PCM payload exceeds sample count")
                fileOutput.fd.sync()
            }
            val actualHash = digest.digest().toHex()
            if (actualHash != normalized.contentSha256) {
                throw InvalidCaptureUploadException("PCM SHA-256 does not match capture metadata")
            }
            validateFiniteFloat32(pcmPartial, normalized.sampleCount)
            writeMetadata(metadataPartial, normalized)
            moveAtomically(pcmPartial, paths.pcm)
            moveAtomically(metadataPartial, paths.metadata)
            writeCompletionMarker(paths.completionPartial, paths.completion)
            return CaptureStoreResult.Stored(StoredCalibrationCapture(normalized, paths.pcm))
        } catch (error: CaptureStoreException) {
            cleanupIncomplete(paths)
            throw error
        } catch (error: Exception) {
            cleanupIncomplete(paths)
            throw CaptureStoreException("Unable to store calibration capture", error)
        }
    }

    @Synchronized
    fun get(jobId: CalibrationJobId, captureId: CaptureId): StoredCalibrationCapture? {
        val directory = jobDirectory(jobId)
        if (!directory.exists()) return null
        if (!directory.isDirectory) throw CaptureStoreException("Calibration capture directory is not a directory")
        val paths = CapturePaths(directory, captureId)
        if (!paths.completion.exists()) {
            if (paths.pcm.exists() || paths.metadata.exists()) {
                throw CaptureStoreException("Calibration capture is incomplete")
            }
            return null
        }
        return try {
            require(paths.completion.readBytes().contentEquals(COMPLETE_MARKER))
            val metadata = readMetadata(paths.metadata)
            require(metadata.jobId == jobId && metadata.captureId == captureId)
            require(paths.pcm.isFile && paths.pcm.length() == metadata.byteCount)
            validateFiniteFloat32(paths.pcm, metadata.sampleCount)
            require(fileSha256(paths.pcm) == metadata.contentSha256) {
                "PCM SHA-256 does not match capture metadata"
            }
            StoredCalibrationCapture(metadata, paths.pcm)
        } catch (error: CaptureStoreException) {
            throw error
        } catch (error: Exception) {
            throw CaptureStoreException("Unable to read calibration capture", error)
        }
    }

    @Synchronized
    fun openPcm(capture: StoredCalibrationCapture): InputStream {
        val stored = get(capture.metadata.jobId, capture.metadata.captureId)
            ?: throw CaptureStoreException("Calibration capture is missing")
        if (stored.metadata.contentSha256 != capture.metadata.contentSha256) {
            throw CaptureStoreException("Calibration capture changed")
        }
        return FileInputStream(stored.pcmFile)
    }

    @Synchronized
    fun deleteJob(jobId: CalibrationJobId) {
        val directory = jobDirectory(jobId)
        if (!directory.exists()) return
        deleteTree(directory)
    }

    @Synchronized
    fun cleanupPartialUploads(olderThanMs: Long = System.currentTimeMillis() - DEFAULT_PARTIAL_RETENTION_MS) {
        if (!rootDirectory.isDirectory) return
        rootDirectory.listFiles { file -> file.isDirectory }?.forEach { directory ->
            val files = directory.listFiles()?.filter(File::isFile).orEmpty()
            files.forEach { file ->
                if (file.lastModified() > olderThanMs) return@forEach
                when {
                    file.name.endsWith(PARTIAL_SUFFIX) -> file.delete()
                    else -> {
                        val token = when {
                            file.name.endsWith(PCM_SUFFIX) -> file.name.removeSuffix(PCM_SUFFIX)
                            file.name.endsWith(METADATA_SUFFIX) -> file.name.removeSuffix(METADATA_SUFFIX)
                            file.name.endsWith(COMPLETE_SUFFIX) -> file.name.removeSuffix(COMPLETE_SUFFIX)
                            else -> return@forEach
                        }
                        val pcm = File(directory, "$token$PCM_SUFFIX")
                        val metadata = File(directory, "$token$METADATA_SUFFIX")
                        val complete = File(directory, "$token$COMPLETE_SUFFIX")
                        if (!pcm.exists() || !metadata.exists() || !complete.exists()) {
                            pcm.delete()
                            metadata.delete()
                            complete.delete()
                        }
                    }
                }
            }
        }
    }

    private fun readExisting(paths: CapturePaths, incoming: CaptureUploadMetadata): StoredCalibrationCapture? {
        if (!paths.completion.exists()) {
            if (paths.pcm.exists() || paths.metadata.exists()) cleanupIncomplete(paths)
            return null
        }
        val existing = get(incoming.jobId, incoming.captureId)
            ?: throw CaptureStoreException("Calibration completion marker is corrupt")
        if (existing.metadata.contentSha256 != incoming.contentSha256) {
            throw CaptureUploadConflictException("Capture ID already contains different PCM")
        }
        if (!existing.metadata.sameCaptureIdentity(incoming)) {
            throw CaptureUploadConflictException("Capture ID already contains different metadata")
        }
        return existing
    }

    private fun writeMetadata(file: File, metadata: CaptureUploadMetadata) {
        FileOutputStream(file).use { fileOutput ->
            DataOutputStream(BufferedOutputStream(fileOutput)).use { output ->
                CaptureMetadataCodec.write(output, metadata)
                output.flush()
                fileOutput.fd.sync()
            }
        }
    }

    private fun readMetadata(file: File): CaptureUploadMetadata {
        FileInputStream(file).use { fileInput ->
            DataInputStream(BufferedInputStream(fileInput)).use { input ->
                val metadata = CaptureMetadataCodec.read(input)
                if (input.available() != 0) throw IOException("Trailing calibration capture metadata")
                return metadata
            }
        }
    }

    private fun writeCompletionMarker(partial: File, target: File) {
        FileOutputStream(partial).use { fileOutput ->
            fileOutput.write(COMPLETE_MARKER)
            fileOutput.fd.sync()
        }
        moveAtomically(partial, target)
    }

    private fun validateFiniteFloat32(file: File, sampleCount: Long) {
        require(file.length() == pcmByteCount(sampleCount))
        FileInputStream(file).use { input ->
            val bytes = ByteArray(BUFFER_SIZE)
            var remaining = file.length()
            while (remaining > 0) {
                val requested = minOf(bytes.size.toLong(), remaining).toInt()
                input.readFully(bytes, requested)
                var offset = 0
                while (offset < requested) {
                    val value = ByteBuffer.wrap(bytes, offset, Float32Bytes).order(ByteOrder.LITTLE_ENDIAN).float
                    if (!value.isFinite()) throw InvalidCaptureUploadException("PCM contains a non-finite sample")
                    offset += Float32Bytes
                }
                remaining -= requested
            }
        }
    }

    private fun fileSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().toHex()
    }

    private fun storedBytes(directory: File): Long = directory.listFiles { file ->
        file.isFile && file.name.endsWith(PCM_SUFFIX)
    }?.sumOf { it.length() } ?: 0L

    private fun cleanupPartial(paths: CapturePaths) {
        paths.pcmPartial.delete()
        paths.metadataPartial.delete()
        paths.completionPartial.delete()
    }

    private fun cleanupIncomplete(paths: CapturePaths) {
        cleanupPartial(paths)
        paths.pcm.delete()
        paths.metadata.delete()
        paths.completion.delete()
    }

    private fun jobDirectory(jobId: CalibrationJobId): File =
        File(rootDirectory, hash(jobId.value))

    private fun ensureDirectory(directory: File) {
        if ((!directory.exists() && !directory.mkdirs()) || !directory.isDirectory) {
            throw CaptureStoreException("Unable to create calibration capture directory")
        }
    }

    private fun deleteTree(directory: File) {
        directory.listFiles()?.forEach { file ->
            if (file.isDirectory) deleteTree(file) else if (!file.delete()) {
                throw CaptureStoreException("Unable to delete calibration capture")
            }
        }
        if (!directory.delete()) throw CaptureStoreException("Unable to delete calibration capture directory")
    }

    private data class CapturePaths(private val directory: File, private val captureId: CaptureId) {
        private val token = hash(captureId.value)
        val pcm = File(directory, "$token$PCM_SUFFIX")
        val metadata = File(directory, "$token$METADATA_SUFFIX")
        val completion = File(directory, "$token$COMPLETE_SUFFIX")
        val pcmPartial = File(directory, "$token$PCM_SUFFIX$PARTIAL_SUFFIX")
        val metadataPartial = File(directory, "$token$METADATA_SUFFIX$PARTIAL_SUFFIX")
        val completionPartial = File(directory, "$token$COMPLETE_SUFFIX$PARTIAL_SUFFIX")
    }

    private companion object {
        const val BUFFER_SIZE = 32 * 1024
        const val Float32Bytes = 4
        const val PCM_SUFFIX = ".pcm"
        const val METADATA_SUFFIX = ".meta"
        const val COMPLETE_SUFFIX = ".complete"
        const val PARTIAL_SUFFIX = ".partial"
        const val DEFAULT_PARTIAL_RETENTION_MS = 24L * 60L * 60L * 1_000L
        val COMPLETE_MARKER = byteArrayOf(0x53, 0x53, 0x43, 0x50)
    }
}

private object CaptureMetadataCodec {
    private const val MAGIC = 0x5353434D
    private const val SCHEMA_VERSION = 3
    private const val MAX_STRING_BYTES = 16 * 1024
    private const val MAX_SETTINGS = 64

    fun write(output: DataOutputStream, metadata: CaptureUploadMetadata) {
        output.writeInt(MAGIC)
        output.writeInt(SCHEMA_VERSION)
        writeString(output, metadata.jobId.value)
        writeString(output, metadata.captureId.value)
        writeString(output, metadata.position.name)
        output.writeInt(metadata.attemptIndex)
        writeString(output, metadata.channel.name)
        output.writeInt(metadata.sampleRateHz)
        output.writeInt(metadata.channelCount)
        output.writeLong(metadata.sampleCount)
        output.writeLong(metadata.byteCount)
        output.writeInt(metadata.browserCaptureSettings.size)
        metadata.browserCaptureSettings.toSortedMap().forEach { (key, value) ->
            writeString(output, key)
            writeString(output, value)
        }
        writeString(output, metadata.userAgent)
        writeString(output, metadata.microphoneProfileId)
        writeString(output, metadata.microphoneProfileRevision)
        writeProfile(output, metadata.microphoneProfile)
        output.writeLong(metadata.capturedAtMs)
        writeString(output, metadata.contentSha256)
    }

    fun read(input: DataInputStream): CaptureUploadMetadata {
        if (input.readInt() != MAGIC) throw IOException("Unknown calibration capture metadata format")
        if (input.readInt() != SCHEMA_VERSION) throw IOException("Unsupported calibration capture metadata schema")
        val jobId = CalibrationJobId(readString(input))
        val captureId = CaptureId(readString(input))
        val position = enumValue(input, CalibrationPosition.entries)
        val attemptIndex = input.readInt()
        val channel = enumValue(input, CaptureChannel.entries)
        val sampleRateHz = input.readInt()
        val channelCount = input.readInt()
        val sampleCount = input.readLong()
        val byteCount = input.readLong()
        val settingsCount = input.readInt()
        if (settingsCount !in 0..MAX_SETTINGS) throw IOException("Invalid capture settings count")
        val settings = buildMap {
            repeat(settingsCount) { put(readString(input), readString(input)) }
        }
        val userAgent = readString(input)
        val microphoneProfileId = readString(input)
        val microphoneProfileRevision = readString(input)
        val microphoneProfile = readProfile(input)
        val metadata = CaptureUploadMetadata(
            jobId = jobId,
            captureId = captureId,
            position = position,
            attemptIndex = attemptIndex,
            channel = channel,
            sampleRateHz = sampleRateHz,
            channelCount = channelCount,
            sampleCount = sampleCount,
            browserCaptureSettings = settings,
            userAgent = userAgent,
            microphoneProfileId = microphoneProfileId,
            microphoneProfileRevision = microphoneProfileRevision,
            microphoneProfile = microphoneProfile,
            capturedAtMs = input.readLong(),
            contentSha256 = readString(input),
            byteCount = byteCount,
        ).normalized()
        if (input.available() != 0) throw IOException("Trailing capture metadata")
        return metadata
    }

    private fun writeString(output: DataOutputStream, value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= MAX_STRING_BYTES)
        output.writeInt(bytes.size)
        output.write(bytes)
    }

    private fun writeProfile(output: DataOutputStream, profile: CalibrationMicrophoneProfilePayload) {
        writeString(output, profile.id)
        writeString(output, profile.revision)
        writeString(output, profile.capturePathStatus)
        output.writeFloat(profile.normalizeAtHz)
        output.writeFloat(profile.trustMinHz)
        output.writeFloat(profile.trustFullMaxHz)
        output.writeFloat(profile.trustTaperToHz)
        output.writeInt(profile.frequenciesHz.size)
        profile.frequenciesHz.forEach(output::writeFloat)
        profile.responseDb.forEach(output::writeFloat)
    }

    private fun readProfile(input: DataInputStream): CalibrationMicrophoneProfilePayload {
        val id = readString(input)
        val revision = readString(input)
        val capturePathStatus = readString(input)
        val normalizeAtHz = input.readFloat()
        val trustMinHz = input.readFloat()
        val trustFullMaxHz = input.readFloat()
        val trustTaperToHz = input.readFloat()
        val pointCount = input.readInt()
        if (pointCount !in CalibrationMicrophoneProfilePayload.MIN_POINTS..CalibrationMicrophoneProfilePayload.MAX_POINTS) {
            throw IOException("Invalid microphone profile point count")
        }
        val frequencies = FloatArray(pointCount) { input.readFloat() }
        val response = FloatArray(pointCount) { input.readFloat() }
        return try {
            CalibrationMicrophoneProfilePayload(
                id = id,
                revision = revision,
                frequenciesHz = frequencies,
                responseDb = response,
                normalizeAtHz = normalizeAtHz,
                trustMinHz = trustMinHz,
                trustFullMaxHz = trustFullMaxHz,
                trustTaperToHz = trustTaperToHz,
                capturePathStatus = capturePathStatus,
            )
        } catch (error: IllegalArgumentException) {
            throw IOException("Invalid microphone profile data", error)
        }
    }

    private fun readString(input: DataInputStream): String {
        val size = input.readInt()
        if (size < 0 || size > MAX_STRING_BYTES) throw IOException("Invalid capture metadata string")
        val bytes = ByteArray(size)
        input.readFully(bytes)
        return String(bytes, StandardCharsets.UTF_8)
    }

    private fun <T : Enum<T>> enumValue(input: DataInputStream, values: Iterable<T>): T {
        val name = readString(input)
        return values.firstOrNull { it.name == name } ?: throw IOException("Unknown capture metadata enum $name")
    }
}

private fun InputStream.readFully(buffer: ByteArray, length: Int) {
    var offset = 0
    while (offset < length) {
        val count = read(buffer, offset, length - offset)
        if (count <= 0) throw IOException("Unexpected end of calibration capture")
        offset += count
    }
}

private fun readAtMost(input: InputStream, buffer: ByteArray, length: Int): Int {
    val blockRead = input.read(buffer, 0, length)
    if (blockRead > 0 || blockRead == -1) return blockRead
    val singleRead = input.read()
    if (singleRead == -1) return -1
    buffer[0] = singleRead.toByte()
    return 1
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

private fun String.isSha256(): Boolean = length == 64 && all { it in "0123456789abcdefABCDEF" }

private fun CaptureUploadMetadata.sameCaptureIdentity(other: CaptureUploadMetadata): Boolean =
    jobId == other.jobId &&
        captureId == other.captureId &&
        position == other.position &&
        attemptIndex == other.attemptIndex &&
        channel == other.channel &&
        sampleRateHz == other.sampleRateHz &&
        channelCount == other.channelCount &&
        sampleCount == other.sampleCount &&
        byteCount == other.byteCount &&
        microphoneProfileId == other.microphoneProfileId &&
        microphoneProfileRevision == other.microphoneProfileRevision &&
        microphoneProfile.sameCalibrationData(other.microphoneProfile)

private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

private fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(StandardCharsets.UTF_8))
    .toHex()
