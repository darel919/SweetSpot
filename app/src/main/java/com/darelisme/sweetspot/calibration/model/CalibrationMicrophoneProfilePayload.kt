package com.darelisme.sweetspot.calibration

import kotlin.math.abs

/**
 * Compact, versioned microphone calibration data supplied by the remote
 * capture device. The TV validates the shape, then converts it to the pure
 * numerical profile used by the analyzer.
 */
class CalibrationMicrophoneProfilePayload(
    val id: String,
    val revision: String,
    frequenciesHz: FloatArray,
    responseDb: FloatArray,
    val normalizeAtHz: Float,
    val trustMinHz: Float,
    val trustFullMaxHz: Float,
    val trustTaperToHz: Float,
    val capturePathStatus: String,
) {
    val frequenciesHz: FloatArray = frequenciesHz.copyOf()
    val responseDb: FloatArray = responseDb.copyOf()

    init {
        require(id.isNotBlank() && id.length <= MAX_TEXT)
        require(revision.isNotBlank() && revision.length <= MAX_TEXT)
        require(frequenciesHz.size in MIN_POINTS..MAX_POINTS)
        require(frequenciesHz.size == responseDb.size)
        require(frequenciesHz.all { it.isFinite() && it in MIN_FREQUENCY_HZ..MAX_FREQUENCY_HZ })
        require(responseDb.all { it.isFinite() && abs(it) <= MAX_RESPONSE_DB })
        require(frequenciesHz.indices.drop(1).all { index -> frequenciesHz[index] > frequenciesHz[index - 1] })
        require(normalizeAtHz.isFinite() && normalizeAtHz in frequenciesHz.first()..frequenciesHz.last())
        require(trustMinHz.isFinite() && trustFullMaxHz.isFinite() && trustTaperToHz.isFinite())
        require(trustMinHz in MIN_FREQUENCY_HZ..MAX_FREQUENCY_HZ)
        require(trustFullMaxHz in MIN_FREQUENCY_HZ..MAX_FREQUENCY_HZ)
        require(trustTaperToHz in MIN_FREQUENCY_HZ..MAX_FREQUENCY_HZ)
        require(trustMinHz < trustFullMaxHz && trustFullMaxHz < trustTaperToHz)
        require(capturePathStatus == VALIDATED || capturePathStatus == PROVISIONAL || capturePathStatus == UNVALIDATED)
    }

    fun isCorrectionEligible(): Boolean = capturePathStatus == VALIDATED

    fun toAnalyzerProfile(): MicrophoneCalibrationProfile = MicrophoneCalibrationProfile(
        frequenciesHz = frequenciesHz,
        responseDb = responseDb,
        normalizeAtHz = normalizeAtHz,
        trustMinHz = trustMinHz,
        trustFullMaxHz = trustFullMaxHz,
        trustTaperToHz = trustTaperToHz,
    )

    fun copyOf(): CalibrationMicrophoneProfilePayload = CalibrationMicrophoneProfilePayload(
        id = id,
        revision = revision,
        frequenciesHz = frequenciesHz,
        responseDb = responseDb,
        normalizeAtHz = normalizeAtHz,
        trustMinHz = trustMinHz,
        trustFullMaxHz = trustFullMaxHz,
        trustTaperToHz = trustTaperToHz,
        capturePathStatus = capturePathStatus,
    )

    fun sameCalibrationData(other: CalibrationMicrophoneProfilePayload?): Boolean =
        other != null &&
            id == other.id &&
            revision == other.revision &&
            frequenciesHz.contentEquals(other.frequenciesHz) &&
            responseDb.contentEquals(other.responseDb) &&
            normalizeAtHz == other.normalizeAtHz &&
            trustMinHz == other.trustMinHz &&
            trustFullMaxHz == other.trustFullMaxHz &&
            trustTaperToHz == other.trustTaperToHz &&
            capturePathStatus == other.capturePathStatus

    override fun equals(other: Any?): Boolean =
        other is CalibrationMicrophoneProfilePayload && sameCalibrationData(other)

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + revision.hashCode()
        result = 31 * result + frequenciesHz.contentHashCode()
        result = 31 * result + responseDb.contentHashCode()
        result = 31 * result + normalizeAtHz.toBits()
        result = 31 * result + trustMinHz.toBits()
        result = 31 * result + trustFullMaxHz.toBits()
        result = 31 * result + trustTaperToHz.toBits()
        result = 31 * result + capturePathStatus.hashCode()
        return result
    }

    companion object {
        const val VALIDATED = "validated"
        const val PROVISIONAL = "provisional"
        const val UNVALIDATED = "unvalidated"
        const val MIN_POINTS = 2
        const val MAX_POINTS = 512
        const val MAX_TEXT = 128
        const val MIN_FREQUENCY_HZ = 1f
        const val MAX_FREQUENCY_HZ = 192_000f
        const val MAX_RESPONSE_DB = 120f
    }
}
