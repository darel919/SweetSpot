package com.darelisme.sweetspot.calibration

import com.darelisme.sweetspot.calibration.analysis.*
import com.darelisme.sweetspot.calibration.capture.*
import com.darelisme.sweetspot.calibration.model.*
import com.darelisme.sweetspot.calibration.persistence.*
import com.darelisme.sweetspot.calibration.playback.*
import com.darelisme.sweetspot.calibration.playback.MeasurementSweep
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

sealed interface CalibrationEngineResult {
    val job: CalibrationJob?

    data class Updated(override val job: CalibrationJob) : CalibrationEngineResult

    data class Rejected(
        override val job: CalibrationJob?,
        val code: String,
        val message: String,
    ) : CalibrationEngineResult
}

interface CalibrationEngineListener {
    fun onJobChanged(job: CalibrationJob) {}
    fun onCaptureFinished(jobId: CalibrationJobId, captureId: CaptureId) {}
}

object NoopCalibrationEngineListener : CalibrationEngineListener

class CalibrationEngine(
    private val jobStore: CalibrationJobStore,
    private val captureStore: CalibrationCaptureStore,
    private val analyzer: CalibrationAnalyzer,
    private val sweep: MeasurementSweep,
    private val playback: CalibrationPlaybackPort = NoopCalibrationPlayback,
    private val dsp: CalibrationDspPort = NoopCalibrationDsp,
    private val listener: CalibrationEngineListener = NoopCalibrationEngineListener,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val metadataParser: CalibrationCaptureMetadataParser? = null,
) : AutoCloseable {
    private val workerThread = ThreadLocal<Boolean>()
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread({
            workerThread.set(true)
            runnable.run()
        }, "sweetspot-calibration").apply { isDaemon = true }
    }

    @Volatile
    private var closed = false
    @Volatile
    private var job: CalibrationJob? = null
    private var activePlaybackCapture: CaptureId? = null
    private val startupError: String?
    private val stateMachine = CalibrationStateMachine()
    private val spatialCorrection = SpatialCorrection()
    private val captureReader = CalibrationCaptureReader(nowMs, metadataParser)

    init {
        startupError = try {
            captureStore.cleanupPartialUploads()
            jobStore.cleanupTemporarySnapshots()
            val snapshots = jobStore.list()
            val activeJobs = snapshots.filterNot(::terminal)
            require(activeJobs.size <= 1) { "Multiple unresolved calibration jobs are present" }
            (activeJobs.singleOrNull() ?: snapshots.maxWithOrNull(compareBy<CalibrationJob> { it.createdAtMs }.thenBy { it.revision }))
                ?.also { loaded ->
                    require(loaded.analyzerRevision == analyzer.revision) { "Analyzer revision does not match the persisted job" }
                    require(loaded.sweepRevision.value == sweep.sweepRevision) { "Sweep revision does not match the persisted job" }
                    job = loaded
                }
            null
        } catch (error: Throwable) {
            "Persisted calibration state is unavailable: ${error.message ?: error.javaClass.simpleName}"
        }
    }

    fun currentJob(): CalibrationJob? = job

    fun startNewJob(): CalibrationEngineResult = runOnWorker {
        if (startupError != null) return@runOnWorker rejected("persisted_state_invalid", startupError)
        val current = job
        if (current != null && !terminal(current)) {
            return@runOnWorker rejected("job_in_progress", "A calibration job is already unresolved")
        }
        if (dsp.pendingCandidateId() != null) {
            return@runOnWorker rejected(
                "candidate_in_progress",
                "A calibration candidate is unresolved; finish or discard it before starting a new job",
            )
        }
        if (current != null) {
            captureStore.deleteJob(current.id)
            jobStore.delete(current.id)
            job = null
        }
        val created = CalibrationJob.new(
            id = CalibrationJobId("cal-${nowMs().toString(36)}-${UUID.randomUUID().toString().take(8)}"),
            createdAtMs = nowMs(),
            analyzerRevision = analyzer.revision,
            sweepRevision = SweepRevision(sweep.sweepRevision),
        )
        persist(created)
        CalibrationEngineResult.Updated(created)
    }

    fun resumeJob(): CalibrationEngineResult = runOnWorker {
        val current = job
        if (startupError != null) rejected("persisted_state_invalid", startupError)
        else if (current == null) rejected("no_job", "No unfinished calibration job is available")
        else CalibrationEngineResult.Updated(recover(recoverStoredCapture(current)))
    }

    fun captureReady(jobId: CalibrationJobId, captureId: CaptureId): CalibrationEngineResult = runOnWorker {
        val current = requireJob(jobId) ?: return@runOnWorker rejected("no_job", "No matching calibration job")
        if (activePlaybackCapture == captureId) return@runOnWorker CalibrationEngineResult.Updated(current)
        val action = current.nextAction
        val result = when (action) {
            is CalibrationAction.Capture -> {
                if (action.request.captureId != captureId) {
                    return@runOnWorker rejected("stale_action", "Capture action is no longer active")
                }
                activePlaybackCapture = captureId
                playback.start(action.request) { listener.onCaptureFinished(jobId, captureId) }
            }
            is CalibrationAction.Validate -> {
                if (action.captureId != captureId) {
                    return@runOnWorker rejected("stale_action", "Validation action is no longer active")
                }
                activePlaybackCapture = captureId
                playback.startValidation(action) { listener.onCaptureFinished(jobId, captureId) }
            }
            else -> return@runOnWorker rejected("stale_action", "The TV is not waiting for this capture")
        }
        when (result) {
            is CalibrationAudioResult.Success -> CalibrationEngineResult.Updated(current)
            is CalibrationAudioResult.Failure -> {
                activePlaybackCapture = null
                when {
                    action is CalibrationAction.Validate -> {
                        val transition = stateMachine.reduce(
                            current,
                            CalibrationEvent.ValidationClassified(ValidationOutcome.INCONCLUSIVE_CAPTURE),
                        )
                        commit(transition)
                        CalibrationEngineResult.Updated(transition.job)
                    }
                    action is CalibrationAction.Capture -> {
                        val transition = stateMachine.reduce(
                            current,
                            CalibrationEvent.CaptureRejected(action.request, CaptureRejectionReason.PLAYBACK_FAILED),
                        )
                        autoStageWhenReady(commit(transition))
                    }
                    else -> rejected("playback_failed", result.message)
                }
            }
        }
    }

    fun cancelCapture(jobId: CalibrationJobId, captureId: CaptureId): CalibrationEngineResult = runOnWorker {
        val current = requireJob(jobId) ?: return@runOnWorker rejected("no_job", "No matching calibration job")
        val request = captureRequest(current.nextAction)
            ?: return@runOnWorker rejected("stale_action", "The TV is not waiting for this capture")
        if (request.captureId != captureId) return@runOnWorker rejected("stale_action", "Capture action is no longer active")
        activePlaybackCapture = null
        when (val result = playback.cancel(request)) {
            is CalibrationAudioResult.Success -> CalibrationEngineResult.Updated(current)
            is CalibrationAudioResult.Failure -> rejected("capture_cancel_failed", result.message)
        }
    }

    /**
     * Accepts a verified stream from the direct capture transport. The stream
     * is consumed by [CalibrationCaptureStore], so the engine never needs a
     * network-sized PCM buffer.
     */
    fun submitCaptureStream(
        metadataJson: String,
        pcm: InputStream,
        pcmBytes: Long,
    ): CalibrationEngineResult = runOnWorker {
        if (pcmBytes <= 0L || pcmBytes > Int.MAX_VALUE) {
            return@runOnWorker rejected("invalid_pcm_stream", "Calibration capture size is invalid")
        }
        val metadata = try {
            captureReader.parse(metadataJson, pcmBytes.toInt())
        } catch (error: Exception) {
            return@runOnWorker rejected("invalid_capture_metadata", error.message ?: "Invalid calibration capture metadata")
        }
        val current = requireJob(metadata.jobId)
            ?: return@runOnWorker rejected("no_job", "No matching calibration job")
        val priorAttempt = current.ledger.attempts.firstOrNull { it.request.captureId == metadata.captureId }
        if (priorAttempt != null) {
            try {
                captureStore.store(metadata, pcm)
            } catch (error: CaptureStoreException) {
                return@runOnWorker rejected("capture_store_failed", error.message ?: "Calibration capture could not be stored")
            }
            return@runOnWorker CalibrationEngineResult.Updated(current)
        }
        val action = current.nextAction
        val captureAction = action as? CalibrationAction.Capture
        val validationAction = action as? CalibrationAction.Validate
        if (captureAction == null && validationAction == null) {
            return@runOnWorker rejected("stale_action", "The TV is not waiting for a capture")
        }
        if (!metadataMatchesAction(metadata, captureAction, validationAction)) {
            return@runOnWorker rejected("stale_action", "Capture metadata does not match the TV action")
        }
        if (activePlaybackCapture == metadata.captureId) activePlaybackCapture = null
        val stored = try {
            captureStore.store(metadata, pcm)
        } catch (error: CaptureStoreException) {
            return@runOnWorker rejected("capture_store_failed", error.message ?: "Calibration capture could not be stored")
        }
        val storedCapture = when (stored) {
            is CaptureStoreResult.Stored -> stored.capture
            is CaptureStoreResult.Duplicate -> stored.capture
        }
        val verifiedSamples = try {
            captureStore.openPcm(storedCapture).use { captureReader.readFloat32(it, storedCapture.metadata.byteCount) }
        } catch (error: CaptureStoreException) {
            return@runOnWorker rejected("capture_store_failed", error.message ?: "Calibration capture could not be read")
        } catch (error: IllegalArgumentException) {
            return@runOnWorker rejected("capture_store_failed", error.message ?: "Calibration capture could not be read")
        }
        processStoredCapture(current, metadata, verifiedSamples, captureAction, validationAction)
    }

    fun finishWithBest(jobId: CalibrationJobId): CalibrationEngineResult = runOnWorker {
        val current = requireJob(jobId) ?: return@runOnWorker rejected("no_job", "No matching calibration job")
        stageBest(current)
    }

    fun cancelOptionalRefinement(jobId: CalibrationJobId): CalibrationEngineResult = runOnWorker {
        val current = requireJob(jobId) ?: return@runOnWorker rejected("no_job", "No matching calibration job")
        val request = (current.nextAction as? CalibrationAction.Capture)?.request
        if (request == null || !request.optional) return@runOnWorker finishWithBest(jobId)
        activePlaybackCapture = null
        when (val cancelled = playback.cancel(request)) {
            is CalibrationAudioResult.Failure -> return@runOnWorker rejected("capture_cancel_failed", cancelled.message)
            is CalibrationAudioResult.Success -> Unit
        }
        stageBest(current)
    }

    fun discardJob(jobId: CalibrationJobId): CalibrationEngineResult = runOnWorker {
        val current = requireJob(jobId) ?: return@runOnWorker rejected("no_job", "No matching calibration job")
        if (terminal(current)) return@runOnWorker CalibrationEngineResult.Updated(current)
        val activeRequest = captureRequest(current.nextAction)
            ?.takeIf { activePlaybackCapture == it.captureId }
        if (activeRequest != null) {
            when (val cancelled = playback.cancel(activeRequest)) {
                is CalibrationAudioResult.Failure -> return@runOnWorker rejected("capture_cancel_failed", cancelled.message)
                is CalibrationAudioResult.Success -> Unit
            }
        }
        activePlaybackCapture = null
        val pendingCandidate = dsp.pendingCandidateId()
        val candidateToRollback = current.candidate?.id ?: pendingCandidate
        if (candidateToRollback != null && !rollbackIfNeeded(candidateToRollback)) {
            return@runOnWorker rejected("rollback_failed", "The candidate could not be rolled back and verified")
        }
        if (candidateToRollback == null && !dsp.isLiveDspVerified()) {
            return@runOnWorker rejected("dsp_unverified", "The previous calibration could not be verified")
        }
        captureStore.deleteJob(current.id)
        jobStore.delete(current.id)
        job = null
        CalibrationEngineResult.Updated(current.copy(phase = CalibrationPhase.Cancelled, nextAction = null, candidate = null, pendingEffect = null))
    }

    override fun close() {
        if (closed) return
        val current = job
        val activeCapture = activePlaybackCapture
        if (current != null && activeCapture != null) {
            captureRequest(current.nextAction)?.takeIf { it.captureId == activeCapture }?.let { request ->
                try { playback.cancel(request) } catch (_: Throwable) {}
            }
        }
        activePlaybackCapture = null
        closed = true
        executor.shutdownNow()
    }

    private fun recoverStoredCapture(current: CalibrationJob): CalibrationJob {
        val captureId = when (val action = current.nextAction) {
            is CalibrationAction.Capture -> action.request.captureId
            is CalibrationAction.Validate -> action.captureId
            else -> return current
        }
        val stored = captureStore.get(current.id, captureId) ?: return current
        val metadata = stored.metadata
        val captureAction = current.nextAction as? CalibrationAction.Capture
        val validationAction = current.nextAction as? CalibrationAction.Validate
        val result = processStoredCapture(
            current,
            metadata,
            captureStore.openPcm(stored).use { captureReader.readFloat32(it, stored.metadata.byteCount) },
            captureAction,
            validationAction,
        )
        return result.job ?: current
    }

    private fun processStoredCapture(
        current: CalibrationJob,
        metadata: CaptureUploadMetadata,
        samples: FloatArray,
        captureAction: CalibrationAction.Capture?,
        validationAction: CalibrationAction.Validate?,
    ): CalibrationEngineResult {
        if (captureAction == null && validationAction == null) {
            return rejected("stale_action", "The TV is not waiting for a capture")
        }
        if (!metadataMatchesAction(metadata, captureAction, validationAction)) {
            return rejected("stale_action", "Capture metadata does not match the TV action")
        }
        if (activePlaybackCapture == metadata.captureId) activePlaybackCapture = null
        val profile = try {
            metadata.microphoneProfile
                .takeIf(CalibrationMicrophoneProfilePayload::isCorrectionEligible)
                ?.toAnalyzerProfile()
        } catch (_: IllegalArgumentException) {
            null
        }
        if (profile == null) {
            if (captureAction != null) {
                val transition = stateMachine.reduce(
                    current,
                    CalibrationEvent.CaptureRejected(
                        request = captureAction.request,
                        reason = CaptureRejectionReason.MICROPHONE_PROFILE_UNAVAILABLE,
                    ),
                )
                return autoStageWhenReady(commit(transition))
            }
            return retryValidationAfterCaptureProblem(current, "The selected microphone profile is not validated for this capture path")
        }
        val analysis = try {
            analyzer.analyze(
                CalibrationCapture(metadata.sampleRateHz, samples, metadata.analysisChannel()),
                sweep,
                profile,
            )
        } catch (_: Throwable) {
            if (validationAction != null) {
                val transition = stateMachine.reduce(
                    current,
                    CalibrationEvent.ValidationClassified(ValidationOutcome.INCONCLUSIVE_CAPTURE),
                )
                commit(transition)
                return CalibrationEngineResult.Updated(transition.job)
            }
            val request = requireNotNull(captureAction).request
            val transition = stateMachine.reduce(
                current,
                CalibrationEvent.CaptureRejected(request, CaptureRejectionReason.INVALID_PCM),
            )
            return autoStageWhenReady(commit(transition))
        }
        if (validationAction != null) return finishValidation(current, analysis)
        val request = requireNotNull(captureAction).request
        val response = when (request.channel) {
            CaptureChannel.LEFT -> analysis.leftResponse
            CaptureChannel.RIGHT -> analysis.rightResponse
            CaptureChannel.BOTH -> emptyList()
        }
        if (analysis.status != AnalysisStatus.OK || response.isEmpty()) {
            val rejection = rejectionReason(analysis)
            val transition = stateMachine.reduce(current, CalibrationEvent.CaptureRejected(request, rejection))
            return autoStageWhenReady(commit(transition))
        }
        val direct = when (request.channel) {
            CaptureChannel.LEFT -> analysis.leftDirectArrival
            CaptureChannel.RIGHT -> analysis.rightDirectArrival
            CaptureChannel.BOTH -> null
        }
        val evidence = AcceptedChannelEvidence(
            request = request,
            responseDb = response.toBandCurve(),
            quality = CaptureQuality(
                snrDb = analysis.quality.snrDb ?: 0f,
                markerConfidence = analysis.marker.confidence.coerceIn(0f, 1f),
                directArrivalConfidence = if (direct?.rejection == null && direct?.acceptedSample != null) 1f else 0f,
            ),
            microphoneProfileId = metadata.microphoneProfile.id,
            microphoneProfileRevision = metadata.microphoneProfile.revision,
        )
        val transition = stateMachine.reduce(current, CalibrationEvent.ChannelAccepted(evidence))
        return autoStageWhenReady(commit(transition))
    }

    private fun finishValidation(
        current: CalibrationJob,
        analysis: CalibrationAnalysis,
    ): CalibrationEngineResult {
        val score = if (analysis.status != AnalysisStatus.OK) {
            ValidationScore(ValidationOutcome.INCONCLUSIVE_CAPTURE, null, null)
        } else {
            ValidationScorer.classify(current, analysis)
        }
        val outcome = score.outcome
        var classifiedJob = current
        if (outcome == ValidationOutcome.IMPROVED || outcome == ValidationOutcome.NEUTRAL) {
            val candidate = requireNotNull(current.candidate)
            when (val recorded = dsp.recordValidation(candidate.id, outcome, score.beforeDb, score.afterDb)) {
                is CalibrationAudioResult.Failure -> {
                    val transition = stateMachine.reduce(current, CalibrationEvent.ValidationClassified(ValidationOutcome.DSP_ERROR))
                    commit(transition)
                    executePending(transition.job)
                    return rejected("validation_record_failed", recorded.message)
                }
                is CalibrationAudioResult.Success -> Unit
            }
            val record = ValidationRecord(candidate.id, outcome, candidate.validationAttemptIndex)
            val intent = current.copy(
                revision = current.revision + 1,
                validationHistory = if (current.validationHistory.lastOrNull() == record) {
                    current.validationHistory
                } else {
                    current.validationHistory + record
                },
                pendingEffect = PendingCalibrationEffect.AcceptCandidate(candidate.id),
                nextAction = CalibrationAction.Wait("Accepting the validated calibration."),
            )
            persist(intent)
            classifiedJob = intent.copy(pendingEffect = null)
            when (val accepted = dsp.acceptCandidate(candidate.id)) {
                is CalibrationAudioResult.Failure -> {
                    val transition = stateMachine.reduce(intent.copy(pendingEffect = null), CalibrationEvent.ValidationClassified(ValidationOutcome.DSP_ERROR))
                    commit(transition)
                    executePending(transition.job)
                    return rejected("candidate_accept_failed", accepted.message)
                }
                is CalibrationAudioResult.Success -> if (!dsp.isLiveDspVerified()) {
                    val transition = stateMachine.reduce(intent.copy(pendingEffect = null), CalibrationEvent.ValidationClassified(ValidationOutcome.DSP_ERROR))
                    commit(transition)
                    executePending(transition.job)
                    return rejected("dsp_unverified", "The accepted calibration could not be verified")
                }
            }
        }
        val transition = stateMachine.reduce(
            classifiedJob,
            CalibrationEvent.ValidationClassified(outcome),
        )
        commit(transition)
        if (transition.job.pendingEffect != null) executePending(transition.job)
        val finalJob = job ?: transition.job
        if (finalJob.phase is CalibrationPhase.Complete) cleanupRawCaptures(finalJob.id)
        return CalibrationEngineResult.Updated(finalJob)
    }

    private fun stageBest(current: CalibrationJob): CalibrationEngineResult {
        if (terminal(current)) return CalibrationEngineResult.Updated(current)
        val usable = current.usability as? CalibrationUsability.Usable
            ?: return rejected("insufficient", "No trustworthy calibration solution is available")
        if (current.phase is CalibrationPhase.Validating
            || current.phase is CalibrationPhase.CandidatePending
            || current.phase is CalibrationPhase.Restoring
            || current.pendingEffect != null
        ) {
            return CalibrationEngineResult.Updated(current)
        }
        val intent = current.copy(
            revision = current.revision + 1,
            phase = CalibrationPhase.CandidatePending,
            nextAction = CalibrationAction.Wait("Staging the best calibration."),
            pendingEffect = PendingCalibrationEffect.StageCandidate(usable.best.id),
            lastError = null,
        )
        persist(intent)
        val pendingBefore = dsp.pendingCandidateId()
        val staged = dsp.stageCandidate(usable.best)
        if (staged is CalibrationAudioResult.Failure) {
            val pendingAfter = dsp.pendingCandidateId()
            val rolledBack = if (pendingBefore == null && pendingAfter != null) rollbackIfNeeded(pendingAfter) else true
            if (!rolledBack && pendingAfter != null) {
                val unresolved = intent.copy(
                    revision = intent.revision + 1,
                    phase = CalibrationPhase.Restoring,
                    candidate = CalibrationCandidateState(
                        id = pendingAfter,
                        solutionId = usable.best.id,
                        mode = usable.best.correctionMode,
                        validationAttemptIndex = 0,
                    ),
                    nextAction = CalibrationAction.Wait("Restoring the previous calibration after a staging failure."),
                    pendingEffect = PendingCalibrationEffect.RestorePrevious(pendingAfter),
                    lastError = CalibrationJobError("candidate_stage_failed", staged.message),
                )
                persist(unresolved)
                return rejected("candidate_stage_failed", staged.message)
            }
            val failed = intent.copy(
                revision = intent.revision + 1,
                phase = CalibrationPhase.Usable,
                nextAction = CalibrationAction.Wait("The best calibration could not be staged."),
                pendingEffect = null,
                lastError = CalibrationJobError("candidate_stage_failed", staged.message),
            )
            persist(failed)
            return rejected("candidate_stage_failed", staged.message)
        }
        val candidateId = (staged as CalibrationAudioResult.Success).candidateId
            ?: run {
                val pendingAfter = dsp.pendingCandidateId()
                if (pendingBefore == null && pendingAfter != null && !rollbackIfNeeded(pendingAfter)) {
                    val unresolved = intent.copy(
                        revision = intent.revision + 1,
                        phase = CalibrationPhase.Restoring,
                        candidate = CalibrationCandidateState(
                            id = pendingAfter,
                            solutionId = usable.best.id,
                            mode = usable.best.correctionMode,
                            validationAttemptIndex = 0,
                        ),
                        nextAction = CalibrationAction.Wait("Restoring the previous calibration after a staging failure."),
                        pendingEffect = PendingCalibrationEffect.RestorePrevious(pendingAfter),
                        lastError = CalibrationJobError("candidate_stage_failed", "The TV did not return a candidate ID"),
                    )
                    persist(unresolved)
                    return rejected("candidate_stage_failed", "The TV did not return a candidate ID")
                }
                val failed = intent.copy(
                    revision = intent.revision + 1,
                    phase = CalibrationPhase.Usable,
                    nextAction = CalibrationAction.Wait("The best calibration could not be staged."),
                    pendingEffect = null,
                    lastError = CalibrationJobError("candidate_stage_failed", "The TV did not return a candidate ID"),
                )
                persist(failed)
                return rejected("candidate_stage_failed", "The TV did not return a candidate ID")
            }
        if (!dsp.isLiveDspVerified()) {
            val candidate = CalibrationCandidateState(candidateId, usable.best.id, usable.best.correctionMode, 0)
            val rolledBack = rollbackIfNeeded(candidateId)
            if (!rolledBack) {
                val unresolved = intent.copy(
                    revision = intent.revision + 1,
                    phase = CalibrationPhase.Restoring,
                    candidate = candidate,
                    nextAction = CalibrationAction.Wait("Restoring the previous calibration after an unverified staging result."),
                    pendingEffect = PendingCalibrationEffect.RestorePrevious(candidateId),
                    lastError = CalibrationJobError("dsp_unverified", "The staged calibration could not be verified or rolled back"),
                )
                persist(unresolved)
                return rejected("dsp_unverified", unresolved.lastError?.message ?: "The staged calibration could not be verified")
            }
            val failed = intent.copy(
                revision = intent.revision + 1,
                phase = CalibrationPhase.Usable,
                nextAction = CalibrationAction.Wait("The best calibration could not be verified."),
                pendingEffect = null,
                lastError = CalibrationJobError(
                    "dsp_unverified",
                    if (rolledBack) "The staged calibration could not be verified" else "The staged calibration could not be verified or rolled back",
                ),
            )
            persist(failed)
            return rejected("dsp_unverified", failed.lastError?.message ?: "The staged calibration could not be verified")
        }
        val candidate = CalibrationCandidateState(candidateId, usable.best.id, usable.best.correctionMode, 0)
        val transition = stateMachine.reduce(intent.copy(pendingEffect = null), CalibrationEvent.CandidateStaged(candidate))
        commit(transition)
        return CalibrationEngineResult.Updated(transition.job)
    }

    private fun autoStageWhenReady(current: CalibrationJob): CalibrationEngineResult {
        if (current.phase != CalibrationPhase.Usable || current.nextAction !is CalibrationAction.Wait) {
            return CalibrationEngineResult.Updated(current)
        }
        return stageBest(current)
    }

    private fun executePending(current: CalibrationJob): CalibrationJob {
        val effect = current.pendingEffect ?: return current
        return when (effect) {
            is PendingCalibrationEffect.AcceptCandidate -> current
            is PendingCalibrationEffect.StageCandidate -> current
            is PendingCalibrationEffect.RestorePrevious -> {
                val restored = rollbackIfNeeded(effect.candidateId)
                if (!restored) {
                    val retry = current.copy(
                        revision = current.revision + 1,
                        phase = CalibrationPhase.Restoring,
                        nextAction = CalibrationAction.Wait("Retrying restoration of the previous calibration."),
                        lastError = CalibrationJobError("dsp_restore_failed", "Previous calibration could not be verified"),
                    )
                    persist(retry)
                    retry
                } else {
                    val finished = current.copy(
                        revision = current.revision + 1,
                        phase = CalibrationPhase.Failed("Previous calibration restored"),
                        candidate = null,
                        pendingEffect = null,
                        nextAction = null,
                        lastError = current.lastError ?: CalibrationJobError("calibration_failed", "Previous calibration restored"),
                    )
                    persist(finished)
                    cleanupRawCaptures(finished.id)
                    finished
                }
            }
            is PendingCalibrationEffect.RollbackThenReoptimize -> {
                if (!rollbackIfNeeded(effect.candidateId)) {
                    val retry = current.copy(
                        revision = current.revision + 1,
                        phase = CalibrationPhase.Restoring,
                        nextAction = CalibrationAction.Wait("Retrying rollback of the previous calibration."),
                        lastError = CalibrationJobError("candidate_rollback_failed", "Candidate rollback could not be verified"),
                    )
                    persist(retry)
                    retry
                } else if (effect.nextMode == null) {
                    val finished = current.copy(
                        revision = current.revision + 1,
                        phase = CalibrationPhase.Failed("No safe improvement was verified; the previous calibration was restored"),
                        candidate = null,
                        pendingEffect = null,
                        nextAction = null,
                        lastError = CalibrationJobError(
                            "validation_worse",
                            "No safe improvement was verified; the previous calibration was restored",
                        ),
                    )
                    persist(finished)
                    cleanupRawCaptures(finished.id)
                    finished
                } else {
                    val optimized = spatialCorrection.optimize(current.ledger.completePositions, effect.nextMode)
                    if (optimized is OptimizationResult.Valid) {
                        val replacement = current.copy(
                            revision = current.revision + 1,
                            phase = CalibrationPhase.Usable,
                            usability = CalibrationUsability.Usable(optimized.solution, requireNotNull(optimized.solution.confidence.grade)),
                            confidence = optimized.solution.confidence,
                            candidate = null,
                            pendingEffect = null,
                            nextAction = CalibrationAction.Wait("A gentler calibration is ready to stage."),
                            lastError = null,
                        )
                        persist(replacement)
                        stageBest(replacement)
                        job ?: replacement
                    } else {
                        executePending(current.copy(pendingEffect = PendingCalibrationEffect.RestorePrevious(effect.candidateId)))
                    }
                }
            }
        }
    }

    private fun rollback(candidateId: CandidateId): Boolean = when (val result = dsp.rollbackCandidate(candidateId)) {
        is CalibrationAudioResult.Failure -> false
        is CalibrationAudioResult.Success -> dsp.isLiveDspVerified()
    }

    private fun rollbackIfNeeded(candidateId: CandidateId): Boolean {
        val pending = dsp.pendingCandidateId()
        if (pending == null) return dsp.isLiveDspVerified()
        if (pending != candidateId) return false
        return rollback(candidateId)
    }

    private fun recover(current: CalibrationJob): CalibrationJob {
        val effect = current.pendingEffect
        if (effect == null && current.phase is CalibrationPhase.Validating && current.candidate != null) {
            return recoverValidation(current, current.candidate)
        }
        if (effect == null) return current
        return when (effect) {
            is PendingCalibrationEffect.StageCandidate -> recoverStage(current, effect)
            is PendingCalibrationEffect.AcceptCandidate -> recoverAccept(current, effect)
            is PendingCalibrationEffect.RollbackThenReoptimize,
            is PendingCalibrationEffect.RestorePrevious -> executePending(current)
        }
    }

    private fun recoverValidation(
        current: CalibrationJob,
        candidate: CalibrationCandidateState,
    ): CalibrationJob {
        val outcome = dsp.pendingValidationOutcome() ?: return current
        return when (outcome) {
            ValidationOutcome.IMPROVED,
            ValidationOutcome.NEUTRAL -> {
                val record = ValidationRecord(candidate.id, outcome, candidate.validationAttemptIndex)
                val acceptEffect = PendingCalibrationEffect.AcceptCandidate(candidate.id)
                val intent = current.copy(
                    revision = current.revision + 1,
                    validationHistory = if (current.validationHistory.lastOrNull() == record) {
                        current.validationHistory
                    } else {
                        current.validationHistory + record
                    },
                    nextAction = CalibrationAction.Wait("Accepting the validated calibration."),
                    pendingEffect = acceptEffect,
                )
                persist(intent)
                recoverAccept(intent, acceptEffect)
            }
            ValidationOutcome.WORSE,
            ValidationOutcome.DSP_ERROR -> {
                val transition = stateMachine.reduce(current, CalibrationEvent.ValidationClassified(outcome))
                val updated = commit(transition)
                executePending(updated)
            }
            ValidationOutcome.INCONCLUSIVE_CAPTURE -> {
                val transition = stateMachine.reduce(current, CalibrationEvent.ValidationClassified(ValidationOutcome.DSP_ERROR))
                val updated = commit(transition)
                executePending(updated)
            }
        }
    }

    private fun recoverStage(
        current: CalibrationJob,
        effect: PendingCalibrationEffect.StageCandidate,
    ): CalibrationJob {
        val usable = current.usability as? CalibrationUsability.Usable
            ?: return failRecovery(current, "Persisted stage intent has no usable solution")
        val pending = dsp.pendingCandidateId()
        if (pending != null) {
            val candidate = CalibrationCandidateState(
                id = pending,
                solutionId = effect.solutionId,
                mode = usable.best.correctionMode,
                validationAttemptIndex = 0,
            )
            return commit(stateMachine.reduce(current.copy(pendingEffect = null), CalibrationEvent.CandidateStaged(candidate)))
        }
        return stageBest(
            current.copy(
                phase = CalibrationPhase.Usable,
                pendingEffect = null,
                nextAction = CalibrationAction.Wait("Resuming calibration staging."),
            ),
        ).job ?: current
    }

    private fun recoverAccept(
        current: CalibrationJob,
        effect: PendingCalibrationEffect.AcceptCandidate,
    ): CalibrationJob {
        val candidate = current.candidate
            ?: return failRecovery(current, "Persisted acceptance intent has no candidate")
        if (candidate.id != effect.candidateId) return failRecovery(current, "Persisted acceptance intent does not match the candidate")
        val pending = dsp.pendingCandidateId()
        val outcome = dsp.pendingValidationOutcome()
            ?: current.validationHistory.lastOrNull { record ->
                record.candidateId == candidate.id && record.attemptIndex == candidate.validationAttemptIndex
            }?.outcome
            ?: return failRecovery(current, "Persisted acceptance intent has no recorded validation result")
        if (outcome != ValidationOutcome.IMPROVED && outcome != ValidationOutcome.NEUTRAL) {
            return failRecovery(current, "Persisted acceptance intent has an invalid validation result")
        }
        val accepted = when {
            pending == null && dsp.isLiveDspVerified() -> true
            pending == candidate.id -> dsp.acceptCandidate(candidate.id) is CalibrationAudioResult.Success
            else -> false
        }
        if (!accepted || !dsp.isLiveDspVerified()) {
            val rollback = if (pending == candidate.id) rollbackIfNeeded(candidate.id) else false
            return failRecovery(
                current,
                if (rollback) "Candidate acceptance was interrupted; the previous calibration was restored" else "Candidate acceptance could not be recovered",
            )
        }
        return commit(
            stateMachine.reduce(current.copy(pendingEffect = null), CalibrationEvent.ValidationClassified(outcome)),
        )
    }

    private fun failRecovery(current: CalibrationJob, message: String): CalibrationJob {
        val failed = current.copy(
            revision = current.revision + 1,
            phase = CalibrationPhase.Failed(message),
            nextAction = null,
            pendingEffect = null,
            lastError = CalibrationJobError("recovery_failed", message),
        )
        persist(failed)
        return failed
    }

    private fun persist(next: CalibrationJob) {
        jobStore.save(next)
        job = next
        listener.onJobChanged(next)
        if (terminal(next)) cleanupRawCaptures(next.id)
    }

    private fun cleanupRawCaptures(jobId: CalibrationJobId) {
        try {
            captureStore.deleteJob(jobId)
        } catch (_: CaptureStoreException) {
            // The compact job snapshot remains the source of truth if cleanup is interrupted.
        }
    }

    private fun commit(transition: CalibrationTransition): CalibrationJob {
        transition.effects.forEach { effect ->
            when (effect) {
                is CalibrationEffect.Persist -> persist(effect.job)
                is CalibrationEffect.Publish -> Unit
                is CalibrationEffect.Execute -> Unit
            }
        }
        return transition.job
    }

    private fun requireJob(jobId: CalibrationJobId): CalibrationJob? = job?.takeIf { it.id == jobId }

    private fun rejected(code: String, message: String): CalibrationEngineResult.Rejected =
        CalibrationEngineResult.Rejected(job, code, message)

    private fun <T> runOnWorker(block: () -> T): T {
        check(!closed) { "Calibration engine is closed" }
        if (workerThread.get() == true) return block()
        val future: Future<T> = executor.submit(Callable(block))
        return try {
            future.get()
        } catch (error: ExecutionException) {
            throw (error.cause ?: error)
        }
    }

    private fun metadataMatchesAction(
        metadata: CaptureUploadMetadata,
        capture: CalibrationAction.Capture?,
        validation: CalibrationAction.Validate?,
    ): Boolean = when {
        capture != null -> metadata.captureId == capture.request.captureId &&
            metadata.position == capture.request.position &&
            metadata.attemptIndex == capture.request.attemptIndex &&
            metadata.channel == capture.request.channel
        validation != null -> metadata.captureId == validation.captureId &&
            metadata.position == validation.position &&
            metadata.channel == CaptureChannel.BOTH
        else -> false
    }

    private fun captureRequest(action: CalibrationAction?): CaptureRequest? = when (action) {
        is CalibrationAction.Capture -> action.request
        is CalibrationAction.Validate -> CaptureRequest(
            captureId = action.captureId,
            position = action.position,
            channel = CaptureChannel.BOTH,
            attemptIndex = action.attemptIndex,
            optional = false,
        )
        else -> null
    }

    private fun rejectionReason(analysis: CalibrationAnalysis): CaptureRejectionReason = when (analysis.status) {
        AnalysisStatus.CAPTURE_CLIPPED -> CaptureRejectionReason.CLIPPING
        AnalysisStatus.CAPTURE_TOO_SHORT -> CaptureRejectionReason.CAPTURE_TOO_SHORT
        AnalysisStatus.SIGNAL_TOO_LOW -> CaptureRejectionReason.SIGNAL_TOO_LOW
        AnalysisStatus.SYNC_MARKER_NOT_FOUND -> CaptureRejectionReason.MARKER_UNRELIABLE
        AnalysisStatus.CLOCK_DRIFT_UNRELIABLE -> CaptureRejectionReason.CLOCK_DRIFT_UNTRUSTED
        AnalysisStatus.DIRECT_ARRIVAL_LOW_CONFIDENCE -> CaptureRejectionReason.DIRECT_ARRIVAL_WEAK
        AnalysisStatus.RESPONSE_NOT_GENERATED -> CaptureRejectionReason.INVALID_PCM
        AnalysisStatus.UNSUPPORTED_SAMPLE_RATE -> CaptureRejectionReason.UNSUPPORTED_SAMPLE_RATE
        AnalysisStatus.OK -> CaptureRejectionReason.INVALID_PCM
    }

    private fun retryValidationAfterCaptureProblem(
        current: CalibrationJob,
        message: String,
    ): CalibrationEngineResult.Updated {
        val transition = stateMachine.reduce(
            current.copy(lastError = CalibrationJobError("validation_capture_invalid", message)),
            CalibrationEvent.ValidationClassified(ValidationOutcome.INCONCLUSIVE_CAPTURE),
        )
        return CalibrationEngineResult.Updated(commit(transition))
    }

    private fun terminal(job: CalibrationJob): Boolean = when (job.phase) {
        CalibrationPhase.Complete,
        CalibrationPhase.Cancelled,
        is CalibrationPhase.Failed -> true
        else -> false
    }
}
