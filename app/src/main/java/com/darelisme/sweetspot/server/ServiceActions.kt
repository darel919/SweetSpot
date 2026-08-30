package com.darelisme.sweetspot.server

import com.darelisme.sweetspot.audio.diagnostics.AudioEffectDiagnostics
import com.darelisme.sweetspot.audio.diagnostics.DynamicsProcessingProbe

/** Service-owned operations exposed by the local diagnostics/control API. */
interface ServiceActions {
    fun runProbe()
    fun runPersistentProbe(bands: Int)
    fun releasePersistentProbe()
    fun getLastProbeResults(): List<DynamicsProcessingProbe.ProbeResult>?
    fun isProbeRunning(): Boolean
    fun isPersistentProbeActive(): Boolean
    fun getPersistentProbeBands(): Int
    fun applyPersistentCurve(curve: String): Boolean
    fun applyPersistentBands(common: FloatArray, left: FloatArray? = null, right: FloatArray? = null): Boolean
    fun getPersistentProbeCurve(): String?
    fun getPersistentProbeCurveSummary(channel: Int = 0): DynamicsProcessingProbe.CurveSummary?
    fun getPersistentProbeError(): String?

    fun runEffectDiagnostics()
    fun getEffectInventory(): List<AudioEffectDiagnostics.EffectInventoryEntry>
    fun getSessionProbes(): List<AudioEffectDiagnostics.SessionProbe>

    fun getCalibrationBands(): FloatArray?
    fun getRequestedCalibrationBands(): FloatArray?
    fun getEffectiveCalibrationBands(): FloatArray?
    fun getRequestedCalibrationBandsForChannel(channel: Int): FloatArray?
    fun getEffectiveCalibrationBandsForChannel(channel: Int): FloatArray?
    fun getCalibrationFrequenciesHz(): IntArray?
    fun isCalibrationActive(): Boolean
    fun wasLastCalibrationApplySuccessful(): Boolean
    fun getLastCalibrationApplyError(): String?
    fun isCalibrationLiveDspVerified(): Boolean
    fun getCalibrationLiveDspVerificationError(): String?
    fun setCalibrationBands(gains: FloatArray): Boolean
    fun resetCalibration(): Boolean
}
