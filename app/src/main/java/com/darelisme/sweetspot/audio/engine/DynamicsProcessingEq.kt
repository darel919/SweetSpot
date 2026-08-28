package com.darelisme.sweetspot

import android.media.audiofx.DynamicsProcessing
import android.util.Log
import java.util.UUID
import kotlin.math.*

/**
 * Production audio engine: a single [DynamicsProcessing] on session 0 with
 * [INTERNAL_BANDS] pre-EQ bands that combines two layers:
 *
 *  - calibration: a read-only 64-band base curve (dB) set by the TV calibration
 *    engine from phone microphone captures. Persisted separately.
 *  - user: 24 user-facing bands (dB, exposed in millibels via the [AudioEngine]
 *    contract) that act as macro controls — each internal band takes the gain of
 *    the nearest user band.
 *
 * Effective gain of internal band i = calibration[i] + userGain[nearestUserBand(i)].
 *
 * Persistence: user gains + enabled + preset restore via [ProfileStore] (last-active
 * and named profiles); calibration restores from its own key.
 */
class DynamicsProcessingEq(private val profileStore: ProfileStore) : AudioEngine {

    enum class ResetFailureStage {
        APPLY,
        PERSIST,
    }

    companion object {
        const val INTERNAL_BANDS = 64
        const val USER_BANDS = 24
        const val MAX_CALIBRATION_GAIN_DB = 12f
        const val MIN_USER_LEVEL_MILLIBELS = -1500
        const val MAX_USER_LEVEL_MILLIBELS = 1500
        private const val SESSION_ID = 0
        private const val PRIORITY = 1000
        const val F_MIN = 20
        const val F_MAX = 20000
        private const val TAG = "DynamicsProcessingEq"
        private const val HEADROOM_PROBE_GAIN_DB = -3f
        private const val INPUT_GAIN_MIN_DB = -60f
        private const val DSP_READBACK_TOLERANCE_DB = 0.25f
        private const val POSITIVE_HEADROOM_TOLERANCE_DB = 0.001f
        const val VALIDATION_WORSE_TOLERANCE_DB = 0.5f
        private const val PRESET_FLAT = 1
        private const val PRESET_NIGHT = 2
        private const val NIGHT_CUT_DB = -6f
        private const val NIGHT_CUT_BANDS = 3
        /** The target TV's 64-band correction path is supported by the verified EQ engine. */
        const val BAND_TRANSFER_CHARACTERIZED = true
        /** Enabled only after acoustic one-channel-at-a-time routing verification. */
        const val INDEPENDENT_ROUTING_VERIFIED = false

        internal fun calibrationTransferCharacterizationError(): String? =
            if (BAND_TRANSFER_CHARACTERIZED) null
            else "Calibration transfer functions have not been characterized on this TV"

        fun isValidCalibrationArray(gains: FloatArray): Boolean =
            gains.size == INTERNAL_BANDS && gains.all { isValidCalibrationGain(it) }

        fun isValidCalibrationGain(gain: Float): Boolean =
            gain.isFinite() && gain >= -MAX_CALIBRATION_GAIN_DB && gain <= MAX_CALIBRATION_GAIN_DB

        fun effectiveCalibrationGain(
            calibrationGainDb: Float,
            userGainDb: Float,
            headroomVerified: Boolean,
        ): Float {
            val requested = calibrationGainDb + userGainDb
            return if (headroomVerified) requested else min(0f, requested)
        }

        internal fun candidateRequiresHeadroom(transaction: CalibrationCandidateTransaction): Boolean {
            val candidate = transaction.candidate
            return if (candidate.left != null && candidate.right != null) {
                candidate.left.any { it > POSITIVE_HEADROOM_TOLERANCE_DB }
                    || candidate.right.any { it > POSITIVE_HEADROOM_TOLERANCE_DB }
            } else {
                candidate.common.any { it > POSITIVE_HEADROOM_TOLERANCE_DB }
            }
        }

        internal data class NormalizedValidationResult(
            val status: CalibrationValidationStatus,
            val beforeDb: Float?,
            val afterDb: Float?,
            val reason: String?,
        )

        internal fun calibrationResetFailureMessage(
            stage: ResetFailureStage,
            originalError: String?,
            restored: Boolean,
        ): String {
            val failure = when (stage) {
                ResetFailureStage.APPLY -> "reset application failed"
                ResetFailureStage.PERSIST -> "reset persistence failed"
            }
            val outcome = if (restored) {
                "previous calibration was restored and verified"
            } else {
                "previous calibration could not be verified"
            }
            return "$failure: ${originalError ?: "unknown error"}; $outcome"
        }

        internal fun normalizeValidationResult(
            requestedStatus: CalibrationValidationStatus,
            beforeDb: Float?,
            afterDb: Float?,
            reason: String?,
        ): NormalizedValidationResult {
            if (requestedStatus == CalibrationValidationStatus.FAILED) {
                return NormalizedValidationResult(
                    status = CalibrationValidationStatus.FAILED,
                    beforeDb = beforeDb?.takeIf { it.isFinite() },
                    afterDb = afterDb?.takeIf { it.isFinite() },
                    reason = reason ?: "Validation failed",
                )
            }
            if (requestedStatus == CalibrationValidationStatus.INCONCLUSIVE) {
                return NormalizedValidationResult(
                    status = CalibrationValidationStatus.INCONCLUSIVE,
                    beforeDb = beforeDb?.takeIf { it.isFinite() },
                    afterDb = afterDb?.takeIf { it.isFinite() },
                    reason = reason ?: "Validation was inconclusive",
                )
            }
            if (requestedStatus == CalibrationValidationStatus.NEUTRAL) {
                return NormalizedValidationResult(
                    status = CalibrationValidationStatus.NEUTRAL,
                    beforeDb = beforeDb?.takeIf { it.isFinite() },
                    afterDb = afterDb?.takeIf { it.isFinite() },
                    reason = reason ?: "Validation was neutral within tolerance",
                )
            }
            if (beforeDb == null || afterDb == null || !beforeDb.isFinite() || !afterDb.isFinite()) {
                return NormalizedValidationResult(
                    status = CalibrationValidationStatus.INCONCLUSIVE,
                    beforeDb = null,
                    afterDb = null,
                    reason = reason ?: "Validation metrics were unavailable",
                )
            }
            val normalizedStatus = when {
                afterDb < beforeDb - VALIDATION_WORSE_TOLERANCE_DB -> CalibrationValidationStatus.PASSED
                afterDb > beforeDb + VALIDATION_WORSE_TOLERANCE_DB -> CalibrationValidationStatus.WORSE
                else -> CalibrationValidationStatus.INCONCLUSIVE
            }
            val consistencyReason = if (requestedStatus != normalizedStatus
                && requestedStatus != CalibrationValidationStatus.INCONCLUSIVE
                && reason == null
            ) {
                "Validation status was derived from the reported metrics"
            } else {
                reason
            }
            return NormalizedValidationResult(normalizedStatus, beforeDb, afterDb, consistencyReason)
        }
    }

    /** Upper cutoff of each real DynamicsProcessing band, not a PEQ center. */
    private val internalFreqs = FloatArray(INTERNAL_BANDS) { i ->
        (F_MIN * (F_MAX.toDouble() / F_MIN).pow((i + 1).toDouble() / INTERNAL_BANDS)).toFloat()
    }
    private val internalCenters = FloatArray(INTERNAL_BANDS) { i ->
        val lower = if (i == 0) F_MIN.toFloat() else internalFreqs[i - 1]
        sqrt(lower * internalFreqs[i])
    }
    private val userFreqs = FloatArray(USER_BANDS) { i ->
        (F_MIN * (F_MAX.toDouble() / F_MIN).pow(i.toDouble() / (USER_BANDS - 1))).toFloat()
    }
    private val userBandForInternal = IntArray(INTERNAL_BANDS) { i ->
        var best = 0
        var bestD = Float.MAX_VALUE
        for (u in 0 until USER_BANDS) {
            val d = abs(internalCenters[i] - userFreqs[u])
            if (d < bestD) { bestD = d; best = u }
        }
        best
    }

    @Volatile private var dp: DynamicsProcessing? = null
    @Volatile private var enabled = true
    @Volatile private var activePreset = PRESET_FLAT
    private var userGains = FloatArray(USER_BANDS)
    private var calibration = FloatArray(INTERNAL_BANDS)
    private var calibrationLeft: FloatArray? = null
    private var calibrationRight: FloatArray? = null
    private var effectiveCalibration = FloatArray(INTERNAL_BANDS)
    private var effectiveCalibrationLeft: FloatArray? = null
    private var effectiveCalibrationRight: FloatArray? = null
    private var calibrationActive = false
    private var inputGainDb = 0f
    private var headroomVerified = false
    private var lastCalibrationApplySucceeded = true
    private var lastCalibrationApplyError: String? = null
    private var lastApplyError: String? = null
    private var measurementBypassState: MeasurementAudioState? = null
    private var calibrationValidationState: MeasurementAudioState? = null
    private var diagnosticProbeCurve: DiagnosticProbeCurve? = null

    private data class DiagnosticProbeCurve(
        val common: FloatArray,
        val left: FloatArray?,
        val right: FloatArray?,
    )

    private data class RequestedCalibrationState(
        val common: FloatArray,
        val left: FloatArray?,
        val right: FloatArray?,
        val active: Boolean,
    )

    @Synchronized
    override fun initialize() {
        if (dp != null) return
        val cfg = DynamicsProcessingProbe().buildConfig(INTERNAL_BANDS, 2)
        val d = try {
            DynamicsProcessing(PRIORITY, SESSION_ID, cfg)
        } catch (e: Throwable) {
            Log.w(TAG, "2-channel engine failed; falling back to 1 channel", e)
            DynamicsProcessing(PRIORITY, SESSION_ID, DynamicsProcessingProbe().buildConfig(INTERNAL_BANDS, 1))
        }
        d.enabled = true
        dp = d
        restore()
        headroomVerified = verifyHeadroomSupport(d)
        val applied = applyAll(trackCalibrationStatus = true)
        val transaction = profileStore.loadCalibrationTransaction()
        if (transaction?.validationStatus == CalibrationValidationStatus.APPLYING
            || transaction?.validationStatus == CalibrationValidationStatus.ROLLING_BACK
        ) {
            if (applied && profileStore.saveActiveCalibrationAndClearCandidate(transaction.previous)) {
                applyRequestedCurve(transaction.previous)
            } else {
                profileStore.saveCandidateValidation(transaction.copy(
                    validationStatus = CalibrationValidationStatus.FAILED,
                    reason = if (transaction.validationStatus == CalibrationValidationStatus.ROLLING_BACK) {
                        "Startup could not complete the pending calibration rollback"
                    } else {
                        "Startup could not verify restoration of the pre-candidate calibration"
                    },
                ))
            }
        }
        Log.i(TAG, "Engine initialized: internalBands=$INTERNAL_BANDS userBands=$USER_BANDS channels=${d.channelCount}")
    }

    @Synchronized
    override fun release() {
        calibrationValidationState?.let { state ->
            calibrationValidationState = null
            restoreMeasurementState(state)
        }
        measurementBypassState?.let { state ->
            measurementBypassState = null
            restoreMeasurementState(state)
        }
        try { dp?.release() } catch (_: Exception) {}
        dp = null
        diagnosticProbeCurve = null
        headroomVerified = false
        inputGainDb = 0f
        effectiveCalibration = FloatArray(INTERNAL_BANDS)
        effectiveCalibrationLeft = null
        effectiveCalibrationRight = null
        Log.i(TAG, "Engine released")
    }

    @Synchronized
    override fun setEnabled(enabled: Boolean): Boolean {
        if (isAudioStateOverrideActive()) return false
        val previous = this.enabled
        return try {
            dp?.enabled = enabled
            if (dp?.enabled != enabled) throw IllegalStateException("DynamicsProcessing enabled readback did not match")
            this.enabled = enabled
            save()
            clearApplyError()
            true
        } catch (error: Throwable) {
            this.enabled = previous
            try { dp?.enabled = previous } catch (_: Throwable) {}
            recordApplyFailure(false, error.message ?: error.javaClass.simpleName)
            false
        }
    }

    @Synchronized
    override fun isEnabled(): Boolean = dp?.enabled ?: false

    @Synchronized
    override fun hasControl(): Boolean = dp?.hasControl() ?: false

    @Synchronized
    override fun setBandLevel(index: Int, millibels: Int): Boolean {
        if (isAudioStateOverrideActive()) return false
        if (index < 0 || index >= USER_BANDS) return false
        if (millibels !in MIN_USER_LEVEL_MILLIBELS..MAX_USER_LEVEL_MILLIBELS) return false
        val previousGains = userGains.copyOf()
        val previousPreset = activePreset
        userGains[index] = millibels / 100f
        activePreset = 0
        if (!applyAll()) {
            userGains = previousGains
            activePreset = previousPreset
            applyAll()
            return false
        }
        save()
        return true
    }

    @Synchronized
    override fun getBandLevels(): IntArray =
        IntArray(USER_BANDS) { (userGains[it] * 100).roundToInt() }

    @Synchronized
    override fun applyPreset(preset: Int): Boolean {
        if (isAudioStateOverrideActive()) return false
        val previousGains = userGains.copyOf()
        val previousPreset = activePreset
        when (preset) {
            PRESET_FLAT -> userGains = FloatArray(USER_BANDS)
            PRESET_NIGHT -> {
                userGains = FloatArray(USER_BANDS)
                for (i in 0 until minOf(NIGHT_CUT_BANDS, USER_BANDS)) userGains[i] = NIGHT_CUT_DB
            }
            else -> return false
        }
        activePreset = preset
        if (!applyAll()) {
            userGains = previousGains
            activePreset = previousPreset
            applyAll()
            return false
        }
        save()
        return true
    }

    @Synchronized
    override fun getActivePreset(): Int = activePreset

    @Synchronized
    override fun getCapabilities(): EngineCapabilities {
        val centers = IntArray(USER_BANDS) { userFreqs[it].roundToInt() }
        return EngineCapabilities(
            bandCount = USER_BANDS,
            bandLevelRange = intArrayOf(-1500, 1500),
            centerFrequenciesHz = centers,
            presets = mapOf(PRESET_FLAT to "Flat", PRESET_NIGHT to "Night")
        )
    }

    @Synchronized
    override fun saveCurrentProfile(name: String) {
        if (isAudioStateOverrideActive()) return
        val levels = IntArray(USER_BANDS) { (userGains[it] * 100).roundToInt() }
        profileStore.saveNamed(name, isEnabled(), activePreset, levels)
        save()
    }

    @Synchronized
    override fun listProfiles(): List<String> = profileStore.listNames()

    @Synchronized
    override fun loadProfile(name: String): Boolean {
        if (measurementBypassState != null || calibrationValidationState != null) return false
        val p = profileStore.loadNamed(name) ?: return false
        val previousGains = userGains.copyOf()
        val previousPreset = activePreset
        val previousEnabled = enabled
        if (p.levels != null && p.levels.size == USER_BANDS) {
            for (i in p.levels.indices) userGains[i] = p.levels[i] / 100f
            activePreset = 0
        } else {
            when (p.preset) {
                PRESET_FLAT -> userGains = FloatArray(USER_BANDS)
                PRESET_NIGHT -> {
                    userGains = FloatArray(USER_BANDS)
                    for (i in 0 until minOf(NIGHT_CUT_BANDS, USER_BANDS)) userGains[i] = NIGHT_CUT_DB
                }
                else -> return false
            }
            activePreset = p.preset
        }
        enabled = p.enabled
        if (!applyAll() || !setEnabled(p.enabled)) {
            userGains = previousGains
            activePreset = previousPreset
            enabled = previousEnabled
            applyAll()
            try { dp?.enabled = previousEnabled } catch (_: Throwable) {}
            return false
        }
        save()
        return true
    }

    @Synchronized
    override fun deleteProfile(name: String) {
        profileStore.deleteNamed(name)
    }

    @Synchronized
    fun getCalibrationBands(): FloatArray = getEffectiveCalibrationBands()

    @Synchronized
    fun getRequestedCalibrationBands(): FloatArray = calibration.copyOf()

    @Synchronized
    fun getEffectiveCalibrationBands(): FloatArray = effectiveCalibration.copyOf()

    @Synchronized
    fun getCalibrationFrequenciesHz(): IntArray =
        IntArray(INTERNAL_BANDS) { internalFreqs[it].roundToInt() }

    @Synchronized
    internal fun exportCalibrationPackage(sourceDevice: CalibrationPackageSourceDevice): CalibrationPackage? {
        if (!calibrationActive || profileStore.loadCalibrationTransaction() != null || !isLiveDspVerified()) return null
        val requested = getRequestedCalibrationBands()
        val effective = getEffectiveCalibrationBands()
        val frequencies = getCalibrationFrequenciesHz()
        if (!isValidCalibrationArray(requested) || !isValidCalibrationArray(effective)) return null
        val requestedLeft = getRequestedCalibrationBandsForChannel(0)
        val requestedRight = getRequestedCalibrationBandsForChannel(1)
        val effectiveLeft = getEffectiveCalibrationBandsForChannel(0)
        val effectiveRight = getEffectiveCalibrationBandsForChannel(1)
        val independent = supportsIndependentCalibration()
        val pairedRequested = if (independent && requestedLeft != null && requestedRight != null) {
            requestedLeft to requestedRight
        } else {
            null
        }
        val pairedEffective = if (independent && effectiveLeft != null && effectiveRight != null) {
            effectiveLeft to effectiveRight
        } else {
            null
        }
        return CalibrationPackage(
            exportedAt = System.currentTimeMillis().toDouble(),
            sourceDevice = sourceDevice,
            active = true,
            frequenciesHz = frequencies.map(Int::toDouble).toDoubleArray(),
            bandsDb = requested,
            leftBandsDb = pairedRequested?.first,
            rightBandsDb = pairedRequested?.second,
            effectiveBandsDb = effective,
            effectiveLeftBandsDb = pairedEffective?.first,
            effectiveRightBandsDb = pairedEffective?.second,
        )
    }

    @Synchronized
    fun getCalibrationBandsForChannel(channel: Int): FloatArray? = when (channel) {
        0 -> effectiveCalibrationLeft?.copyOf()
        1 -> effectiveCalibrationRight?.copyOf()
        else -> null
    }

    @Synchronized
    fun getRequestedCalibrationBandsForChannel(channel: Int): FloatArray? = when (channel) {
        0 -> calibrationLeft?.copyOf()
        1 -> calibrationRight?.copyOf()
        else -> null
    }

    @Synchronized
    fun getEffectiveCalibrationBandsForChannel(channel: Int): FloatArray? = getCalibrationBandsForChannel(channel)

    @Synchronized
    fun supportsIndependentCalibration(): Boolean =
        INDEPENDENT_ROUTING_VERIFIED && (dp?.channelCount ?: 0) >= 2

    @Synchronized
    fun getChannelCount(): Int = dp?.channelCount ?: 0

    @Synchronized
    fun getInputGainDb(): Float = inputGainDb

    @Synchronized
    fun isHeadroomVerified(): Boolean = headroomVerified

    @Synchronized
    fun isCalibrationActive(): Boolean = calibrationActive

    @Synchronized
    fun hasCalibrationProfile(): Boolean =
        calibrationActive || profileStore.loadCalibration() != null || profileStore.loadCalibrationChannels() != null

    /** Toggles the saved room correction without deleting its measured curve. */
    @Synchronized
    fun setCalibrationEnabled(enabled: Boolean): Boolean {
        if (isAudioStateOverrideActive() || !hasCalibrationProfile()) return false
        if (calibrationActive == enabled) return true
        val previous = calibrationActive
        calibrationActive = enabled
        if (!applyAll(trackCalibrationStatus = true)) {
            calibrationActive = previous
            applyAll(trackCalibrationStatus = true)
            return false
        }
        if (!profileStore.saveCalibrationEnabled(enabled)) {
            calibrationActive = previous
            applyAll(trackCalibrationStatus = true)
            return false
        }
        lastCalibrationApplySucceeded = true
        lastCalibrationApplyError = null
        return true
    }

    @Synchronized
    fun wasLastCalibrationApplySuccessful(): Boolean = lastCalibrationApplySucceeded

    @Synchronized
    fun getLastCalibrationApplyError(): String? = lastCalibrationApplyError

    @Synchronized
    fun getLiveDspVerificationError(): String? {
        val d = dp ?: return "DynamicsProcessing is not initialized"
        if (!d.hasControl()) return "DynamicsProcessing does not have control"
        if (d.enabled != enabled) return "DynamicsProcessing enabled state is not verified"
        val expectedInputGain = requiredInputGainDb()
        val actualInputGains = readInputGains(d) ?: return "Input gain readback is unavailable"
        if (actualInputGains.any { abs(it - expectedInputGain) > DSP_READBACK_TOLERANCE_DB }) {
            return "Input gain readback no longer matches the requested state"
        }
        return try {
            for (channel in 0 until d.channelCount) {
                for (band in 0 until INTERNAL_BANDS) {
                    val actual = d.getPreEqBandByChannelIndex(channel, band).gain
                    val expected = expectedEffectiveGain(channel, band)
                    if (!actual.isFinite() || abs(actual - expected) > DSP_READBACK_TOLERANCE_DB) {
                        return "Pre-EQ band readback no longer matches channel $channel band $band"
                    }
                }
            }
            null
        } catch (error: Throwable) {
            error.message ?: "Pre-EQ band readback is unavailable"
        }
    }

    /**
     * Installs a temporary, non-persisted diagnostic curve on the production
     * 64-band effect. This must be the same effect that owns session 0; a
     * second global DynamicsProcessing instance loses control on the TCL.
     */
    @Synchronized
    fun applyDiagnosticProbe(common: FloatArray, left: FloatArray? = null, right: FloatArray? = null): Boolean {
        if (measurementBypassState != null || calibrationValidationState != null) return false
        if (!isValidDiagnosticProbeCurve(common)) return false
        if ((left == null) != (right == null)) return false
        if (left != null && (right == null || !isValidDiagnosticProbeCurve(left) || !isValidDiagnosticProbeCurve(right))) return false
        val hasPositiveGain = if (left != null && right != null) {
            left.any { it > POSITIVE_HEADROOM_TOLERANCE_DB }
                || right.any { it > POSITIVE_HEADROOM_TOLERANCE_DB }
        } else {
            common.any { it > POSITIVE_HEADROOM_TOLERANCE_DB }
        }
        if (hasPositiveGain && !headroomVerified) return false
        val current = diagnosticProbeCurve
        diagnosticProbeCurve = DiagnosticProbeCurve(common.copyOf(), left?.copyOf(), right?.copyOf())
        if (applyAll()) return true
        diagnosticProbeCurve = current
        applyAll()
        return false
    }

    @Synchronized
    fun clearDiagnosticProbe(): Boolean {
        if (measurementBypassState != null || calibrationValidationState != null) return false
        val current = diagnosticProbeCurve ?: return true
        diagnosticProbeCurve = null
        if (applyAll()) return true
        diagnosticProbeCurve = current
        applyAll()
        return false
    }

    @Synchronized
    fun isDiagnosticProbeActive(): Boolean = diagnosticProbeCurve != null

    @Synchronized
    fun getDiagnosticProbeCurveSummary(channel: Int): DynamicsProcessingProbe.CurveSummary? {
        val d = dp ?: return null
        if (channel < 0 || channel >= d.channelCount || diagnosticProbeCurve == null) return null
        return try {
            DynamicsProcessingProbe().readCurveSummary(d, INTERNAL_BANDS, channel)
        } catch (_: Throwable) { null }
    }

    @Synchronized
    fun isLiveDspVerified(): Boolean = getLiveDspVerificationError() == null

    @Synchronized
    internal fun getCalibrationTransaction(): CalibrationCandidateTransaction? =
        profileStore.loadCalibrationTransaction()?.copyArrays()

    @Synchronized
    internal fun applyCalibrationCandidate(gains: FloatArray, left: FloatArray? = null, right: FloatArray? = null): Boolean {
        if (isAudioStateOverrideActive()) return false
        calibrationTransferCharacterizationError()?.let {
            return recordCalibrationFailure(it)
        }
        if (profileStore.loadCalibrationTransaction() != null) {
            recordCalibrationFailure("A calibration candidate is already pending; accept or roll it back first")
            return false
        }
        if (!isValidCalibrationArray(gains)) {
            recordCalibrationFailure("Calibration must contain $INTERNAL_BANDS finite gains within ±${MAX_CALIBRATION_GAIN_DB} dB")
            return false
        }
        if ((left == null) != (right == null)
            || (left != null && !isValidCalibrationArray(left))
            || (right != null && !isValidCalibrationArray(right))
            || (left != null && !supportsIndependentCalibration())
        ) {
            recordCalibrationFailure("Independent calibration requires two valid channel curves on a stereo DSP")
            return false
        }
        val candidateRequiresHeadroom = if (left != null && right != null) {
            left.any { it > POSITIVE_HEADROOM_TOLERANCE_DB }
                || right.any { it > POSITIVE_HEADROOM_TOLERANCE_DB }
        } else {
            gains.any { it > POSITIVE_HEADROOM_TOLERANCE_DB }
        }
        if (!headroomVerified && candidateRequiresHeadroom) {
            recordCalibrationFailure("Positive calibration gains require verified input headroom")
            return false
        }
        val preCandidateLiveState = requestedCurveState()
        val candidate = CalibrationCurveState(
            common = gains.copyOf(),
            left = left?.copyOf(),
            right = right?.copyOf(),
            active = true,
        )
        val transaction = CalibrationCandidateTransaction(
            candidateId = UUID.randomUUID().toString(),
            previous = preCandidateLiveState,
            candidate = candidate,
            validationStatus = CalibrationValidationStatus.APPLYING,
            beforeDb = null,
            afterDb = null,
            reason = null,
        )
        if (!profileStore.saveCandidateApplying(transaction)) {
            recordCalibrationFailure("Could not persist the calibration candidate before applying it")
            return false
        }
        applyRequestedCurve(candidate)
        if (!applyAll(trackCalibrationStatus = true)) {
            applyRequestedCurve(preCandidateLiveState)
            val restored = applyAll(trackCalibrationStatus = true)
            lastCalibrationApplyError = if (restored) {
                profileStore.saveActiveCalibrationAndClearCandidate(preCandidateLiveState)
                lastCalibrationApplyError ?: "Calibration candidate application failed"
            } else {
                profileStore.saveCandidateValidation(transaction.copy(
                    validationStatus = CalibrationValidationStatus.FAILED,
                    reason = "Calibration candidate failed and the pre-candidate calibration could not be verified",
                ))
                "Calibration candidate failed and the pre-candidate calibration could not be verified"
            }
            lastCalibrationApplySucceeded = false
            return false
        }
        if (!profileStore.saveCandidatePendingValidation(transaction)) {
            applyRequestedCurve(preCandidateLiveState)
            val restored = applyAll(trackCalibrationStatus = true)
            if (restored) {
                profileStore.saveCandidateValidation(transaction.copy(
                    validationStatus = CalibrationValidationStatus.FAILED,
                    reason = "Could not persist the pending calibration candidate after restoring the pre-candidate calibration",
                ))
            } else {
                profileStore.saveCandidateValidation(transaction.copy(
                    validationStatus = CalibrationValidationStatus.FAILED,
                    reason = "Could not persist the pending candidate and the pre-candidate calibration could not be verified",
                ))
            }
            recordCalibrationFailure(if (restored) {
                "Could not persist the pending calibration candidate after restoring the pre-candidate calibration"
            } else {
                "Could not persist the pending candidate and the pre-candidate calibration could not be verified"
            })
            return false
        }
        return true
    }

    @Synchronized
    internal fun applyImportedCalibrationCandidate(packageValue: CalibrationPackage): Boolean {
        if (!packageValue.active) {
            recordCalibrationFailure("Inactive calibration packages cannot be imported")
            return false
        }
        if (!applyCalibrationCandidate(packageValue.bandsDb, packageValue.leftBandsDb, packageValue.rightBandsDb)) {
            return false
        }
        val transaction = profileStore.loadCalibrationTransaction()
        if (transaction == null || transaction.validationStatus != CalibrationValidationStatus.PENDING) {
            recordCalibrationFailure("Imported calibration candidate was not persisted")
            return false
        }
        val imported = transaction.copy(
            validationStatus = CalibrationValidationStatus.IMPORTED,
            reason = "Imported calibration was applied and verified on the TV",
        )
        if (profileStore.saveCandidateImported(imported)) return true

        val rolledBack = rollbackCalibrationCandidate(transaction.candidateId)
        recordCalibrationFailure(
            if (rolledBack) {
                "Imported calibration could not be persisted and was rolled back"
            } else {
                "Imported calibration could not be persisted and rollback could not be verified"
            },
        )
        return false
    }

    @Synchronized
    internal fun acceptCalibrationCandidate(candidateId: String): Boolean {
        val transaction = profileStore.loadCalibrationTransaction() ?: return false
        if (transaction.candidateId != candidateId
            || (transaction.validationStatus != CalibrationValidationStatus.PASSED
                && transaction.validationStatus != CalibrationValidationStatus.NEUTRAL
                && transaction.validationStatus != CalibrationValidationStatus.IMPORTED)
        ) {
            recordCalibrationFailure("Calibration candidate is not ready for acceptance")
            return false
        }
        if (!canAcceptCalibrationCandidate(transaction, candidateId, isLiveDspVerified())) {
            recordCalibrationFailure("Calibration candidate cannot be accepted while live DSP readback is degraded")
            return false
        }
        return profileStore.clearCalibrationTransaction()
    }

    @Synchronized
    internal fun rollbackCalibrationCandidate(candidateId: String): Boolean {
        val transaction = profileStore.loadCalibrationTransaction() ?: return false
        if (!canRollbackCalibrationCandidate(transaction, candidateId)) return false
        if (transaction.validationStatus != CalibrationValidationStatus.ROLLING_BACK
            && !profileStore.saveCandidateRollingBack(transaction)
        ) {
            recordCalibrationFailure("Could not persist the calibration rollback before changing live DSP")
            return false
        }
        val candidateLiveState = requestedCurveState()
        applyRequestedCurve(transaction.previous)
        if (!applyAll(trackCalibrationStatus = true)) {
            applyRequestedCurve(candidateLiveState)
            applyAll(trackCalibrationStatus = true)
            profileStore.saveCandidateValidation(transaction.copy(
                validationStatus = CalibrationValidationStatus.FAILED,
                reason = "The pre-candidate calibration could not be verified after rollback",
            ))
            return false
        }
        if (profileStore.saveActiveCalibrationAndClearCandidate(transaction.previous)) return true
        profileStore.saveCandidateValidation(transaction.copy(
            validationStatus = CalibrationValidationStatus.FAILED,
            reason = "The pre-candidate calibration was restored but could not be committed",
        ))
        recordCalibrationFailure("The pre-candidate calibration was restored but could not be committed")
        return false
    }

    @Synchronized
    internal fun recordCalibrationValidation(
        candidateId: String,
        status: CalibrationValidationStatus,
        beforeDb: Float?,
        afterDb: Float?,
        reason: String?,
    ): Boolean {
        val transaction = profileStore.loadCalibrationTransaction() ?: return false
        if (transaction.candidateId != candidateId || transaction.validationStatus != CalibrationValidationStatus.PENDING) return false
        val normalized = normalizeValidationResult(status, beforeDb, afterDb, reason)
        val updated = transaction.copy(
            validationStatus = normalized.status,
            beforeDb = normalized.beforeDb,
            afterDb = normalized.afterDb,
            reason = normalized.reason,
        )
        if (!profileStore.saveCandidateValidation(updated)) return false
        if (normalized.status != CalibrationValidationStatus.WORSE) return true

        val candidateLiveState = requestedCurveState()
        applyRequestedCurve(transaction.previous)
        val restored = applyAll(trackCalibrationStatus = true)
        if (restored && profileStore.saveActiveCalibrationPreservingCandidate(transaction.previous)) return true

        applyRequestedCurve(candidateLiveState)
        applyAll(trackCalibrationStatus = true)
        val restoreError = if (restored) {
            "Measured-worse candidate could not persist its safe rollback"
        } else {
            "Measured-worse candidate could not restore the pre-candidate calibration"
        }
        profileStore.saveCandidateValidation(updated.copy(reason = restoreError))
        lastCalibrationApplySucceeded = false
        lastCalibrationApplyError = restoreError
        return false
    }

    @Synchronized
    fun setCalibrationBands(gains: FloatArray): Boolean {
        if (isAudioStateOverrideActive()) return false
        if (calibrationTransferCharacterizationError() != null) {
            return recordCalibrationFailure("Calibration transfer functions have not been characterized on this TV")
        }
        if (!isValidCalibrationArray(gains)) {
            recordCalibrationFailure("Calibration must contain $INTERNAL_BANDS finite gains within ±${MAX_CALIBRATION_GAIN_DB} dB")
            return false
        }
        if (!headroomVerified && gains.any { it > POSITIVE_HEADROOM_TOLERANCE_DB }) {
            recordCalibrationFailure("Positive calibration gains require verified input headroom")
            return false
        }
        val previous = requestedCalibrationState()
        calibration = gains.copyOf()
        calibrationLeft = null
        calibrationRight = null
        calibrationActive = true
        if (!applyAll(trackCalibrationStatus = true)) {
            return rollbackCalibration(previous)
        }
        if (profileStore.saveCalibration(calibration)) return true
        rollbackCalibration(previous)
        recordCalibrationFailure("Calibration was applied but could not be saved on the TV")
        return false
    }

    @Synchronized
    fun setCalibrationBandsByChannel(left: FloatArray, right: FloatArray): Boolean {
        if (isAudioStateOverrideActive()) return false
        if (calibrationTransferCharacterizationError() != null) return false
        if (!supportsIndependentCalibration()) return false
        if (!isValidCalibrationArray(left) || !isValidCalibrationArray(right)) {
            recordCalibrationFailure("Each channel must contain $INTERNAL_BANDS finite gains within ±${MAX_CALIBRATION_GAIN_DB} dB")
            return false
        }
        if (!headroomVerified && (left.any { it > POSITIVE_HEADROOM_TOLERANCE_DB } || right.any { it > POSITIVE_HEADROOM_TOLERANCE_DB })) {
            recordCalibrationFailure("Positive calibration gains require verified input headroom")
            return false
        }
        val previous = requestedCalibrationState()
        val leftCopy = left.copyOf()
        val rightCopy = right.copyOf()
        calibrationLeft = leftCopy
        calibrationRight = rightCopy
        calibration = FloatArray(INTERNAL_BANDS) { (leftCopy[it] + rightCopy[it]) / 2f }
        calibrationActive = true
        if (!applyAll(trackCalibrationStatus = true)) {
            return rollbackCalibration(previous)
        }
        if (profileStore.saveCalibrationChannels(leftCopy, rightCopy)) return true
        rollbackCalibration(previous)
        recordCalibrationFailure("Channel calibration was applied but could not be saved on the TV")
        return false
    }

    @Synchronized
    fun resetCalibration(): Boolean {
        if (isAudioStateOverrideActive()) return false
        val previous = requestedCalibrationState()
        calibration = FloatArray(INTERNAL_BANDS)
        calibrationLeft = null
        calibrationRight = null
        calibrationActive = false
        val applied = applyAll(trackCalibrationStatus = true)
        if (!applied) {
            val resetError = lastCalibrationApplyError
            val restored = restoreCalibrationAfterReset(previous)
            lastCalibrationApplySucceeded = false
            lastCalibrationApplyError = calibrationResetFailureMessage(
                ResetFailureStage.APPLY,
                resetError,
                restored,
            )
            Log.w(TAG, lastCalibrationApplyError ?: "Calibration reset failed")
            return false
        }
        if (profileStore.clearCalibration()) return true

        val resetError = "Calibration was applied but could not be saved on the TV"
        val restored = restoreCalibrationAfterReset(previous)
        lastCalibrationApplySucceeded = false
        lastCalibrationApplyError = calibrationResetFailureMessage(
            ResetFailureStage.PERSIST,
            resetError,
            restored,
        )
        Log.w(TAG, lastCalibrationApplyError ?: "Calibration reset failed")
        return false
    }

    private fun restoreCalibrationAfterReset(previous: RequestedCalibrationState): Boolean {
        calibration = previous.common.copyOf()
        calibrationLeft = previous.left?.copyOf()
        calibrationRight = previous.right?.copyOf()
        calibrationActive = previous.active
        return applyAll(trackCalibrationStatus = false)
    }

    @Synchronized
    override fun beginMeasurementBypass(): MeasurementAudioOverrideResult {
        measurementBypassState?.let { return MeasurementAudioOverrideResult.Applied(copyMeasurementState(it)) }
        if (diagnosticProbeCurve != null) {
            return MeasurementAudioOverrideResult.Failed("Persistent diagnostic probe is active", true)
        }
        if (calibrationValidationState != null) {
            return MeasurementAudioOverrideResult.Failed("Calibration validation is already active", true)
        }

        val state = MeasurementAudioState(
            enabled = enabled,
            activePreset = activePreset,
            userBandLevelsMillibels = getBandLevels(),
            calibrationGainsDb = calibration.copyOf(),
            calibrationActive = calibrationActive,
            calibrationLeftGainsDb = calibrationLeft?.copyOf(),
            calibrationRightGainsDb = calibrationRight?.copyOf(),
            inputGainDb = inputGainDb,
            headroomVerified = headroomVerified,
        )
        measurementBypassState = state
        enabled = true
        try {
            dp?.enabled = true
            if (dp?.enabled != true || !applyAll()) throw IllegalStateException(lastApplyError ?: "Measurement bypass readback failed")
            return MeasurementAudioOverrideResult.Applied(copyMeasurementState(state))
        } catch (error: Throwable) {
            measurementBypassState = null
            val restored = restoreMeasurementState(state)
            val message = error.message ?: "Measurement bypass could not be verified"
            return MeasurementAudioOverrideResult.Failed(
                if (restored) message else "$message; previous audio state could not be verified",
                restored,
            )
        }
    }

    @Synchronized
    override fun endMeasurementBypass(state: MeasurementAudioState): Boolean {
        val active = measurementBypassState ?: return false
        measurementBypassState = null
        return restoreMeasurementState(active)
    }

    @Synchronized
    override fun beginCalibrationValidation(candidateId: String?): MeasurementAudioOverrideResult {
        calibrationValidationState?.let { return MeasurementAudioOverrideResult.Applied(copyMeasurementState(it)) }
        if (diagnosticProbeCurve != null) {
            return MeasurementAudioOverrideResult.Failed("Persistent diagnostic probe is active", true)
        }
        if (measurementBypassState != null) {
            return MeasurementAudioOverrideResult.Failed("Measurement bypass is already active", true)
        }
        val transaction = profileStore.loadCalibrationTransaction()
        if (candidateId.isNullOrBlank()
            || transaction?.candidateId != candidateId
            || transaction.validationStatus != CalibrationValidationStatus.PENDING
        ) {
            return MeasurementAudioOverrideResult.Failed("Validation requires the pending calibration candidate", true)
        }
        if (candidateRequiresHeadroom(transaction) && !headroomVerified) {
            return MeasurementAudioOverrideResult.Failed("Validation requires verified input headroom", true)
        }
        if (!calibrationActive) return MeasurementAudioOverrideResult.Failed("No active calibration is available for validation", true)

        val state = MeasurementAudioState(
            enabled = enabled,
            activePreset = activePreset,
            userBandLevelsMillibels = getBandLevels(),
            calibrationGainsDb = calibration.copyOf(),
            calibrationActive = calibrationActive,
            calibrationLeftGainsDb = calibrationLeft?.copyOf(),
            calibrationRightGainsDb = calibrationRight?.copyOf(),
            inputGainDb = inputGainDb,
            headroomVerified = headroomVerified,
        )
        calibrationValidationState = state
        enabled = true
        try {
            dp?.enabled = true
            if (dp?.enabled != true || !applyAll()) throw IllegalStateException(lastApplyError ?: "Validation override readback failed")
            return MeasurementAudioOverrideResult.Applied(copyMeasurementState(state))
        } catch (error: Throwable) {
            calibrationValidationState = null
            val restored = restoreMeasurementState(state)
            val message = error.message ?: "Validation override could not be verified"
            return MeasurementAudioOverrideResult.Failed(
                if (restored) message else "$message; previous audio state could not be verified",
                restored,
            )
        }
    }

    @Synchronized
    override fun endCalibrationValidation(state: MeasurementAudioState): Boolean {
        val active = calibrationValidationState ?: return false
        calibrationValidationState = null
        return restoreMeasurementState(active)
    }

    @Synchronized
    private fun applyAll(trackCalibrationStatus: Boolean = false): Boolean {
        val d = dp ?: return recordApplyFailure(trackCalibrationStatus, "DynamicsProcessing is not initialized")
        if (!d.hasControl()) return recordApplyFailure(trackCalibrationStatus, "DynamicsProcessing does not have control")
        val nCh = d.channelCount
        val requestedInputGain = requiredInputGainDb()
        if (!setInputGain(d, requestedInputGain)) {
            return recordApplyFailure(trackCalibrationStatus, "Input gain readback did not match the requested value")
        }

        return try {
            val appliedCalibrationByChannel = Array(nCh) { FloatArray(INTERNAL_BANDS) }
            for (ch in 0 until nCh) {
                for (i in 0 until INTERNAL_BANDS) {
                    val userGain = userGains[userBandForInternal[i]]
                    val effectiveGain = expectedEffectiveGain(ch, i)
                    val band = d.getPreEqBandByChannelIndex(ch, i)
                    band.setGain(effectiveGain)
                    d.setPreEqBandByChannelIndex(ch, i, band)
                    val actualGain = d.getPreEqBandByChannelIndex(ch, i).gain
                    if (!actualGain.isFinite() || abs(actualGain - effectiveGain) > DSP_READBACK_TOLERANCE_DB) {
                        throw IllegalStateException(
                            "Pre-EQ band readback mismatch at channel $ch band $i: " +
                                "requested=$effectiveGain actual=$actualGain"
                        )
                    }
                    appliedCalibrationByChannel[ch][i] = if (measurementBypassState != null) {
                        0f
                    } else {
                        actualGain - if (calibrationValidationState != null) 0f else userGain
                    }
                }
            }
            if (diagnosticProbeCurve == null) updateEffectiveCalibration(appliedCalibrationByChannel)
            if (trackCalibrationStatus) {
                lastCalibrationApplySucceeded = true
                lastCalibrationApplyError = null
            }
            clearApplyError()
            true
        } catch (error: Throwable) {
            if (trackCalibrationStatus) {
                lastCalibrationApplySucceeded = false
                lastCalibrationApplyError = error.message ?: error.javaClass.simpleName
            }
            lastApplyError = error.message ?: error.javaClass.simpleName
            captureEffectiveCalibration(d)
            Log.w(TAG, "DynamicsProcessing calibration application failed", error)
            false
        }
    }

    @Synchronized
    private fun requiredInputGainDb(): Float {
        if (!headroomVerified) return 0f
        var maximum = 0f
        val probe = diagnosticProbeCurve
        if (probe != null) {
            maximum = max(maximum, probe.common.maxOrNull()?.toDouble()?.toFloat() ?: 0f)
            maximum = max(maximum, probe.left?.maxOrNull()?.toDouble()?.toFloat() ?: 0f)
            maximum = max(maximum, probe.right?.maxOrNull()?.toDouble()?.toFloat() ?: 0f)
            return if (maximum > 0f) -(maximum + 0.5f) else 0f
        }
        if (measurementBypassState != null) {
            return 0f
        }
        val channelCount = dp?.channelCount ?: 0
        for (ch in 0 until maxOf(1, channelCount)) {
            val channelCalibration = when (ch) {
                0 -> calibrationLeft ?: calibration
                1 -> calibrationRight ?: calibration
                else -> calibration
            }
            for (i in 0 until INTERNAL_BANDS) {
                val userGain = if (calibrationValidationState == null) userGains[userBandForInternal[i]] else 0f
                val calibrationGain = if (calibrationActive) channelCalibration[i] else 0f
                maximum = max(maximum, calibrationGain + userGain)
            }
        }
        return if (maximum > 0f) -(maximum + 0.5f) else 0f
    }

    @Synchronized
    private fun expectedEffectiveGain(channel: Int, band: Int): Float {
        diagnosticProbeCurve?.let { probe ->
            return when (channel) {
                0 -> (probe.left ?: probe.common)[band]
                1 -> (probe.right ?: probe.common)[band]
                else -> probe.common[band]
            }
        }
        if (measurementBypassState != null) return 0f
        val channelCalibration = when (channel) {
            0 -> calibrationLeft ?: calibration
            1 -> calibrationRight ?: calibration
            else -> calibration
        }
        val userGain = if (calibrationValidationState == null) userGains[userBandForInternal[band]] else 0f
        val calibrationGain = if (calibrationActive) channelCalibration[band] else 0f
        return effectiveCalibrationGain(calibrationGain, userGain, headroomVerified)
    }

    private fun isValidDiagnosticProbeCurve(gains: FloatArray): Boolean =
        gains.size == INTERNAL_BANDS && gains.all { gain ->
            gain.isFinite()
                && gain >= DynamicsProcessingProbe.MIN_PROBE_GAIN_DB
                && gain <= DynamicsProcessingProbe.MAX_PROBE_GAIN_DB
        }

    private fun setInputGain(d: DynamicsProcessing, requestedDb: Float): Boolean {
        val bounded = requestedDb.coerceIn(INPUT_GAIN_MIN_DB, 0f)
        try {
            d.setInputGainAllChannelsTo(bounded)
            val actual = readInputGains(d)
            val verified = actual != null && actual.all { gain ->
                abs(gain - bounded) <= DSP_READBACK_TOLERANCE_DB
            }
            inputGainDb = actual?.firstOrNull() ?: 0f
            return verified
        } catch (error: Throwable) {
            inputGainDb = 0f
            Log.w(TAG, "Input gain application failed", error)
            return false
        }
    }

    private fun verifyHeadroomSupport(d: DynamicsProcessing): Boolean {
        val negativeVerified = try {
            d.setInputGainAllChannelsTo(HEADROOM_PROBE_GAIN_DB)
            readInputGains(d)?.all { gain ->
                abs(gain - HEADROOM_PROBE_GAIN_DB) <= DSP_READBACK_TOLERANCE_DB
            } == true
        } catch (error: Throwable) {
            Log.w(TAG, "Negative input-gain headroom probe failed", error)
            false
        }
        val restored = try {
            d.setInputGainAllChannelsTo(0f)
            readInputGains(d)?.all { gain -> abs(gain) <= DSP_READBACK_TOLERANCE_DB } == true
        } catch (error: Throwable) {
            Log.w(TAG, "Input-gain headroom probe could not restore 0 dB", error)
            false
        }
        inputGainDb = 0f
        return negativeVerified && restored
    }

    private fun readInputGains(d: DynamicsProcessing): FloatArray? = try {
        if (d.channelCount <= 0) {
            null
        } else {
            FloatArray(d.channelCount) { channel ->
                d.getInputGainByChannelIndex(channel).also { gain ->
                    if (!gain.isFinite()) throw IllegalStateException("Non-finite input-gain readback")
                }
            }
        }
    } catch (_: Throwable) {
        null
    }

    private fun updateEffectiveCalibration(appliedByChannel: Array<FloatArray>) {
        if (measurementBypassState != null || appliedByChannel.isEmpty()) {
            effectiveCalibration = FloatArray(INTERNAL_BANDS)
            effectiveCalibrationLeft = null
            effectiveCalibrationRight = null
            return
        }
        effectiveCalibration = FloatArray(INTERNAL_BANDS) { band ->
            appliedByChannel.map { it[band] }.average().toFloat()
        }
        if (calibrationLeft != null && calibrationRight != null && appliedByChannel.size >= 2) {
            effectiveCalibrationLeft = appliedByChannel[0].copyOf()
            effectiveCalibrationRight = appliedByChannel[1].copyOf()
        } else {
            effectiveCalibrationLeft = null
            effectiveCalibrationRight = null
        }
    }

    private fun captureEffectiveCalibration(d: DynamicsProcessing) {
        if (measurementBypassState != null || calibrationValidationState != null) return
        try {
            val actualByChannel = Array(d.channelCount) { channel ->
                FloatArray(INTERNAL_BANDS) { band ->
                    val actualGain = d.getPreEqBandByChannelIndex(channel, band).gain
                    if (!actualGain.isFinite()) throw IllegalStateException("Non-finite pre-EQ readback")
                    actualGain - userGains[userBandForInternal[band]]
                }
            }
            updateEffectiveCalibration(actualByChannel)
        } catch (error: Throwable) {
            Log.w(TAG, "Could not capture effective calibration after DSP failure", error)
        }
    }

    private fun requestedCalibrationState() = RequestedCalibrationState(
        common = calibration.copyOf(),
        left = calibrationLeft?.copyOf(),
        right = calibrationRight?.copyOf(),
        active = calibrationActive,
    )

    private fun requestedCurveState() = CalibrationCurveState(
        common = calibration.copyOf(),
        left = calibrationLeft?.copyOf(),
        right = calibrationRight?.copyOf(),
        active = calibrationActive,
    )

    /** Applies only the calibration layer and leaves the separate user EQ state unchanged. */
    private fun applyRequestedCurve(curve: CalibrationCurveState) {
        calibration = curve.common.copyOf()
        calibrationLeft = if (INDEPENDENT_ROUTING_VERIFIED) curve.left?.copyOf() else null
        calibrationRight = if (INDEPENDENT_ROUTING_VERIFIED) curve.right?.copyOf() else null
        calibrationActive = curve.active
    }

    private fun rollbackCalibration(previous: RequestedCalibrationState): Boolean {
        val failure = lastCalibrationApplyError ?: "Calibration application failed"
        calibration = previous.common.copyOf()
        calibrationLeft = previous.left?.copyOf()
        calibrationRight = previous.right?.copyOf()
        calibrationActive = previous.active
        val restored = applyAll(trackCalibrationStatus = true)
        lastCalibrationApplySucceeded = false
        lastCalibrationApplyError = if (restored) {
            failure
        } else {
            "$failure; rollback could not be verified"
        }
        return false
    }

    private fun recordCalibrationFailure(message: String): Boolean {
        lastCalibrationApplySucceeded = false
        lastCalibrationApplyError = message
        Log.w(TAG, message)
        return false
    }

    private fun recordApplyFailure(trackCalibrationStatus: Boolean, message: String): Boolean {
        if (trackCalibrationStatus) {
            lastCalibrationApplySucceeded = false
            lastCalibrationApplyError = message
        }
        lastApplyError = message
        Log.w(TAG, message)
        return false
    }

    private fun clearApplyError() {
        lastApplyError = null
    }

    private fun copyMeasurementState(state: MeasurementAudioState): MeasurementAudioState = state.copy(
        userBandLevelsMillibels = state.userBandLevelsMillibels.copyOf(),
        calibrationGainsDb = state.calibrationGainsDb.copyOf(),
        calibrationLeftGainsDb = state.calibrationLeftGainsDb?.copyOf(),
        calibrationRightGainsDb = state.calibrationRightGainsDb?.copyOf(),
    )

    private fun restoreMeasurementState(state: MeasurementAudioState): Boolean {
        if (state.userBandLevelsMillibels.size == USER_BANDS) {
            userGains = FloatArray(USER_BANDS) { i -> state.userBandLevelsMillibels[i] / 100f }
        }
        if (state.calibrationGainsDb.size == INTERNAL_BANDS) {
            calibration = state.calibrationGainsDb.copyOf()
        }
        calibrationLeft = state.calibrationLeftGainsDb?.takeIf { it.size == INTERNAL_BANDS }?.copyOf()
        calibrationRight = state.calibrationRightGainsDb?.takeIf { it.size == INTERNAL_BANDS }?.copyOf()
        activePreset = state.activePreset
        calibrationActive = state.calibrationActive
        enabled = state.enabled
        val restored = applyAll()
        val enabledRestored = try {
            dp?.enabled = state.enabled
            dp?.enabled == state.enabled
        } catch (_: Throwable) {
            false
        }
        return restored && enabledRestored
    }

    private fun restore() {
        val saved = profileStore.load()
        if (saved.levels != null && saved.levels.size == USER_BANDS) {
            for (i in saved.levels.indices) userGains[i] = saved.levels[i] / 100f
            activePreset = 0
        } else {
            when (saved.preset) {
                PRESET_FLAT -> {
                    userGains = FloatArray(USER_BANDS)
                    activePreset = PRESET_FLAT
                }
                PRESET_NIGHT -> {
                    userGains = FloatArray(USER_BANDS)
                    for (i in 0 until minOf(NIGHT_CUT_BANDS, USER_BANDS)) userGains[i] = NIGHT_CUT_DB
                    activePreset = PRESET_NIGHT
                }
                else -> {
                    userGains = FloatArray(USER_BANDS)
                    activePreset = PRESET_FLAT
                }
            }
        }
        enabled = saved.enabled
        dp?.enabled = saved.enabled
        val cal = profileStore.loadCalibration()
        val channels = profileStore.loadCalibrationChannels()
        if (INDEPENDENT_ROUTING_VERIFIED && channels != null && isValidCalibrationArray(channels.first) && isValidCalibrationArray(channels.second)) {
            calibrationLeft = channels.first.copyOf()
            calibrationRight = channels.second.copyOf()
            calibration = FloatArray(INTERNAL_BANDS) { (channels.first[it] + channels.second[it]) / 2f }
            calibrationActive = profileStore.isCalibrationEnabled()
        } else if (!INDEPENDENT_ROUTING_VERIFIED && channels != null
            && isValidCalibrationArray(channels.first) && isValidCalibrationArray(channels.second)) {
            calibration = FloatArray(INTERNAL_BANDS) { (channels.first[it] + channels.second[it]) / 2f }
            calibrationActive = profileStore.isCalibrationEnabled()
        } else if (channels != null) {
            profileStore.clearActiveCalibrationOnly()
        } else if (cal != null && isValidCalibrationArray(cal)) {
            for (i in 0 until INTERNAL_BANDS) calibration[i] = cal[i]
            calibrationActive = profileStore.isCalibrationEnabled()
        } else if (cal != null) {
            profileStore.clearActiveCalibrationOnly()
        }
        val transaction = profileStore.loadCalibrationTransaction()
        if (profileStore.hasCalibrationTransactionData() && transaction == null) {
            calibration = FloatArray(INTERNAL_BANDS)
            calibrationLeft = null
            calibrationRight = null
            calibrationActive = false
            profileStore.clearActiveCalibrationOnly()
            lastCalibrationApplySucceeded = false
            lastCalibrationApplyError = "Calibration transaction is corrupt; calibration was disabled"
            return
        }
        transaction?.let {
            when (it.validationStatus.recoveryTarget()) {
                CalibrationRecoveryTarget.PREVIOUS -> {
                    applyRequestedCurve(it.previous)
                    profileStore.saveActiveCalibrationPreservingCandidate(it.previous)
                }
                CalibrationRecoveryTarget.CANDIDATE -> applyRequestedCurve(it.candidate)
            }
        }
    }

    private fun save() {
        val levels = IntArray(USER_BANDS) { (userGains[it] * 100).roundToInt() }
        profileStore.save(isEnabled(), activePreset, levels)
    }

    private fun isAudioStateOverrideActive(): Boolean =
        dp == null ||
            diagnosticProbeCurve != null ||
            measurementBypassState != null ||
            calibrationValidationState != null
}
