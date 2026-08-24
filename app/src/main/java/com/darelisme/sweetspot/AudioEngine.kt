package com.darelisme.sweetspot

/**
 * Audio abstraction owned by the service. The web server and command layer talk
 * to this interface only — they never touch [android.media.audiofx.Equalizer]
 * directly. This keeps the HTTP/UI code independent of the concrete DSP and
 * makes future effects (DynamicsProcessing, etc.) swappable.
 */
interface AudioEngine {
    fun initialize()
    fun release()
    fun setEnabled(enabled: Boolean): Boolean
    fun isEnabled(): Boolean
    fun hasControl(): Boolean
    fun setBandLevel(index: Int, millibels: Int): Boolean
    fun getBandLevels(): IntArray
    fun applyPreset(preset: Int): Boolean
    fun getActivePreset(): Int
    fun getCapabilities(): EngineCapabilities

    /** Persist the current state (enabled, preset, custom levels) under a name. */
    fun saveCurrentProfile(name: String)
    /** Names of all saved profiles. */
    fun listProfiles(): List<String>
    /** Load a named profile and apply it to the engine. */
    fun loadProfile(name: String): Boolean
    /** Delete a named profile. */
    fun deleteProfile(name: String)

    fun beginMeasurementBypass(): MeasurementAudioOverrideResult
    fun endMeasurementBypass(state: MeasurementAudioState): Boolean

    /** Temporarily flattens user EQ while keeping the calibration layer active. */
    fun beginCalibrationValidation(candidateId: String? = null): MeasurementAudioOverrideResult
    fun endCalibrationValidation(state: MeasurementAudioState): Boolean
}

sealed interface MeasurementAudioOverrideResult {
    data class Applied(val previousState: MeasurementAudioState) : MeasurementAudioOverrideResult
    data class Failed(val error: String, val restored: Boolean) : MeasurementAudioOverrideResult
}

data class MeasurementAudioState(
    val enabled: Boolean,
    val activePreset: Int,
    val userBandLevelsMillibels: IntArray,
    val calibrationGainsDb: FloatArray,
    val calibrationActive: Boolean,
    val calibrationLeftGainsDb: FloatArray? = null,
    val calibrationRightGainsDb: FloatArray? = null,
    val inputGainDb: Float = 0f,
    val headroomVerified: Boolean = false,
)

data class EngineCapabilities(
    val bandCount: Int,
    val bandLevelRange: IntArray,
    val centerFrequenciesHz: IntArray,
    val presets: Map<Int, String>
) {
    /** [IntArray] uses reference equality, so capability comparisons need value equality. */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as EngineCapabilities
        return bandCount == other.bandCount &&
            bandLevelRange.contentEquals(other.bandLevelRange) &&
            centerFrequenciesHz.contentEquals(other.centerFrequenciesHz) &&
            presets == other.presets
    }

    override fun hashCode(): Int {
        var result = bandCount
        result = 31 * result + bandLevelRange.contentHashCode()
        result = 31 * result + centerFrequenciesHz.contentHashCode()
        result = 31 * result + presets.hashCode()
        return result
    }
}
