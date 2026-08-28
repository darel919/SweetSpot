package com.darelisme.sweetspot.calibration

import com.darelisme.sweetspot.calibration.analysis.*
import com.darelisme.sweetspot.calibration.capture.*
import com.darelisme.sweetspot.calibration.model.*
import com.darelisme.sweetspot.calibration.persistence.*
import com.darelisme.sweetspot.calibration.playback.*
import com.darelisme.sweetspot.calibration.playback.MeasurementSweep
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CalibrationEngineTest {
    @Test
    fun optionalFailureRetainsTheBestFourPositionSolution() {
        val root = Files.createTempDirectory("sweetspot-engine-").toFile()
        try {
            val analyzer = FixtureAnalyzer()
            val sweep = MeasurementSweep(48_000)
            val store = CalibrationJobStore(root.resolve("jobs"))
            val captures = CalibrationCaptureStore(root.resolve("captures"))
            val engine = CalibrationEngine(store, captures, analyzer, sweep, playback = ImmediatePlayback, metadataParser = FixtureMetadataParser)
            val started = engine.startNewJob() as CalibrationEngineResult.Updated
            captureMandatory(engine, analyzer)
            val afterMandatory = requireNotNull(engine.currentJob())
            assertTrue(afterMandatory.minimumViableCalibration)
            assertEquals(3, afterMandatory.bestSolution?.sourcePositions?.size)

            captureCurrent(engine, analyzer)
            captureCurrent(engine, analyzer)
            val afterForward = requireNotNull(engine.currentJob())
            assertEquals(setOf(CalibrationPosition.CENTER, CalibrationPosition.LEFT, CalibrationPosition.RIGHT, CalibrationPosition.FORWARD), afterForward.bestSolution?.sourcePositions)

            repeat(2) { captureCurrent(engine, analyzer) }
            repeat(2) { captureCurrent(engine, analyzer) }
            val afterBackwardFailure = requireNotNull(engine.currentJob())
            assertTrue(afterBackwardFailure.minimumViableCalibration)
            assertNotNull(afterBackwardFailure.bestSolution)
            assertEquals(afterForward.bestSolution, afterBackwardFailure.bestSolution)
            assertTrue(afterBackwardFailure.ledger.rejectedAttempts.any { it.request.position == CalibrationPosition.BACKWARD })
            engine.close()
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun acceptedEvidenceSurvivesEngineReload() {
        val root = Files.createTempDirectory("sweetspot-engine-reload-").toFile()
        try {
            val analyzer = FixtureAnalyzer()
            val sweep = MeasurementSweep(48_000)
            val store = CalibrationJobStore(root.resolve("jobs"))
            val captures = CalibrationCaptureStore(root.resolve("captures"))
            val first = CalibrationEngine(store, captures, analyzer, sweep, playback = ImmediatePlayback, metadataParser = FixtureMetadataParser)
            val jobId = (first.startNewJob() as CalibrationEngineResult.Updated).job.id
            captureMandatory(first, analyzer)
            val beforeReload = requireNotNull(first.currentJob())
            first.close()

            val resumed = CalibrationEngine(store, captures, analyzer, sweep, playback = ImmediatePlayback, metadataParser = FixtureMetadataParser)
            assertEquals(beforeReload, resumed.currentJob())
            assertEquals(beforeReload, (resumed.resumeJob() as CalibrationEngineResult.Updated).job)
            resumed.close()
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun storedCaptureIsAnalyzedAfterEngineReloadBeforeBrowserReacquisition() {
        val root = Files.createTempDirectory("sweetspot-engine-capture-recovery-").toFile()
        try {
            val analyzer = FixtureAnalyzer()
            val sweep = MeasurementSweep(48_000)
            val store = CalibrationJobStore(root.resolve("jobs"))
            val captures = CalibrationCaptureStore(root.resolve("captures"))
            val first = CalibrationEngine(store, captures, analyzer, sweep, playback = ImmediatePlayback, metadataParser = FixtureMetadataParser)
            val started = (first.startNewJob() as CalibrationEngineResult.Updated).job
            val action = started.nextAction as CalibrationAction.Capture
            val rawFrame = frame(started.id, action.request.captureId, action.request.position, action.request.channel, action.request.attemptIndex)
            val metadata = FixtureMetadataParser.parse(rawFrame.metadataJson, rawFrame.pcm.size)
            first.close()

            captures.store(metadata, ByteArrayInputStream(rawFrame.pcm))

            val resumed = CalibrationEngine(store, captures, analyzer, sweep, playback = ImmediatePlayback, metadataParser = FixtureMetadataParser)
            val recovered = (resumed.resumeJob() as CalibrationEngineResult.Updated).job

            assertEquals(1, recovered.ledger.attempts.size)
            assertTrue(recovered.ledger.attempts.single() is CaptureAttempt.Accepted)
            assertTrue(recovered.nextAction is CalibrationAction.Capture)
            assertEquals(CalibrationPosition.CENTER, (recovered.nextAction as CalibrationAction.Capture).request.position)
            resumed.close()
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun completedJobRemainsAvailableAfterEngineReload() {
        val root = Files.createTempDirectory("sweetspot-engine-complete-reload-").toFile()
        try {
            val analyzer = FixtureAnalyzer()
            val sweep = MeasurementSweep(48_000)
            val store = CalibrationJobStore(root.resolve("jobs"))
            val captures = CalibrationCaptureStore(root.resolve("captures"))
            val first = CalibrationEngine(
                store,
                captures,
                analyzer,
                sweep,
                playback = ImmediatePlayback,
                dsp = FixtureDsp(),
                metadataParser = FixtureMetadataParser,
            )
            val jobId = (first.startNewJob() as CalibrationEngineResult.Updated).job.id
            captureMandatory(first, analyzer)
            val staged = first.finishWithBest(jobId) as CalibrationEngineResult.Updated
            val validation = staged.job.nextAction as CalibrationAction.Validate
            analyzer.lastAction = null
            val attemptId = "fixture-attempt-${validation.captureId.value}"
            first.captureReady(jobId, validation.captureId, attemptId)
            submit(
                first,
                frame(jobId, validation.captureId, validation.position, CaptureChannel.BOTH, validation.attemptIndex),
                attemptId,
            )
            val completed = requireNotNull(first.currentJob())
            assertEquals(CalibrationPhase.Complete, completed.phase)
            first.close()

            val resumed = CalibrationEngine(
                store,
                captures,
                analyzer,
                sweep,
                playback = ImmediatePlayback,
                dsp = FixtureDsp(),
                metadataParser = FixtureMetadataParser,
            )
            assertEquals(completed, resumed.currentJob())
            assertEquals(completed, (resumed.resumeJob() as CalibrationEngineResult.Updated).job)
            resumed.close()
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun duplicateAcceptedCaptureIsIdempotentAfterThePlannerAdvances() {
        val root = Files.createTempDirectory("sweetspot-engine-duplicate-").toFile()
        try {
            val analyzer = FixtureAnalyzer()
            val sweep = MeasurementSweep(48_000)
            val store = CalibrationJobStore(root.resolve("jobs"))
            val captures = CalibrationCaptureStore(root.resolve("captures"))
            val engine = CalibrationEngine(store, captures, analyzer, sweep, playback = ImmediatePlayback, metadataParser = FixtureMetadataParser)
            val jobId = (engine.startNewJob() as CalibrationEngineResult.Updated).job.id
            val firstAction = requireNotNull(engine.currentJob()).nextAction as CalibrationAction.Capture
            val firstFrame = frame(jobId, firstAction.request.captureId, firstAction.request.position, firstAction.request.channel, firstAction.request.attemptIndex)
            val firstAttempt = "fixture-attempt-${firstAction.request.captureId.value}"
            analyzer.lastAction = firstAction
            engine.captureReady(jobId, firstAction.request.captureId, firstAttempt)
            submit(engine, firstFrame, firstAttempt)
            captureMandatory(engine, analyzer)

            val current = requireNotNull(engine.currentJob())
            val duplicate = submit(engine, firstFrame, firstAttempt)

            assertEquals(current, (duplicate as CalibrationEngineResult.Updated).job)
            assertEquals(current.ledger, requireNotNull(engine.currentJob()).ledger)
            engine.close()
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun playbackFailureIsRetriedAsTheSameLocalCaptureAction() {
        val root = Files.createTempDirectory("sweetspot-engine-playback-failure-").toFile()
        try {
            val analyzer = FixtureAnalyzer()
            val sweep = MeasurementSweep(48_000)
            val store = CalibrationJobStore(root.resolve("jobs"))
            val captures = CalibrationCaptureStore(root.resolve("captures"))
            val engine = CalibrationEngine(
                store,
                captures,
                analyzer,
                sweep,
                playback = FailingPlayback,
                metadataParser = FixtureMetadataParser,
            )
            val job = (engine.startNewJob() as CalibrationEngineResult.Updated).job
            val action = job.nextAction as CalibrationAction.Capture

            val result = engine.captureReady(job.id, action.request.captureId)
            val updated = (result as CalibrationEngineResult.Updated).job

            assertEquals(1, updated.ledger.rejectedAttempts.size)
            assertEquals(CaptureRejectionReason.PLAYBACK_FAILED, updated.ledger.rejectedAttempts.single().reason)
            assertEquals(1, (updated.nextAction as CalibrationAction.Capture).request.attemptIndex)
            engine.close()
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun duplicateCaptureReadyAfterPlaybackFinishesDoesNotReplayTheSweep() {
        val root = Files.createTempDirectory("sweetspot-engine-ready-duplicate-").toFile()
        try {
            val analyzer = FixtureAnalyzer()
            val sweep = MeasurementSweep(48_000)
            val store = CalibrationJobStore(root.resolve("jobs"))
            val captures = CalibrationCaptureStore(root.resolve("captures"))
            var startCount = 0
            val listener = object : CalibrationEngineListener {
                override fun onCaptureStarted(jobId: CalibrationJobId, captureId: CaptureId, captureAttemptId: String) {
                    startCount++
                }
            }
            val engine = CalibrationEngine(
                store,
                captures,
                analyzer,
                sweep,
                playback = ImmediatePlayback,
                listener = listener,
                metadataParser = FixtureMetadataParser,
            )
            val job = (engine.startNewJob() as CalibrationEngineResult.Updated).job
            val action = job.nextAction as CalibrationAction.Capture
            val attemptId = "fixture-attempt-${action.request.captureId.value}"

            assertTrue(engine.captureReady(job.id, action.request.captureId, attemptId) is CalibrationEngineResult.Updated)
            assertTrue(engine.captureReady(job.id, action.request.captureId, attemptId) is CalibrationEngineResult.Updated)
            assertEquals(1, startCount)
            engine.close()
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun remoteMicrophoneProfileIsUsedByTheTvAnalyzer() {
        val root = Files.createTempDirectory("sweetspot-engine-mic-profile-").toFile()
        try {
            val analyzer = FixtureAnalyzer()
            val sweep = MeasurementSweep(48_000)
            val store = CalibrationJobStore(root.resolve("jobs"))
            val captures = CalibrationCaptureStore(root.resolve("captures"))
            val engine = CalibrationEngine(
                store,
                captures,
                analyzer,
                sweep,
                playback = ImmediatePlayback,
                metadataParser = FixtureMetadataParser,
            )
            val job = (engine.startNewJob() as CalibrationEngineResult.Updated).job
            val action = job.nextAction as CalibrationAction.Capture
            val attemptId = "fixture-attempt-${action.request.captureId.value}"
            engine.captureReady(job.id, action.request.captureId, attemptId)

            val submitted = submit(engine,
                frame(
                    job.id,
                    action.request.captureId,
                    action.request.position,
                    action.request.channel,
                    action.request.attemptIndex,
                ),
                attemptId,
            )
            assertTrue(
                (submitted as? CalibrationEngineResult.Rejected)?.message ?: "unexpected result",
                submitted is CalibrationEngineResult.Updated,
            )
            val result = submitted as CalibrationEngineResult.Updated

            assertTrue(result.captureAccepted)
            assertEquals(CalibrationPosition.CENTER, result.job.ledger.attempts.first().request.position)
            assertEquals(2, analyzer.lastProfile?.frequenciesHz?.size)
            val evidence = (result.job.ledger.attempts.first() as CaptureAttempt.Accepted).evidence
            assertEquals("fixture-mic", evidence.microphoneProfileId)
            assertEquals("v1", evidence.microphoneProfileRevision)
            engine.close()
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun validatedBestSolutionCompletesWithoutBrowserClassification() {
        val root = Files.createTempDirectory("sweetspot-engine-validation-").toFile()
        try {
            val analyzer = FixtureAnalyzer()
            val sweep = MeasurementSweep(48_000)
            val store = CalibrationJobStore(root.resolve("jobs"))
            val captures = CalibrationCaptureStore(root.resolve("captures"))
            val dsp = FixtureDsp()
            val engine = CalibrationEngine(store, captures, analyzer, sweep, playback = ImmediatePlayback, dsp = dsp, metadataParser = FixtureMetadataParser)
            val jobId = (engine.startNewJob() as CalibrationEngineResult.Updated).job.id
            captureMandatory(engine, analyzer)
            val staged = engine.finishWithBest(jobId) as CalibrationEngineResult.Updated
            val validation = staged.job.nextAction as CalibrationAction.Validate
            assertEquals(validation.candidateId, dsp.candidateId)
            analyzer.lastAction = null
            val attemptId = "fixture-attempt-${validation.captureId.value}"
            engine.captureReady(jobId, validation.captureId, attemptId)
            val result = submit(
                engine,
                frame(jobId, validation.captureId, validation.position, CaptureChannel.BOTH, validation.attemptIndex),
                attemptId,
            )
            val completedResult = result as CalibrationEngineResult.Updated
            assertTrue(completedResult.captureAccepted)
            val completed = completedResult.job
            assertEquals(CalibrationPhase.Complete, completed.phase)
            assertEquals(null, completed.candidate)
            assertTrue(dsp.accepted)
            assertEquals(null, captures.get(CalibrationJobId(jobId.value), validation.captureId))
            engine.close()
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun acceptanceRecoveryUsesPersistedValidationHistoryAfterDspCommit() {
        val root = Files.createTempDirectory("sweetspot-engine-accept-recovery-").toFile()
        try {
            val analyzer = FixtureAnalyzer()
            val sweep = MeasurementSweep(48_000)
            val store = CalibrationJobStore(root.resolve("jobs"))
            val captures = CalibrationCaptureStore(root.resolve("captures"))
            val machine = CalibrationStateMachine()
            val usable = CalibrationTestFixtures.usableJob(machine)
            val best = requireNotNull(usable.bestSolution)
            val candidate = CalibrationCandidateState(CandidateId("candidate-recovered"), best.id, CorrectionMode.NORMAL, 0)
            val validating = machine.reduce(usable, CalibrationEvent.CandidateStaged(candidate)).job
            val intent = validating.copy(
                revision = validating.revision + 1,
                validationHistory = listOf(ValidationRecord(candidate.id, ValidationOutcome.IMPROVED, 0)),
                nextAction = CalibrationAction.Wait("Accepting the validated calibration."),
                pendingEffect = PendingCalibrationEffect.AcceptCandidate(candidate.id),
            )
            store.save(intent)

            val engine = CalibrationEngine(
                store,
                captures,
                analyzer,
                sweep,
                dsp = CommittedDsp,
                playback = ImmediatePlayback,
                metadataParser = FixtureMetadataParser,
            )
            val recovered = (engine.resumeJob() as CalibrationEngineResult.Updated).job

            assertEquals(CalibrationPhase.Complete, recovered.phase)
            assertEquals(listOf(ValidationOutcome.IMPROVED), recovered.validationHistory.map(ValidationRecord::outcome))
            engine.close()
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun validationRecoveryFinishesAnOutcomeRecordedBeforeProcessDeath() {
        val root = Files.createTempDirectory("sweetspot-engine-validation-recovery-").toFile()
        try {
            val analyzer = FixtureAnalyzer()
            val sweep = MeasurementSweep(48_000)
            val store = CalibrationJobStore(root.resolve("jobs"))
            val captures = CalibrationCaptureStore(root.resolve("captures"))
            val machine = CalibrationStateMachine()
            val usable = CalibrationTestFixtures.usableJob(machine)
            val best = requireNotNull(usable.bestSolution)
            val candidate = CalibrationCandidateState(CandidateId("candidate-validation-recovered"), best.id, CorrectionMode.NORMAL, 0)
            val validating = machine.reduce(usable, CalibrationEvent.CandidateStaged(candidate)).job
            store.save(validating)

            val engine = CalibrationEngine(
                store,
                captures,
                analyzer,
                sweep,
                dsp = PendingValidationDsp(candidate.id),
                playback = ImmediatePlayback,
                metadataParser = FixtureMetadataParser,
            )
            val recovered = (engine.resumeJob() as CalibrationEngineResult.Updated).job

            assertEquals(CalibrationPhase.Complete, recovered.phase)
            assertEquals(listOf(ValidationOutcome.IMPROVED), recovered.validationHistory.map(ValidationRecord::outcome))
            engine.close()
        } finally {
            root.deleteRecursively()
        }
    }

    private fun captureMandatory(engine: CalibrationEngine, analyzer: FixtureAnalyzer) {
        repeat(6) {
            captureCurrent(engine, analyzer)
        }
    }

    private fun captureCurrent(engine: CalibrationEngine, analyzer: FixtureAnalyzer) {
        val job = requireNotNull(engine.currentJob())
        val action = job.nextAction as CalibrationAction.Capture
        analyzer.lastAction = action
        val attemptId = "fixture-attempt-${action.request.captureId.value}"
        val ready = engine.captureReady(job.id, action.request.captureId, attemptId)
        assertTrue(ready is CalibrationEngineResult.Updated)
        val result = submit(
            engine,
            frame(job.id, action.request.captureId, action.request.position, action.request.channel, action.request.attemptIndex),
            attemptId,
        )
        assertTrue(result is CalibrationEngineResult.Updated)
    }

    private fun submit(engine: CalibrationEngine, capture: FixtureCapture, captureAttemptId: String): CalibrationEngineResult =
        engine.submitCaptureStream(
            capture.metadataJson,
            ByteArrayInputStream(capture.pcm),
            capture.pcm.size.toLong(),
            captureAttemptId,
        )

    private fun frame(
        jobId: CalibrationJobId,
        captureId: CaptureId,
        position: CalibrationPosition,
        channel: CaptureChannel,
        attemptIndex: Int,
    ): FixtureCapture {
        val pcm = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
            .putFloat(0.01f).putFloat(0.02f).putFloat(0.03f).putFloat(0.04f).array()
        val metadata = """
            {"jobId":"${jobId.value}","captureId":"${captureId.value}","positionId":"${position.name.lowercase()}","attemptIndex":$attemptIndex,"channel":"${channel.name.lowercase()}","sampleRate":48000,"channelCount":1,"sampleCount":4,"byteCount":16,"settings":{},"userAgent":"fixture","microphoneProfileId":"fixture-mic","microphoneProfileRevision":"v1","microphoneProfile":{"id":"fixture-mic","revision":"v1","capturePathStatus":"validated","frequenciesHz":[20,20000],"responseDb":[0,0],"normalizeAtHz":1000,"trustMinHz":30,"trustFullMaxHz":8000,"trustTaperToHz":12000},"capturedAtMs":1,"contentSha256":"${sha256(pcm)}"}
        """.trimIndent()
        return FixtureCapture(metadata, pcm)
    }

    private data class FixtureCapture(val metadataJson: String, val pcm: ByteArray)

    private class FixtureAnalyzer : CalibrationAnalyzer {
        override val revision = AnalyzerRevision("android-response-v1")
        var lastAction: CalibrationAction.Capture? = null
        var lastProfile: MicrophoneCalibrationProfile? = null

        override fun analyze(capture: CalibrationCapture, sweep: MeasurementSweep, microphoneProfile: MicrophoneCalibrationProfile?): CalibrationAnalysis {
            lastProfile = microphoneProfile
            val position = lastAction?.request?.position
            val rejected = position == CalibrationPosition.BACKWARD
            val responseValue = when (position) {
                CalibrationPosition.CENTER -> 0f
                CalibrationPosition.LEFT -> 4f
                CalibrationPosition.RIGHT -> 8f
                CalibrationPosition.FORWARD -> 12f
                CalibrationPosition.BACKWARD -> 0f
                null -> 0f
            }
            val response = CalibrationBandGrid.centerFrequenciesHz.map { ResponsePoint(it, responseValue) }
            val marker = MarkerDetection(true, 0, 0, 0, 1, .95f, .95f, .95f, 0f, 1f, 1f, 1, null, emptyList(), emptyList(), emptyList())
            val quality = CaptureSignalQuality(.2f, .5f, 30f, 0)
            val direct = DirectArrivalDiagnostics(1, .5f, .01f, 30f, .5f, .1f, null)
            return CalibrationAnalysis(
                if (rejected) AnalysisStatus.SYNC_MARKER_NOT_FOUND else AnalysisStatus.OK,
                marker,
                quality,
                if (capture.channel == AnalysisChannel.RIGHT) emptyList() else response,
                if (capture.channel == AnalysisChannel.LEFT) emptyList() else response,
                if (capture.channel == AnalysisChannel.RIGHT) null else direct,
                if (capture.channel == AnalysisChannel.LEFT) null else direct,
            )
        }
    }

    private object FixtureMetadataParser : CalibrationCaptureMetadataParser {
        override fun parse(metadataJson: String, pcmBytes: Int): CaptureUploadMetadata {
            fun string(key: String): String = Regex("\\\"$key\\\":\\\"([^\\\"]+)\\\"").find(metadataJson)?.groupValues?.get(1)
                ?: error("missing $key")
            fun number(key: String): Long = Regex("\\\"$key\\\":([0-9]+)").find(metadataJson)?.groupValues?.get(1)?.toLong()
                ?: error("missing $key")
            val sampleCount = number("sampleCount")
            return CaptureUploadMetadata(
                jobId = CalibrationJobId(string("jobId")),
                captureId = CaptureId(string("captureId")),
                position = CalibrationPosition.valueOf(string("positionId").uppercase()),
                attemptIndex = number("attemptIndex").toInt(),
                channel = CaptureChannel.valueOf(string("channel").uppercase()),
                sampleRateHz = number("sampleRate").toInt(),
                channelCount = number("channelCount").toInt(),
                sampleCount = sampleCount,
                browserCaptureSettings = emptyMap(),
                userAgent = string("userAgent"),
                microphoneProfileId = string("microphoneProfileId"),
                microphoneProfileRevision = string("microphoneProfileRevision"),
                microphoneProfile = CalibrationMicrophoneProfilePayload(
                    id = "fixture-mic",
                    revision = "v1",
                    frequenciesHz = floatArrayOf(20f, 20_000f),
                    responseDb = floatArrayOf(0f, 0f),
                    normalizeAtHz = 1_000f,
                    trustMinHz = 30f,
                    trustFullMaxHz = 8_000f,
                    trustTaperToHz = 12_000f,
                    capturePathStatus = CalibrationMicrophoneProfilePayload.VALIDATED,
                ),
                capturedAtMs = number("capturedAtMs"),
                contentSha256 = string("contentSha256"),
                byteCount = pcmBytes.toLong(),
            )
        }
    }

    private object ImmediatePlayback : CalibrationPlaybackPort {
        override fun start(request: CaptureRequest, onFinished: () -> Unit): CalibrationAudioResult {
            onFinished()
            return CalibrationAudioResult.Success()
        }
    }

    private object FailingPlayback : CalibrationPlaybackPort {
        override fun start(request: CaptureRequest, onFinished: () -> Unit): CalibrationAudioResult =
            CalibrationAudioResult.Failure("fixture playback failure")
    }

    private class FixtureDsp : CalibrationDspPort {
        var candidateId: CandidateId? = null
        var accepted = false

        override fun stageCandidate(solution: CalibrationSolution): CalibrationAudioResult {
            candidateId = CandidateId("candidate")
            return CalibrationAudioResult.Success(candidateId)
        }

        override fun recordValidation(candidateId: CandidateId, outcome: ValidationOutcome, beforeDb: Float?, afterDb: Float?, reason: String?): CalibrationAudioResult =
            CalibrationAudioResult.Success()

        override fun acceptCandidate(candidateId: CandidateId): CalibrationAudioResult {
            accepted = true
            return CalibrationAudioResult.Success()
        }

        override fun rollbackCandidate(candidateId: CandidateId): CalibrationAudioResult = CalibrationAudioResult.Success()

        override fun isLiveDspVerified(): Boolean = true
    }

    private object CommittedDsp : CalibrationDspPort {
        override fun stageCandidate(solution: CalibrationSolution): CalibrationAudioResult =
            CalibrationAudioResult.Success(CandidateId("candidate-recovered"))

        override fun recordValidation(
            candidateId: CandidateId,
            outcome: ValidationOutcome,
            beforeDb: Float?,
            afterDb: Float?,
            reason: String?,
        ): CalibrationAudioResult = CalibrationAudioResult.Success()

        override fun acceptCandidate(candidateId: CandidateId): CalibrationAudioResult = CalibrationAudioResult.Success()

        override fun rollbackCandidate(candidateId: CandidateId): CalibrationAudioResult = CalibrationAudioResult.Success()

        override fun isLiveDspVerified(): Boolean = true
    }

    private class PendingValidationDsp(private val candidate: CandidateId) : CalibrationDspPort {
        private var accepted = false

        override fun pendingCandidateId(): CandidateId? = candidate.takeUnless { accepted }

        override fun pendingValidationOutcome(): ValidationOutcome? = ValidationOutcome.IMPROVED.takeUnless { accepted }

        override fun stageCandidate(solution: CalibrationSolution): CalibrationAudioResult =
            CalibrationAudioResult.Success(candidate)

        override fun recordValidation(
            candidateId: CandidateId,
            outcome: ValidationOutcome,
            beforeDb: Float?,
            afterDb: Float?,
            reason: String?,
        ): CalibrationAudioResult = CalibrationAudioResult.Success()

        override fun acceptCandidate(candidateId: CandidateId): CalibrationAudioResult {
            accepted = true
            return CalibrationAudioResult.Success()
        }

        override fun rollbackCandidate(candidateId: CandidateId): CalibrationAudioResult {
            accepted = true
            return CalibrationAudioResult.Success()
        }

        override fun isLiveDspVerified(): Boolean = true
    }

    private companion object {
        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
