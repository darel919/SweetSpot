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
        private const val SESSION_ID = 0
        private const val PRIORITY = 1000
        const val F_MIN = 20
        const val F_MAX = 20000
        private const val TAG = "DynamicsProcessingEq"
        private const val PRESET_FLAT = 1
        private const val PRESET_NIGHT = 2
        private const val NIGHT_CUT_DB = -6f
        private const val NIGHT_CUT_BANDS = 3
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
    private var calibrationActive = false
    private var inputGainDb = 0f
    private var headroomVerified = false
    private var measurementBypassState: MeasurementAudioState? = null

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
        applyAll()
        Log.i(TAG, "Engine initialized: internalBands=$INTERNAL_BANDS userBands=$USER_BANDS channels=${d.channelCount}")
    }

    @Synchronized
    override fun release() {
        measurementBypassState?.let { state ->
            measurementBypassState = null
            restoreMeasurementState(state)
        }
        try { dp?.release() } catch (_: Exception) {}
        dp = null
        Log.i(TAG, "Engine released")
    }

    @Synchronized
    override fun setEnabled(enabled: Boolean) {
        if (measurementBypassState != null) return
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
        if (measurementBypassState != null) return
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
        if (measurementBypassState != null) return
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
    fun getCalibrationBands(): FloatArray = calibration.copyOf()

    @Synchronized
    fun getCalibrationFrequenciesHz(): IntArray =
        IntArray(INTERNAL_BANDS) { internalFreqs[it].roundToInt() }

    @Synchronized
    fun getCalibrationBandsForChannel(channel: Int): FloatArray? = when (channel) {
        0 -> calibrationLeft?.copyOf()
        1 -> calibrationRight?.copyOf()
        else -> null
    }

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
    fun setCalibrationBands(gains: FloatArray): Boolean {
        if (measurementBypassState != null) return false
        if (gains.size != INTERNAL_BANDS) return false
        if (gains.any { !it.isFinite() }) return false
        for (i in 0 until INTERNAL_BANDS) calibration[i] = gains[i]
        calibrationLeft = null
        calibrationRight = null
        calibrationActive = true
        applyAll()
        profileStore.saveCalibration(calibration)
        return true
    }

    @Synchronized
    fun setCalibrationBandsByChannel(left: FloatArray, right: FloatArray): Boolean {
        if (measurementBypassState != null) return false
        if (!supportsIndependentCalibration()) return false
        if (left.size != INTERNAL_BANDS || right.size != INTERNAL_BANDS) return false
        if (left.any { !it.isFinite() } || right.any { !it.isFinite() }) return false
        calibrationLeft = left.copyOf()
        calibrationRight = right.copyOf()
        calibration = FloatArray(INTERNAL_BANDS) { (left[it] + right[it]) / 2f }
        calibrationActive = true
        applyAll()
        profileStore.saveCalibrationChannels(left, right)
        return true
    }

    @Synchronized
    fun resetCalibration() {
        if (measurementBypassState != null) return
        calibration = FloatArray(INTERNAL_BANDS)
        calibrationLeft = null
        calibrationRight = null
        calibrationActive = false
        applyAll()
        profileStore.saveCalibration(calibration)
    }

    @Synchronized
    override fun beginMeasurementBypass(): MeasurementAudioState {
        val existing = measurementBypassState
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
    override fun endMeasurementBypass(state: MeasurementAudioState) {
        val active = measurementBypassState ?: return
        measurementBypassState = null
        restoreMeasurementState(active)
    }

    // --- internals ---

    @Synchronized
    private fun applyAll() {
        val d = dp ?: return
        val nCh = d.channelCount
        if (measurementBypassState != null) {
            setInputGain(d, 0f)
        } else {
            setInputGain(d, requiredInputGainDb())
        }
        for (ch in 0 until nCh) {
            val channelCalibration = when (ch) {
                0 -> calibrationLeft ?: calibration
                1 -> calibrationRight ?: calibration
                else -> calibration
            }
            for (i in 0 until INTERNAL_BANDS) {
                val eff = if (measurementBypassState != null) {
                    0f
                } else {
                    val requested = channelCalibration[i] + userGains[userBandForInternal[i]]
                    if (headroomVerified) requested else min(0f, requested)
                }
                val band = d.getPreEqBandByChannelIndex(ch, i)
                band.setGain(eff)
                d.setPreEqBandByChannelIndex(ch, i, band)
            }
        }
    }

    @Synchronized
    private fun requiredInputGainDb(): Float {
        var maximum = 0f
        val channelCount = dp?.channelCount ?: 0
        for (ch in 0 until maxOf(1, channelCount)) {
            val channelCalibration = when (ch) {
                0 -> calibrationLeft ?: calibration
                1 -> calibrationRight ?: calibration
                else -> calibration
            }
            for (i in 0 until INTERNAL_BANDS) {
                maximum = max(maximum, channelCalibration[i] + userGains[userBandForInternal[i]])
            }
        }
        return if (maximum > 0f) -(maximum + 0.5f) else 0f
    }

    private fun setInputGain(d: DynamicsProcessing, requestedDb: Float) {
        val bounded = requestedDb.coerceIn(-60f, 0f)
        try {
            d.setInputGainAllChannelsTo(bounded)
            val count = d.channelCount
            val verified = count > 0 && (0 until count).all { channel ->
                abs(d.getInputGainByChannelIndex(channel) - bounded) <= 0.25f
            }
            inputGainDb = if (verified) bounded else 0f
            headroomVerified = verified
        } catch (error: Throwable) {
            inputGainDb = 0f
            headroomVerified = false
            Log.w(TAG, "Input headroom gain is not controllable on this device", error)
        }
    }

    private fun restoreMeasurementState(state: MeasurementAudioState) {
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
        applyAll()
        // The input gain is derived from the restored layers. Keep the saved
        // diagnostic value as well so a bypass round-trip is exact.
        inputGainDb = state.inputGainDb
        headroomVerified = state.headroomVerified
        dp?.enabled = state.enabled
    }

    private fun restore() {
        val saved = profileStore.load()
        if (saved.levels != null && saved.levels.size == USER_BANDS) {
            for (i in saved.levels.indices) userGains[i] = saved.levels[i] / 100f
            activePreset = 0
        } else {
            applyPreset(saved.preset)
        }
        enabled = saved.enabled
        dp?.enabled = saved.enabled
        val cal = profileStore.loadCalibration()
        val channels = profileStore.loadCalibrationChannels()
        if (channels != null && channels.first.size == INTERNAL_BANDS && channels.second.size == INTERNAL_BANDS) {
            calibrationLeft = channels.first.copyOf()
            calibrationRight = channels.second.copyOf()
            calibration = FloatArray(INTERNAL_BANDS) { (channels.first[it] + channels.second[it]) / 2f }
            calibrationActive = true
        } else if (cal != null && cal.size == INTERNAL_BANDS) {
            for (i in 0 until INTERNAL_BANDS) calibration[i] = cal[i]
            calibrationActive = true
        }
    }

    private fun save() {
        val levels = IntArray(USER_BANDS) { (userGains[it] * 100).roundToInt() }
        profileStore.save(isEnabled(), activePreset, levels)
    }
}
