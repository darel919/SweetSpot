package com.darelisme.sweetspot

import android.media.audiofx.DynamicsProcessing
import android.util.Log
import kotlin.math.*

/**
 * Production audio engine: a single [DynamicsProcessing] on session 0 with
 * [INTERNAL_BANDS] pre-EQ bands that combines two layers:
 *
 *  - calibration: a read-only 64-band base curve (dB) set ONLY by the calibrate
 *    wizard (fed by iPhone-mic measurements). Persisted separately.
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

    companion object {
        const val INTERNAL_BANDS = 64
        const val USER_BANDS = 24
        const val MAX_CALIBRATION_GAIN_DB = 12f
        private const val SESSION_ID = 0
        private const val PRIORITY = 1000
        const val F_MIN = 20
        const val F_MAX = 20000
        private const val TAG = "DynamicsProcessingEq"
        private const val HEADROOM_PROBE_GAIN_DB = -3f
        private const val INPUT_GAIN_MIN_DB = -60f
        private const val DSP_READBACK_TOLERANCE_DB = 0.25f
        private const val PRESET_FLAT = 1
        private const val PRESET_NIGHT = 2
        private const val NIGHT_CUT_DB = -6f
        private const val NIGHT_CUT_BANDS = 3

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
    private var userGains = FloatArray(USER_BANDS)        // dB
    private var calibration = FloatArray(INTERNAL_BANDS)  // dB, read-only base
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
    private var measurementBypassState: MeasurementAudioState? = null
    private var calibrationValidationState: MeasurementAudioState? = null

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
        applyAll(trackCalibrationStatus = true)
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
        headroomVerified = false
        inputGainDb = 0f
        effectiveCalibration = FloatArray(INTERNAL_BANDS)
        effectiveCalibrationLeft = null
        effectiveCalibrationRight = null
        Log.i(TAG, "Engine released")
    }

    @Synchronized
    override fun setEnabled(enabled: Boolean) {
        if (isAudioStateOverrideActive()) return
        this.enabled = enabled
        dp?.enabled = enabled
        save()
    }

    @Synchronized
    override fun isEnabled(): Boolean = dp?.enabled ?: false

    @Synchronized
    override fun hasControl(): Boolean = dp?.hasControl() ?: false

    @Synchronized
    override fun setBandLevel(index: Int, millibels: Int) {
        if (isAudioStateOverrideActive()) return
        if (index < 0 || index >= USER_BANDS) return
        userGains[index] = millibels / 100f
        activePreset = 0
        applyAll()
        save()
    }

    @Synchronized
    override fun getBandLevels(): IntArray =
        IntArray(USER_BANDS) { (userGains[it] * 100).roundToInt() }

    @Synchronized
    override fun applyPreset(preset: Int) {
        if (isAudioStateOverrideActive()) return
        when (preset) {
            PRESET_FLAT -> userGains = FloatArray(USER_BANDS)
            PRESET_NIGHT -> {
                userGains = FloatArray(USER_BANDS)
                for (i in 0 until minOf(NIGHT_CUT_BANDS, USER_BANDS)) userGains[i] = NIGHT_CUT_DB
            }
            else -> return
        }
        activePreset = preset
        applyAll()
        save()
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
        if (measurementBypassState != null) return
        val levels = IntArray(USER_BANDS) { (userGains[it] * 100).roundToInt() }
        profileStore.saveNamed(name, isEnabled(), activePreset, levels)
        save()
    }

    @Synchronized
    override fun listProfiles(): List<String> = profileStore.listNames()

    @Synchronized
    override fun loadProfile(name: String) {
        if (measurementBypassState != null) return
        val p = profileStore.loadNamed(name) ?: return
        if (p.levels != null && p.levels.size == USER_BANDS) {
            for (i in p.levels.indices) userGains[i] = p.levels[i] / 100f
            activePreset = 0
        } else {
            applyPreset(p.preset)
        }
        enabled = p.enabled
        dp?.enabled = p.enabled
        applyAll()
        save()
    }

    @Synchronized
    override fun deleteProfile(name: String) {
        profileStore.deleteNamed(name)
    }

    // --- Calibration (read-only base curve; wizard/API only) ---

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
    fun supportsIndependentCalibration(): Boolean = (dp?.channelCount ?: 0) >= 2

    @Synchronized
    fun getChannelCount(): Int = dp?.channelCount ?: 0

    @Synchronized
    fun getInputGainDb(): Float = inputGainDb

    @Synchronized
    fun isHeadroomVerified(): Boolean = headroomVerified

    @Synchronized
    fun isCalibrationActive(): Boolean = calibrationActive

    @Synchronized
    fun wasLastCalibrationApplySuccessful(): Boolean = lastCalibrationApplySucceeded

    @Synchronized
    fun getLastCalibrationApplyError(): String? = lastCalibrationApplyError

    @Synchronized
    fun setCalibrationBands(gains: FloatArray): Boolean {
        if (isAudioStateOverrideActive()) return false
        if (!isValidCalibrationArray(gains)) {
            recordCalibrationFailure("Calibration must contain $INTERNAL_BANDS finite gains within ±${MAX_CALIBRATION_GAIN_DB} dB")
            return false
        }
        if (!headroomVerified && gains.any { it > 0f }) {
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
        profileStore.saveCalibration(calibration)
        return true
    }

    @Synchronized
    fun setCalibrationBandsByChannel(left: FloatArray, right: FloatArray): Boolean {
        if (isAudioStateOverrideActive()) return false
        if (!supportsIndependentCalibration()) return false
        if (!isValidCalibrationArray(left) || !isValidCalibrationArray(right)) {
            recordCalibrationFailure("Each channel must contain $INTERNAL_BANDS finite gains within ±${MAX_CALIBRATION_GAIN_DB} dB")
            return false
        }
        if (!headroomVerified && (left.any { it > 0f } || right.any { it > 0f })) {
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
        profileStore.saveCalibrationChannels(leftCopy, rightCopy)
        return true
    }

    @Synchronized
    fun resetCalibration() {
        if (isAudioStateOverrideActive()) return
        calibration = FloatArray(INTERNAL_BANDS)
        calibrationLeft = null
        calibrationRight = null
        calibrationActive = false
        if (!applyAll(trackCalibrationStatus = true)) {
            Log.w(TAG, "Calibration reset could not be fully verified")
        }
        if (!profileStore.clearCalibration()) {
            Log.w(TAG, "Calibration reset could not clear persisted calibration")
        }
    }

    @Synchronized
    override fun beginMeasurementBypass(): MeasurementAudioState {
        val existing = measurementBypassState ?: calibrationValidationState
        if (existing != null) return existing.copy(
            userBandLevelsMillibels = existing.userBandLevelsMillibels.copyOf(),
            calibrationGainsDb = existing.calibrationGainsDb.copyOf(),
            calibrationLeftGainsDb = existing.calibrationLeftGainsDb?.copyOf(),
            calibrationRightGainsDb = existing.calibrationRightGainsDb?.copyOf(),
        )

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
        dp?.enabled = true
        applyAll()
        return state.copy(
            userBandLevelsMillibels = state.userBandLevelsMillibels.copyOf(),
            calibrationGainsDb = state.calibrationGainsDb.copyOf()
        )
    }

    @Synchronized
    override fun endMeasurementBypass(state: MeasurementAudioState): Boolean {
        val active = measurementBypassState ?: return false
        measurementBypassState = null
        return restoreMeasurementState(active)
    }

    @Synchronized
    override fun beginCalibrationValidation(): MeasurementAudioState {
        val existing = calibrationValidationState ?: measurementBypassState
        if (existing != null) return existing.copy(
            userBandLevelsMillibels = existing.userBandLevelsMillibels.copyOf(),
            calibrationGainsDb = existing.calibrationGainsDb.copyOf(),
            calibrationLeftGainsDb = existing.calibrationLeftGainsDb?.copyOf(),
            calibrationRightGainsDb = existing.calibrationRightGainsDb?.copyOf(),
        )

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
        dp?.enabled = true
        applyAll()
        return state.copy(
            userBandLevelsMillibels = state.userBandLevelsMillibels.copyOf(),
            calibrationGainsDb = state.calibrationGainsDb.copyOf(),
            calibrationLeftGainsDb = state.calibrationLeftGainsDb?.copyOf(),
            calibrationRightGainsDb = state.calibrationRightGainsDb?.copyOf(),
        )
    }

    @Synchronized
    override fun endCalibrationValidation(state: MeasurementAudioState): Boolean {
        val active = calibrationValidationState ?: return false
        calibrationValidationState = null
        return restoreMeasurementState(active)
    }

    // --- internals ---

    @Synchronized
    private fun applyAll(trackCalibrationStatus: Boolean = false): Boolean {
        val d = dp ?: return recordApplyFailure(trackCalibrationStatus, "DynamicsProcessing is not initialized")
        val nCh = d.channelCount
        val requestedInputGain = if (measurementBypassState != null) 0f else requiredInputGainDb()
        if (!setInputGain(d, requestedInputGain)) {
            return recordApplyFailure(trackCalibrationStatus, "Input gain readback did not match the requested value")
        }

        return try {
            val appliedCalibrationByChannel = Array(nCh) { FloatArray(INTERNAL_BANDS) }
            for (ch in 0 until nCh) {
                val channelCalibration = when (ch) {
                    0 -> calibrationLeft ?: calibration
                    1 -> calibrationRight ?: calibration
                    else -> calibration
                }
                for (i in 0 until INTERNAL_BANDS) {
                    val userGain = userGains[userBandForInternal[i]]
                    val effectiveGain = when {
                        measurementBypassState != null -> 0f
                        calibrationValidationState != null -> effectiveCalibrationGain(channelCalibration[i], 0f, headroomVerified)
                        else -> effectiveCalibrationGain(channelCalibration[i], userGain, headroomVerified)
                    }
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
            updateEffectiveCalibration(appliedCalibrationByChannel)
            if (trackCalibrationStatus) {
                lastCalibrationApplySucceeded = true
                lastCalibrationApplyError = null
            }
            true
        } catch (error: Throwable) {
            if (trackCalibrationStatus) {
                lastCalibrationApplySucceeded = false
                lastCalibrationApplyError = error.message ?: error.javaClass.simpleName
            }
            captureEffectiveCalibration(d)
            Log.w(TAG, "DynamicsProcessing calibration application failed", error)
            false
        }
    }

    @Synchronized
    private fun requiredInputGainDb(): Float {
        if (!headroomVerified) return 0f
        var maximum = 0f
        val channelCount = dp?.channelCount ?: 0
        for (ch in 0 until maxOf(1, channelCount)) {
            val channelCalibration = when (ch) {
                0 -> calibrationLeft ?: calibration
                1 -> calibrationRight ?: calibration
                else -> calibration
            }
            for (i in 0 until INTERNAL_BANDS) {
                val userGain = if (calibrationValidationState == null) userGains[userBandForInternal[i]] else 0f
                maximum = max(maximum, channelCalibration[i] + userGain)
            }
        }
        return if (maximum > 0f) -(maximum + 0.5f) else 0f
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
        Log.w(TAG, message)
        return false
    }

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
        dp?.enabled = state.enabled
        return restored
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
        if (channels != null && isValidCalibrationArray(channels.first) && isValidCalibrationArray(channels.second)) {
            calibrationLeft = channels.first.copyOf()
            calibrationRight = channels.second.copyOf()
            calibration = FloatArray(INTERNAL_BANDS) { (channels.first[it] + channels.second[it]) / 2f }
            calibrationActive = true
        } else if (channels != null) {
            profileStore.clearCalibration()
        } else if (cal != null && isValidCalibrationArray(cal)) {
            for (i in 0 until INTERNAL_BANDS) calibration[i] = cal[i]
            calibrationActive = true
        } else if (cal != null) {
            profileStore.clearCalibration()
        }
    }

    private fun save() {
        val levels = IntArray(USER_BANDS) { (userGains[it] * 100).roundToInt() }
        profileStore.save(isEnabled(), activePreset, levels)
    }

    private fun isAudioStateOverrideActive(): Boolean =
        measurementBypassState != null || calibrationValidationState != null
}
