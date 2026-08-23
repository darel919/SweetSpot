package com.darelisme.sweetspot

import android.media.audiofx.Equalizer
import android.util.Log
import kotlin.math.abs

/**
 * Real audio engine backed by the global Android [Equalizer] (session 0).
 *
 * Persistence: on [initialize] it restores the last saved profile from
 * [ProfileStore]; every mutation (enable/bypass, preset, band level) is saved
 * so the profile survives process death and device reboots.
 */
class EqualizerEngine(private val profileStore: ProfileStore) : AudioEngine {

    companion object {
        private const val TAG = "EqualizerEngine"
        private const val SESSION_ID = 0
        private const val PRESET_FLAT = 1
        private const val PRESET_NIGHT = 2
        private const val NIGHT_TARGET_HZ = 60
        private const val NIGHT_LEVEL_MILLIBELS = -1500
    }

    private var eq: Equalizer? = null
    @Volatile private var activePreset: Int = PRESET_FLAT

    @Synchronized
    override fun initialize() {
        if (eq != null) return
        val e = Equalizer(1000, SESSION_ID)
        e.enabled = true
        eq = e
        Log.i(TAG, "Equalizer initialized: bands=${e.numberOfBands}, " +
                "range=${e.bandLevelRange.contentToString()}")
        restore()
    }

    @Synchronized
    override fun release() {
        eq?.let {
            try { it.release() } catch (_: Exception) {}
        }
        eq = null
        Log.i(TAG, "Equalizer released")
    }

    @Synchronized
    override fun setEnabled(enabled: Boolean) {
        eq?.enabled = enabled
        save()
    }

    @Synchronized
    override fun isEnabled(): Boolean = eq?.enabled ?: false

    @Synchronized
    override fun hasControl(): Boolean = eq?.hasControl() ?: false

    @Synchronized
    override fun setBandLevel(index: Int, millibels: Int) {
        eq?.setBandLevel(index.toShort(), millibels.toShort())
        activePreset = 0
        save()
    }

    @Synchronized
    override fun getBandLevels(): IntArray {
        val e = eq ?: return intArrayOf()
        val n = e.numberOfBands.toInt()
        return IntArray(n) { i -> e.getBandLevel(i.toShort()).toInt() }
    }

    @Synchronized
    override fun applyPreset(preset: Int) {
        val e = eq ?: return
        val levels = when (preset) {
            PRESET_FLAT -> IntArray(e.numberOfBands.toInt()) { 0 }
            PRESET_NIGHT -> nightLevels(e)
            else -> return
        }
        for (i in levels.indices) {
            e.setBandLevel(i.toShort(), levels[i].toShort())
        }
        activePreset = preset
        save()
    }

    @Synchronized
    override fun getActivePreset(): Int = activePreset

    @Synchronized
    override fun saveCurrentProfile(name: String) {
        val levels = if (activePreset == 0) getBandLevels() else null
        profileStore.saveNamed(name, isEnabled(), activePreset, levels)
        save()
    }

    @Synchronized
    override fun listProfiles(): List<String> = profileStore.listNames()

    @Synchronized
    override fun loadProfile(name: String) {
        val p = profileStore.loadNamed(name) ?: return
        val e = eq ?: return
        if (p.levels != null && p.levels.size == e.numberOfBands.toInt()) {
            for (i in p.levels.indices) e.setBandLevel(i.toShort(), p.levels[i].toShort())
            activePreset = 0
        } else {
            applyPreset(p.preset)
        }
        e.enabled = p.enabled
        save()
    }

    @Synchronized
    override fun deleteProfile(name: String) {
        profileStore.deleteNamed(name)
    }

    @Synchronized
    override fun getCapabilities(): EngineCapabilities {
        val e = eq
        if (e == null) {
            return EngineCapabilities(
                bandCount = 0,
                bandLevelRange = intArrayOf(-1500, 1500),
                centerFrequenciesHz = intArrayOf(),
                presets = mapOf(1 to "Flat", 2 to "Night")
            )
        }
        val n = e.numberOfBands.toInt()
        val range = e.bandLevelRange
        // getCenterFreq() returns millihertz — convert to Hz for display.
        val centers = IntArray(n) { i -> e.getCenterFreq(i.toShort()) / 1000 }
        return EngineCapabilities(
            bandCount = n,
            bandLevelRange = intArrayOf(range[0].toInt(), range[1].toInt()),
            centerFrequenciesHz = centers,
            presets = mapOf(1 to "Flat", 2 to "Night")
        )
    }

    private fun nightLevels(e: Equalizer): IntArray {
        val n = e.numberOfBands.toInt()
        val centersHz = IntArray(n) { i -> e.getCenterFreq(i.toShort()) / 1000 }
        // Apply the cut to the band whose center frequency is closest to 60 Hz.
        var target = 0
        var best = Int.MAX_VALUE
        for (i in 0 until n) {
            val d = abs(centersHz[i] - NIGHT_TARGET_HZ)
            if (d < best) { best = d; target = i }
        }
        return IntArray(n) { i -> if (i == target) NIGHT_LEVEL_MILLIBELS else 0 }
    }

    private fun restore() {
        val saved = profileStore.load()
        val e = eq ?: return
        if (saved.levels != null && saved.levels.size == e.numberOfBands.toInt()) {
            for (i in saved.levels.indices) {
                e.setBandLevel(i.toShort(), saved.levels[i].toShort())
            }
            activePreset = 0
        } else {
            applyPreset(saved.preset)
        }
        e.enabled = saved.enabled
    }

    private fun save() {
        val levels = if (activePreset == 0) getBandLevels() else null
        profileStore.save(isEnabled(), activePreset, levels)
    }
}
