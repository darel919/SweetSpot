package com.darelisme.sweetspot.calibration

object CalibrationValidationFallbackPolicy {
    fun nextModeAfterWorse(mode: CorrectionMode): CorrectionMode? = when (mode) {
        CorrectionMode.NORMAL -> CorrectionMode.GENTLE
        CorrectionMode.GENTLE -> CorrectionMode.RESTRICTED_BAND
        CorrectionMode.RESTRICTED_BAND -> null
    }
}
