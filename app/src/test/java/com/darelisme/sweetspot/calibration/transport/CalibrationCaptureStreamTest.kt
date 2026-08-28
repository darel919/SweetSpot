package com.darelisme.sweetspot.calibration.transport

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.security.MessageDigest
import java.nio.charset.StandardCharsets
import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CalibrationCaptureStreamTest {
    @Test
    fun consumesTheCrossRepositoryCaptureVector() {
        withTempDirectory { root ->
            val fixture = requireNotNull(javaClass.getResourceAsStream("/calibration-capture-stream.json"))
                .bufferedReader()
                .use { it.readText() }
            fun field(name: String): String = Regex("\\\"$name\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"")
                .find(fixture)?.groupValues?.get(1)
                ?: error("missing $name")
            fun decode(name: String): ByteArray = Base64.getDecoder().decode(field(name))
            val metadata = String(decode("metadataJsonBase64"), StandardCharsets.UTF_8)
            val captureAttemptId = field("captureAttemptId")
            val pcm = decode("pcmBase64")
            val chunks = Regex("\\\"chunksBase64\\\"\\s*:\\s*\\[\\s*\\\"([^\\\"]+)\\\"\\s*,\\s*\\\"([^\\\"]+)\\\"\\s*\\]")
                .find(fixture)?.groupValues?.drop(1)?.map(Base64.getDecoder()::decode)
                ?: error("missing chunks")

            assertEquals("SSCS", String(decode("beginBase64").copyOfRange(0, 4), StandardCharsets.US_ASCII))
            assertArrayEquals(pcm.copyOfRange(0, 8), chunks[0].copyOfRange(payloadOffset(chunks[0]), chunks[0].size))
            assertArrayEquals(pcm.copyOfRange(8, 16), chunks[1].copyOfRange(payloadOffset(chunks[1]), chunks[1].size))

            val receiver = CalibrationCaptureStreamReceiver(root)
            assertNull(receiver.accept(CalibrationCaptureStreamFrame.Begin(
                sessionId = field("sessionId"),
                captureId = field("captureId"),
                metadataJson = metadata,
                expectedSampleCount = 4,
                expectedByteCount = 16,
                captureAttemptId = captureAttemptId,
            )))
            assertNull(receiver.accept(CalibrationCaptureStreamFrame.Chunk(
                sessionId = field("sessionId"),
                captureId = field("captureId"),
                sequence = 0,
                sampleCount = 2,
                pcm = pcm.copyOfRange(0, 8),
                captureAttemptId = captureAttemptId,
            )))
            assertNull(receiver.accept(CalibrationCaptureStreamFrame.Chunk(
                sessionId = field("sessionId"),
                captureId = field("captureId"),
                sequence = 1,
                sampleCount = 2,
                pcm = pcm.copyOfRange(8, 16),
                captureAttemptId = captureAttemptId,
            )))
            val completed = receiver.accept(CalibrationCaptureStreamFrame.End(
                sessionId = field("sessionId"),
                captureId = field("captureId"),
                chunkCount = 2,
                finalSampleCount = 4,
                finalByteCount = 16,
                finalSha256 = field("sha256"),
                metadataJson = metadata,
                captureAttemptId = captureAttemptId,
            )) ?: error("expected completed capture")
            assertArrayEquals(pcm, completed.pcmFile.readBytes())
            receiver.delete(completed)
        }
    }

    @Test
    fun receiverWritesChunksToDiskAndAcceptsAnExactDuplicate() {
        withTempDirectory { root ->
            val receiver = CalibrationCaptureStreamReceiver(root)
            val pcm = floats(0.25f, -0.5f)
            val hash = pcm.sha256()
            val begin = CalibrationCaptureStreamFrame.Begin("session-1", "capture-1", "{\"jobId\":\"job-1\",\"captureId\":\"capture-1\"}", null, null, "attempt-1")
            val chunk = CalibrationCaptureStreamFrame.Chunk("session-1", "capture-1", 0, 2, pcm, "attempt-1")
            val end = CalibrationCaptureStreamFrame.End(
                sessionId = "session-1",
                captureId = "capture-1",
                chunkCount = 1,
                finalSampleCount = 2,
                finalByteCount = pcm.size.toLong(),
                finalSha256 = hash,
                metadataJson = "{\"jobId\":\"job-1\",\"captureId\":\"capture-1\"}",
                captureAttemptId = "attempt-1",
            )

            assertNull(receiver.accept(begin))
            assertNull(receiver.accept(chunk))
            assertNull(receiver.accept(chunk))
            val completed = receiver.accept(end) ?: error("expected completed capture")
            assertEquals(2L, completed.sampleCount)
            assertEquals(pcm.size.toLong(), completed.byteCount)
            assertArrayEquals(pcm, completed.pcmFile.readBytes())
            receiver.delete(completed)
        }
    }

    @Test
    fun finalizedCaptureRetriesAreIdempotent() {
        withTempDirectory { root ->
            val receiver = CalibrationCaptureStreamReceiver(root)
            val pcm = floats(0.25f, -0.5f)
            val hash = pcm.sha256()
            val beginMetadata = "{\"jobId\":\"job-1\",\"captureId\":\"capture-1\"}"
            val endMetadata = "{\"jobId\":\"job-1\",\"captureId\":\"capture-1\",\"sampleCount\":2,\"byteCount\":8,\"contentSha256\":\"$hash\"}"
            val begin = CalibrationCaptureStreamFrame.Begin(
                sessionId = "session-1",
                captureId = "capture-1",
                metadataJson = beginMetadata,
                expectedSampleCount = null,
                expectedByteCount = null,
                captureAttemptId = "attempt-1",
            )
            receiver.accept(begin)
            receiver.accept(CalibrationCaptureStreamFrame.Chunk("session-1", "capture-1", 0, 2, pcm, "attempt-1"))
            val end = CalibrationCaptureStreamFrame.End(
                sessionId = "session-1",
                captureId = "capture-1",
                chunkCount = 1,
                finalSampleCount = 2,
                finalByteCount = 8,
                finalSha256 = hash,
                metadataJson = endMetadata,
                captureAttemptId = "attempt-1",
            )
            val completed = receiver.accept(end) ?: error("expected completed capture")
            receiver.delete(completed)

            val duplicateBegin = receiver.accept(begin) ?: error("expected duplicate capture receipt")
            assertTrue(duplicateBegin.duplicate)
            assertEquals(hash, duplicateBegin.sha256)
            assertNull(receiver.accept(CalibrationCaptureStreamFrame.Chunk("session-1", "capture-1", 0, 2, pcm, "attempt-1")))
            val duplicateEnd = receiver.accept(end) ?: error("expected duplicate capture receipt")
            assertTrue(duplicateEnd.duplicate)
            assertEquals(hash, duplicateEnd.sha256)
        }
    }

    @Test
    fun malformedLateChunkFromFinalizedCaptureCannotAbortAnotherActiveCapture() {
        withTempDirectory { root ->
            val receiver = CalibrationCaptureStreamReceiver(root)
            val pcm = floats(0.25f)
            val hash = pcm.sha256()
            val metadata = "{\"jobId\":\"job-1\",\"captureId\":\"capture-1\"}"
            receiver.accept(CalibrationCaptureStreamFrame.Begin(
                "session-1", "capture-1", metadata, null, null, "attempt-1",
            ))
            receiver.accept(CalibrationCaptureStreamFrame.Chunk(
                "session-1", "capture-1", 0, 1, pcm, "attempt-1",
            ))
            val end = CalibrationCaptureStreamFrame.End(
                "session-1", "capture-1", 1, 1, 4, hash, metadata, "attempt-1",
            )
            val completed = receiver.accept(end) ?: error("expected completed capture")
            receiver.delete(completed)

            receiver.accept(CalibrationCaptureStreamFrame.Begin(
                "session-1", "capture-2", "{\"jobId\":\"job-1\",\"captureId\":\"capture-2\"}", null, null, "attempt-2",
            ))
            assertNull(receiver.accept(CalibrationCaptureStreamFrame.Chunk(
                "session-1", "capture-1", -1, 0, byteArrayOf(), "attempt-1",
            )))
            assertNull(receiver.accept(CalibrationCaptureStreamFrame.Chunk(
                "session-1", "capture-2", 0, 1, pcm, "attempt-2",
            )))
            receiver.cancel()
        }
    }

    @Test
    fun malformedUnknownCaptureCannotAbortTheActiveStream() {
        withTempDirectory { root ->
            val receiver = CalibrationCaptureStreamReceiver(root)
            receiver.accept(CalibrationCaptureStreamFrame.Begin(
                "session-1", "capture-current", "{\"jobId\":\"job-1\",\"captureId\":\"capture-current\"}", null, null, "attempt-current",
            ))

            assertThrows(java.io.IOException::class.java) {
                receiver.accept(CalibrationCaptureStreamFrame.Chunk(
                    "session-1", "capture-old", -1, 0, byteArrayOf(), "attempt-old",
                ))
            }
            assertNull(receiver.accept(CalibrationCaptureStreamFrame.Chunk(
                "session-1", "capture-current", 0, 1, floats(0.25f), "attempt-current",
            )))
            receiver.cancel()
        }
    }

    @Test
    fun receiverRejectsAConflictingDuplicateChunk() {
        withTempDirectory { root ->
            val receiver = CalibrationCaptureStreamReceiver(root)
            receiver.accept(CalibrationCaptureStreamFrame.Begin(
                "session-1",
                "capture-1",
                "{\"jobId\":\"job-1\",\"captureId\":\"capture-1\"}",
                null,
                null,
                "attempt-1",
            ))
            receiver.accept(CalibrationCaptureStreamFrame.Chunk(
                "session-1",
                "capture-1",
                0,
                1,
                floats(0.25f),
                "attempt-1",
            ))

            assertThrows(java.io.IOException::class.java) {
                receiver.accept(CalibrationCaptureStreamFrame.Chunk(
                    "session-1",
                    "capture-1",
                    0,
                    1,
                    floats(-0.25f),
                    "attempt-1",
                ))
            }
            receiver.cancel()
            assertEquals(0, root.listFiles()?.size ?: 0)
        }
    }

    @Test
    fun staleSessionCancellationCannotDeleteTheCurrentStream() {
        withTempDirectory { root ->
            val receiver = CalibrationCaptureStreamReceiver(root)
            receiver.accept(CalibrationCaptureStreamFrame.Begin(
                "session-current",
                "capture-current",
                "{\"jobId\":\"job-1\",\"captureId\":\"capture-current\"}",
                null,
                null,
                "attempt-current",
            ))

            assertFalse(receiver.cancel("session-old", "capture-current"))
            assertNull(receiver.accept(CalibrationCaptureStreamFrame.Chunk(
                "session-current",
                "capture-current",
                0,
                1,
                floats(0.25f),
                "attempt-current",
            )))
            receiver.cancel()
        }
    }

    @Test
    fun newAttemptReplacesAStalePartialForTheSameCapture() {
        withTempDirectory { root ->
            val receiver = CalibrationCaptureStreamReceiver(root)
            val metadata = "{\"jobId\":\"job-1\",\"captureId\":\"capture-1\"}"
            receiver.accept(CalibrationCaptureStreamFrame.Begin(
                sessionId = "session-1",
                captureId = "capture-1",
                metadataJson = metadata,
                expectedSampleCount = null,
                expectedByteCount = null,
                captureAttemptId = "attempt-old",
            ))
            receiver.accept(CalibrationCaptureStreamFrame.Chunk(
                sessionId = "session-1",
                captureId = "capture-1",
                sequence = 0,
                sampleCount = 1,
                pcm = floats(0.25f),
                captureAttemptId = "attempt-old",
            ))

            assertNull(receiver.accept(CalibrationCaptureStreamFrame.Begin(
                sessionId = "session-1",
                captureId = "capture-1",
                metadataJson = metadata,
                expectedSampleCount = null,
                expectedByteCount = null,
                captureAttemptId = "attempt-new",
            )))
            assertThrows(java.io.IOException::class.java) {
                receiver.accept(CalibrationCaptureStreamFrame.Chunk(
                    sessionId = "session-1",
                    captureId = "capture-1",
                    sequence = 1,
                    sampleCount = 1,
                    pcm = floats(0.5f),
                    captureAttemptId = "attempt-old",
                ))
            }
            assertNull(receiver.accept(CalibrationCaptureStreamFrame.Chunk(
                sessionId = "session-1",
                captureId = "capture-1",
                sequence = 0,
                sampleCount = 1,
                pcm = floats(0.5f),
                captureAttemptId = "attempt-new",
            )))
            receiver.cancel()
            assertEquals(0, root.listFiles()?.size ?: 0)
        }
    }

    @Test
    fun receiverRejectsNonFinitePcmAndCleansThePartialFile() {
        withTempDirectory { root ->
            val receiver = CalibrationCaptureStreamReceiver(root)
            receiver.accept(CalibrationCaptureStreamFrame.Begin(
                "session-1",
                "capture-1",
                "{\"jobId\":\"job-1\",\"captureId\":\"capture-1\"}",
                null,
                null,
                "attempt-1",
            ))

            assertThrows(java.io.IOException::class.java) {
                receiver.accept(CalibrationCaptureStreamFrame.Chunk(
                    "session-1",
                    "capture-1",
                    0,
                    1,
                    floats(Float.NaN),
                    "attempt-1",
                ))
            }
            assertEquals(0, root.listFiles()?.size ?: 0)
        }
    }

    @Test
    fun receiverRejectsMissingChunksAndCleansAHashMismatch() {
        withTempDirectory { root ->
            val receiver = CalibrationCaptureStreamReceiver(root)
            receiver.accept(CalibrationCaptureStreamFrame.Begin("session-1", "capture-1", "{\"jobId\":\"job-1\",\"captureId\":\"capture-1\"}", null, null, "attempt-1"))
            val pcm = floats(0.25f)
            assertThrows(java.io.IOException::class.java) {
                receiver.accept(CalibrationCaptureStreamFrame.Chunk("session-1", "capture-1", 1, 1, pcm, "attempt-1"))
            }
            receiver.cancel()
            receiver.accept(CalibrationCaptureStreamFrame.Begin("session-1", "capture-1", "{\"jobId\":\"job-1\",\"captureId\":\"capture-1\"}", null, null, "attempt-1"))
            receiver.accept(CalibrationCaptureStreamFrame.Chunk("session-1", "capture-1", 0, 1, pcm, "attempt-1"))
            assertThrows(java.io.IOException::class.java) {
                receiver.accept(
                    CalibrationCaptureStreamFrame.End(
                        "session-1",
                        "capture-1",
                        1,
                        1,
                        4,
                        "0".repeat(64),
                        "{\"jobId\":\"job-1\",\"captureId\":\"capture-1\"}",
                        "attempt-1",
                    ),
                )
            }
            assertEquals(0, root.listFiles()?.size ?: 0)
        }
    }

    private fun withTempDirectory(block: (File) -> Unit) {
        val root = Files.createTempDirectory("sweetspot-stream-").toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun floats(vararg values: Float): ByteArray = ByteBuffer.allocate(values.size * 4)
        .order(ByteOrder.LITTLE_ENDIAN)
        .apply { values.forEach(::putFloat) }
        .array()

    private fun payloadOffset(frame: ByteArray): Int = 16 + ByteBuffer.wrap(frame)
        .order(ByteOrder.BIG_ENDIAN)
        .getInt(8)

    private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(this)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

}
