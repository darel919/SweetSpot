package com.darelisme.sweetspot

import android.media.audiofx.DynamicsProcessing
import android.util.Log
import kotlin.math.*

/**
 * Production audio engine: a single [DynamicsProcessing] on session 0 with
 * [INTERNAL_BANDS] pre-EQ bands that combines two layers:
 *
 *  - calibration: a read-only 128-band base curve (dB) set ONLY by the calibrate
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
        const val INTERNAL_BANDS = 128
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

    private val internalFreqs = FloatArray(INTERNAL_BANDS) { i ->
        (F_MIN * (F_MAX.toDouble() / F_MIN).pow(i.toDouble() / (INTERNAL_BANDS - 1))).toFloat()
    }
    private val userFreqs = FloatArray(USER_BANDS) { i ->
        (F_MIN * (F_MAX.toDouble() / F_MIN).pow(i.toDouble() / (USER_BANDS - 1))).toFloat()
    }
    private val userBandForInternal = IntArray(INTERNAL_BANDS) { i ->
        var best = 0
        var bestD = Float.MAX_VALUE
        for (u in 0 until USER_BANDS) {
            val d = abs(internalFreqs[i] - userFreqs[u])
            if (d < bestD) { bestD = d; best = u }
        }
        best
    }

    @Volatile private var dp: DynamicsProcessing? = null
    @Volatile private var enabled = true
    @Volatile private var activePreset = PRESET_FLAT
    private var userGains = FloatArray(USER_BANDS)        // dB
    private var calibration = FloatArray(INTERNAL_BANDS)  // dB, read-only base
    private var calibrationActive = false

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
        try { dp?.release() } catch (_: Exception) {}
        dp = null
        Log.i(TAG, "Engine released")
    }

    @Synchronized
    override fun setEnabled(enabled: Boolean) {
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
        val levels = IntArray(USER_BANDS) { (userGains[it] * 100).roundToInt() }
        profileStore.saveNamed(name, isEnabled(), activePreset, levels)
        save()
    }

    @Synchronized
    override fun listProfiles(): List<String> = profileStore.listNames()

    @Synchronized
    override fun loadProfile(name: String) {
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
    fun isCalibrationActive(): Boolean = calibrationActive

    @Synchronized
    fun setCalibrationBands(gains: FloatArray): Boolean {
        if (gains.size != INTERNAL_BANDS) return false
        for (i in 0 until INTERNAL_BANDS) calibration[i] = gains[i]
        calibrationActive = true
        applyAll()
        profileStore.saveCalibration(calibration)
        return true
    }

    @Synchronized
    fun resetCalibration() {
        calibration = FloatArray(INTERNAL_BANDS)
        calibrationActive = false
        applyAll()
        profileStore.saveCalibration(calibration)
    }

    // --- internals ---

    @Synchronized
    private fun applyAll() {
        val d = dp ?: return
        val nCh = d.channelCount
        for (ch in 0 until nCh) {
            for (i in 0 until INTERNAL_BANDS) {
                val eff = calibration[i] + userGains[userBandForInternal[i]]
                val band = d.getPreEqBandByChannelIndex(ch, i)
                band.setGain(eff)
                d.setPreEqBandByChannelIndex(ch, i, band)
            }
        }
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
        if (cal != null && cal.size == INTERNAL_BANDS) {
            for (i in 0 until INTERNAL_BANDS) calibration[i] = cal[i]
            calibrationActive = true
        }
    }

    private fun save() {
        val levels = IntArray(USER_BANDS) { (userGains[it] * 100).roundToInt() }
        profileStore.save(isEnabled(), activePreset, levels)
    }
}
