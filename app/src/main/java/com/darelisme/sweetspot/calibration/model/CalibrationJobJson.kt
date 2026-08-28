package com.darelisme.sweetspot.calibration.model

import org.json.JSONArray
import org.json.JSONObject

object CalibrationJobJson {
    fun view(job: CalibrationJob): JSONObject = JSONObject().apply {
        put("jobId", job.id.value)
        put("createdAtMs", job.createdAtMs)
        put("revision", job.revision)
        put("analyzerRevision", job.analyzerRevision.value)
        put("sweepRevision", job.sweepRevision.value)
        put("phase", phase(job.phase))
        put("acceptedPositions", JSONArray(job.ledger.completePositions.map { it.position.name.lowercase() }))
        put("excludedPositions", JSONArray(excludedPositions(job)))
        put("historicalAttemptCount", job.ledger.attempts.size)
        put("optionalFailureCount", job.ledger.rejectedAttempts.count { it.request.optional })
        put("minimumViableCalibration", job.minimumViableCalibration)
        put("bestSolution", job.bestSolution?.let(::solution) ?: JSONObject.NULL)
        put("confidence", job.confidence?.let(::confidence) ?: JSONObject.NULL)
        put("nextAction", job.nextAction?.let(::action) ?: JSONObject.NULL)
        put("activeCandidateId", job.candidate?.id?.value ?: JSONObject.NULL)
        put("validationState", validationState(job))
        put("lastError", job.lastError?.let { error ->
            JSONObject().put("code", error.code).put("message", error.message)
        } ?: JSONObject.NULL)
    }

    private fun excludedPositions(job: CalibrationJob): List<String> =
        CalibrationPosition.entries.filter { position ->
            position.optional && job.ledger.complete(position) == null &&
                job.ledger.rejectedAttempts.any { it.request.position == position }
        }.map { it.name.lowercase() }

    private fun phase(phase: CalibrationPhase): String = when (phase) {
        CalibrationPhase.CenterPreflight -> "center_preflight"
        CalibrationPhase.MeasuringRequired -> "measuring_required"
        CalibrationPhase.Usable -> "usable"
        CalibrationPhase.Refining -> "refining"
        CalibrationPhase.CandidatePending -> "candidate_pending"
        CalibrationPhase.Validating -> "validating"
        CalibrationPhase.Reoptimizing -> "reoptimizing"
        CalibrationPhase.Restoring -> "restoring"
        CalibrationPhase.Complete -> "complete"
        is CalibrationPhase.Failed -> "failed"
        CalibrationPhase.Cancelled -> "cancelled"
    }

    private fun confidence(value: CalibrationConfidence): JSONObject = JSONObject().apply {
        put("usableBandCount", value.usableBandCount)
        put("totalBandCount", CalibrationBandGrid.BAND_COUNT)
        put("score", value.score.toDouble())
        put("grade", value.grade?.name?.lowercase() ?: JSONObject.NULL)
    }

    private fun solution(value: CalibrationSolution): JSONObject = JSONObject().apply {
        put("solutionId", value.id.value)
        put("sourcePositionIds", JSONArray(value.sourcePositions.sortedBy { it.ordinal }.map { it.name.lowercase() }))
        put("confidence", confidence(value.confidence))
        put("score", value.score.toDouble())
        put("correctionMode", value.correctionMode.name.lowercase())
    }

    private fun action(value: CalibrationAction): JSONObject = when (value) {
        is CalibrationAction.Capture -> JSONObject().apply {
            put("kind", "capture")
            put("captureId", value.request.captureId.value)
            put("positionId", value.request.position.name.lowercase())
            put("channel", value.request.channel.name.lowercase())
            put("attemptIndex", value.request.attemptIndex)
            put("optional", value.request.optional)
            put("instruction", value.instruction)
        }
        is CalibrationAction.Validate -> JSONObject().apply {
            put("kind", "validate")
            put("captureId", value.captureId.value)
            put("positionId", value.position.name.lowercase())
            put("candidateId", value.candidateId.value)
            put("attemptIndex", value.attemptIndex)
            put("instruction", value.instruction)
        }
        is CalibrationAction.Wait -> JSONObject().put("kind", "wait").put("message", value.message)
        is CalibrationAction.Complete -> JSONObject().put("kind", "complete").put("solutionId", value.solutionId.value)
    }

    private fun validationState(job: CalibrationJob): String {
        val latest = job.validationHistory.lastOrNull()?.outcome
        return when {
            job.phase is CalibrationPhase.Validating || job.phase is CalibrationPhase.CandidatePending -> "pending"
            job.phase is CalibrationPhase.Restoring -> "rolling_back"
            latest == ValidationOutcome.WORSE -> "worse"
            latest == ValidationOutcome.INCONCLUSIVE_CAPTURE -> "inconclusive"
            latest == ValidationOutcome.DSP_ERROR -> "failed"
            latest == ValidationOutcome.IMPROVED || latest == ValidationOutcome.NEUTRAL -> "passed"
            else -> "none"
        }
    }
}
