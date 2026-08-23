package com.darelisme.sweetspot

import android.content.Context
import android.content.SharedPreferences

/**
 * Persists EQ profiles on the TV.
 *
 * Two layers:
 *  - "last active": a single auto-saved snapshot (enabled, preset, custom levels)
 *    used to restore state on service start / after a reboot.
 *  - "named profiles": an explicit, user-created collection the UI lists and
 *    lets the user choose from. Each is stored under a name.
 */
class ProfileStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ---- last active (auto-restore) ----

    fun load(): SavedProfile {
        val enabled = prefs.getBoolean(KEY_ENABLED, true)
        val preset = prefs.getInt(KEY_PRESET, 1)
        val levels = prefs.getString(KEY_LEVELS, null)
            ?.split(',')
            ?.mapNotNull { it.toIntOrNull() }
            ?.toIntArray()
        return SavedProfile(enabled, preset, levels)
    }

    fun save(enabled: Boolean, preset: Int, levels: IntArray?) {
        prefs.edit().apply {
            putBoolean(KEY_ENABLED, enabled)
            putInt(KEY_PRESET, preset)
            putString(KEY_LEVELS, levels?.joinToString(","))
            apply()
        }
    }

    // ---- named profiles ----

    fun saveNamed(name: String, enabled: Boolean, preset: Int, levels: IntArray?) {
        val key = profileKey(name)
        prefs.edit().apply {
            putBoolean(key + SUFFIX_ENABLED, enabled)
            putInt(key + SUFFIX_PRESET, preset)
            putString(key + SUFFIX_LEVELS, levels?.joinToString(","))
            val names = listNames().toMutableSet()
            names.add(name)
            putStringSet(KEY_NAMES, names)
            apply()
        }
    }

    fun loadNamed(name: String): SavedProfile? {
        val key = profileKey(name)
        if (!prefs.contains(key + SUFFIX_PRESET)) return null
        val enabled = prefs.getBoolean(key + SUFFIX_ENABLED, true)
        val preset = prefs.getInt(key + SUFFIX_PRESET, 1)
        val levels = prefs.getString(key + SUFFIX_LEVELS, null)
            ?.split(',')
            ?.mapNotNull { it.toIntOrNull() }
            ?.toIntArray()
        return SavedProfile(enabled, preset, levels)
    }

    fun listNames(): List<String> =
        prefs.getStringSet(KEY_NAMES, emptySet())?.toList()?.sorted() ?: emptyList()

    fun deleteNamed(name: String) {
        val key = profileKey(name)
        prefs.edit().apply {
            remove(key + SUFFIX_ENABLED)
            remove(key + SUFFIX_PRESET)
            remove(key + SUFFIX_LEVELS)
            val names = listNames().toMutableList()
            names.remove(name)
            putStringSet(KEY_NAMES, names.toSet())
            apply()
        }
    }

    // ---- calibration (128-band read-only base curve) ----

    fun loadCalibration(): FloatArray? {
        val s = prefs.getString(KEY_CALIBRATION, null)
            ?.split(',')
            ?.mapNotNull { it.toFloatOrNull() }
            ?.toFloatArray()
        return if (s != null && s.size == 64) s else null
    }

    fun saveCalibration(gains: FloatArray) {
        prefs.edit().putString(KEY_CALIBRATION, gains.joinToString(",")).apply()
    }

    private fun profileKey(name: String) = "p_$name"

    companion object {
        private const val PREFS_NAME = "sweetspot"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_PRESET = "preset"
        private const val KEY_LEVELS = "levels"
        private const val KEY_NAMES = "names"
        private const val SUFFIX_ENABLED = "_e"
        private const val SUFFIX_PRESET = "_p"
        private const val SUFFIX_LEVELS = "_l"
        private const val KEY_CALIBRATION = "calibration"
    }
}

data class SavedProfile(
    val enabled: Boolean,
    val preset: Int,
    val levels: IntArray?
)
