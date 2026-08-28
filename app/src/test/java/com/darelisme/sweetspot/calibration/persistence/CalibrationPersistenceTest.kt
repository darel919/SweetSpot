package com.darelisme.sweetspot.calibration

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class CalibrationPersistenceTest {
    private val machine = CalibrationStateMachine()

    @Test
    fun jobRoundTripRetainsAcceptedEvidenceAndBestSolution() {
        val directory = temporaryDirectory()
        try {
            val original = CalibrationTestFixtures.usableJob(machine)
            val store = CalibrationJobStore(directory)

            store.save(original)

            assertEquals(original, store.load(original.id))
            assertEquals(listOf(original), store.list())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun jobRoundTripRetainsCandidateValidationAndRollbackIntent() {
        val directory = temporaryDirectory()
        try {
            val usable = CalibrationTestFixtures.usableJob(machine)
            val best = (usable.usability as CalibrationUsability.Usable).best
            val validating = machine.reduce(
                usable,
                CalibrationEvent.CandidateStaged(
                    CalibrationCandidateState(CandidateId("candidate"), best.id, CorrectionMode.NORMAL, 0),
                ),
            ).job
            val original = machine.reduce(
                validating,
                CalibrationEvent.ValidationClassified(ValidationOutcome.WORSE),
            ).job
            val store = CalibrationJobStore(directory)

            store.save(original)

            assertEquals(original, store.load(original.id))
            assertTrue(store.load(original.id)?.pendingEffect is PendingCalibrationEffect.RollbackThenReoptimize)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun validationCaptureRetriesOnceThenRestoresWithoutAnotherRoomWalk() {
        val usable = CalibrationTestFixtures.usableJob(machine)
        val best = requireNotNull(usable.bestSolution)
        val candidate = CalibrationCandidateState(CandidateId("candidate-validation"), best.id, CorrectionMode.NORMAL, 0)
        val validating = machine.reduce(usable, CalibrationEvent.CandidateStaged(candidate)).job

        val retry = machine.reduce(
            validating,
            CalibrationEvent.ValidationClassified(ValidationOutcome.INCONCLUSIVE_CAPTURE),
        ).job
        assertEquals(CalibrationPhase.Validating, retry.phase)
        assertEquals(1, (retry.nextAction as CalibrationAction.Validate).attemptIndex)
        assertTrue(retry.minimumViableCalibration)

        val exhausted = machine.reduce(
            retry,
            CalibrationEvent.ValidationClassified(ValidationOutcome.INCONCLUSIVE_CAPTURE),
        ).job
        assertEquals(CalibrationPhase.Restoring, exhausted.phase)
        assertTrue(exhausted.pendingEffect is PendingCalibrationEffect.RestorePrevious)
        assertTrue(exhausted.minimumViableCalibration)
        assertEquals(2, exhausted.validationHistory.size)
    }

    @Test
    fun jobRoundTripRetainsActionEffectAndErrorVariants() {
        val directory = temporaryDirectory()
        try {
            val usable = CalibrationTestFixtures.usableJob(machine)
            val best = (usable.usability as CalibrationUsability.Usable).best
            val candidate = CalibrationCandidateState(
                CandidateId("candidate-variants"),
                best.id,
                CorrectionMode.GENTLE,
                2,
            )
            val variants = listOf(
                CalibrationJob.new(
                    id = CalibrationJobId("job-not-usable"),
                    createdAtMs = 1L,
                    analyzerRevision = AnalyzerRevision("android-response-v1"),
                    sweepRevision = SweepRevision("android-sweep-v3"),
                ),
                usable.copy(nextAction = CalibrationAction.Wait("wait")),
                usable.copy(
                    phase = CalibrationPhase.Validating,
                    candidate = candidate,
                    nextAction = CalibrationAction.Validate(
                        captureId = CaptureId("validation-variant"),
                        position = CalibrationPosition.CENTER,
                        candidateId = candidate.id,
                        attemptIndex = 2,
                        instruction = "validate",
                    ),
                ),
                usable.copy(
                    phase = CalibrationPhase.Complete,
                    nextAction = CalibrationAction.Complete(best.id),
                    candidate = null,
                    pendingEffect = null,
                ),
                usable.copy(
                    phase = CalibrationPhase.CandidatePending,
                    nextAction = CalibrationAction.Wait("stage"),
                    pendingEffect = PendingCalibrationEffect.StageCandidate(best.id),
                ),
                usable.copy(
                    phase = CalibrationPhase.Restoring,
                    candidate = candidate,
                    nextAction = CalibrationAction.Wait("restore"),
                    pendingEffect = PendingCalibrationEffect.RestorePrevious(candidate.id),
                ),
                usable.copy(
                    phase = CalibrationPhase.Failed("failed"),
                    nextAction = null,
                    lastError = CalibrationJobError("test", "persisted error"),
                ),
            )
            val store = CalibrationJobStore(directory)

            variants.forEachIndexed { index, variant ->
                val withRevision = variant.copy(revision = index.toLong())
                store.save(withRevision)
                assertEquals(withRevision, store.load(withRevision.id))
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun jobStoreRejectsStaleRevisionAndIncompatibleRevision() {
        val directory = temporaryDirectory()
        try {
            val job = CalibrationTestFixtures.newJob()
            val store = CalibrationJobStore(directory)
            store.save(job.copy(revision = 4))

            var staleRejected = false
            try {
                store.save(job.copy(revision = 3))
            } catch (_: StaleCalibrationSnapshotException) {
                staleRejected = true
            }
            assertTrue(staleRejected)

            val incompatible = CalibrationJobStore(directory, AnalyzerRevision("other"))
            var revisionRejected = false
            try {
                incompatible.load(job.id)
            } catch (_: CalibrationSnapshotIncompatibleException) {
                revisionRejected = true
            }
            assertTrue(revisionRejected)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun corruptSnapshotDoesNotBecomeAValidJobAndTemporaryFileIsIgnored() {
        val directory = temporaryDirectory()
        try {
            val job = CalibrationTestFixtures.newJob()
            val store = CalibrationJobStore(directory)
            store.save(job)
            val snapshot = directory.listFiles()!!.single { it.name.endsWith(".snapshot") }
            val temporary = directory.resolve("${snapshot.name}.tmp")
            temporary.writeBytes(byteArrayOf(1, 2, 3))
            assertEquals(job, store.load(job.id))
            store.cleanupTemporarySnapshots()
            assertFalse(temporary.exists())

            snapshot.writeBytes(byteArrayOf(1, 2, 3))
            var corruptRejected = false
            try {
                store.load(job.id)
            } catch (_: CalibrationSnapshotException) {
                corruptRejected = true
            }
            assertTrue(corruptRejected)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun captureRoundTripUsesLittleEndianFloat32AndStoresMetadata() {
        val root = temporaryDirectory()
        try {
            val fixture = captureFixture()
            val store = CalibrationCaptureStore(root)
            val result = store.store(fixture.metadata, OneByteInputStream(fixture.bytes))
            val stored = (result as CaptureStoreResult.Stored).capture

            assertEquals(fixture.metadata, stored.metadata)
            assertArrayEquals(fixture.bytes, store.openPcm(stored).use(InputStream::readBytes))
            assertEquals(stored, store.get(fixture.metadata.jobId, fixture.metadata.captureId))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun sameCaptureAndHashIsIdempotentButDifferentContentConflicts() {
        val root = temporaryDirectory()
        try {
            val fixture = captureFixture()
            val store = CalibrationCaptureStore(root)
            val first = store.store(fixture.metadata, ByteArrayInputStream(fixture.bytes))
            val duplicate = store.store(fixture.metadata, ByteArrayInputStream(fixture.bytes))

            assertTrue(first is CaptureStoreResult.Stored)
            assertTrue(duplicate is CaptureStoreResult.Duplicate)

            val retryWithUpdatedTimestamp = fixture.metadata.copy(capturedAtMs = 2L)
            assertTrue(
                store.store(retryWithUpdatedTimestamp, ByteArrayInputStream(fixture.bytes)) is
                    CaptureStoreResult.Duplicate,
            )

            val changed = captureFixture(captureId = fixture.metadata.captureId, values = floatArrayOf(0.25f, 0.5f))
            var conflict = false
            try {
                store.store(changed.metadata, ByteArrayInputStream(changed.bytes))
            } catch (_: CaptureUploadConflictException) {
                conflict = true
            }
            assertTrue(conflict)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun captureReadbackRejectsTamperedPcm() {
        val root = temporaryDirectory()
        try {
            val fixture = captureFixture()
            val store = CalibrationCaptureStore(root)
            val stored = (store.store(fixture.metadata, ByteArrayInputStream(fixture.bytes)) as CaptureStoreResult.Stored).capture
            stored.pcmFile.writeBytes(fixture.bytes.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() })

            assertThrows(CaptureStoreException::class.java) {
                store.get(fixture.metadata.jobId, fixture.metadata.captureId)
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun captureRejectsHashMismatchNonFiniteSamplesAndOversizePayload() {
        val root = temporaryDirectory()
        try {
            val fixture = captureFixture()
            val store = CalibrationCaptureStore(root, maxBytesPerCapture = 64)
            val wrongHash = fixture.metadata.copy(contentSha256 = sha256(byteArrayOf(9, 8, 7, 6)))
            assertInvalidUpload(store, wrongHash, fixture.bytes)

            val nonFinite = captureFixture(values = floatArrayOf(Float.NaN, 1f))
            assertInvalidUpload(store, nonFinite.metadata, nonFinite.bytes)

            val tooLarge = captureFixture(values = FloatArray(17) { it.toFloat() })
            var limited = false
            try {
                store.store(tooLarge.metadata, ByteArrayInputStream(tooLarge.bytes))
            } catch (_: CaptureStorageLimitException) {
                limited = true
            }
            assertTrue(limited)
            assertTrue(root.walkTopDown().none { it.isFile && it.name.endsWith(".partial") })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun captureCleanupRemovesPartialUploadsAndTerminalJobData() {
        val root = temporaryDirectory()
        try {
            val fixture = captureFixture()
            val store = CalibrationCaptureStore(root)
            store.store(fixture.metadata, ByteArrayInputStream(fixture.bytes))
            val jobDirectory = root.listFiles()!!.single { it.isDirectory }
            val partial = jobDirectory.resolve("unfinished.partial")
            partial.writeBytes(byteArrayOf(1))
            partial.setLastModified(0)

            store.cleanupPartialUploads(Long.MAX_VALUE)
            assertFalse(partial.exists())
            assertTrue(store.get(fixture.metadata.jobId, fixture.metadata.captureId) != null)

            store.deleteJob(fixture.metadata.jobId)
            assertFalse(jobDirectory.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    private fun assertInvalidUpload(
        store: CalibrationCaptureStore,
        metadata: CaptureUploadMetadata,
        bytes: ByteArray,
    ) {
        var rejected = false
        try {
            store.store(metadata, ByteArrayInputStream(bytes))
        } catch (_: InvalidCaptureUploadException) {
            rejected = true
        }
        assertTrue(rejected)
    }

    private fun captureFixture(
        captureId: CaptureId = CaptureId("center-left-0"),
        values: FloatArray = floatArrayOf(-1f, 0f, 1f, 0.5f),
    ): CaptureFixture {
        val bytes = ByteBuffer.allocate(values.size * Float32Bytes)
            .order(ByteOrder.LITTLE_ENDIAN)
            .apply { values.forEach(::putFloat) }
            .array()
        return CaptureFixture(
            metadata = CaptureUploadMetadata(
                jobId = CalibrationJobId("job-capture"),
                captureId = captureId,
                position = CalibrationPosition.CENTER,
                attemptIndex = 0,
                channel = CaptureChannel.LEFT,
                sampleRateHz = 48_000,
                channelCount = 1,
                sampleCount = values.size.toLong(),
                browserCaptureSettings = mapOf("echoCancellation" to "false", "channelCount" to "1"),
                userAgent = "test-browser",
                microphoneProfileId = "iphone-17-pro",
                microphoneProfileRevision = "2026-01",
                microphoneProfile = CalibrationMicrophoneProfilePayload(
                    id = "iphone-17-pro",
                    revision = "2026-01",
                    frequenciesHz = floatArrayOf(20f, 20_000f),
                    responseDb = floatArrayOf(0f, 0f),
                    normalizeAtHz = 1_000f,
                    trustMinHz = 30f,
                    trustFullMaxHz = 8_000f,
                    trustTaperToHz = 12_000f,
                    capturePathStatus = CalibrationMicrophoneProfilePayload.VALIDATED,
                ),
                capturedAtMs = 1L,
                contentSha256 = sha256(bytes),
            ),
            bytes = bytes,
        )
    }

    private data class CaptureFixture(
        val metadata: CaptureUploadMetadata,
        val bytes: ByteArray,
    )

    private class OneByteInputStream(private val bytes: ByteArray) : InputStream() {
        private var index = 0

        override fun read(): Int = if (index == bytes.size) -1 else bytes[index++].toInt() and 0xff

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (index == bytes.size) return -1
            buffer[offset] = bytes[index++]
            return 1
        }
    }

    private fun temporaryDirectory() = Files.createTempDirectory("sweetspot-calibration-").toFile()

    private companion object {
        const val Float32Bytes = 4

        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }
}
