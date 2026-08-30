package com.darelisme.sweetspot.calibration.model

import org.junit.Assert.assertEquals
import org.junit.Test

class CalibrationResultTest {
    @Test
    fun finalResultTextMatchesTheCalibrationOutcome() {
        assertEquals(
            CalibrationResultText("Calibration complete — improved", "The new calibration is active."),
            calibrationResultText("improved"),
        )
        assertEquals(
            CalibrationResultText(
                "Calibration inconclusive",
                "The candidate could not be proven better. It was removed, and calibration remains off. Your pre-calibration audio settings are unchanged.",
            ),
            calibrationResultText("inconclusive", rollbackTargetActive = false),
        )
        assertEquals(
            CalibrationResultText(
                "Calibration rejected",
                "The candidate did not improve the measured result. It was removed, and calibration remains off. Your pre-calibration audio settings are unchanged.",
            ),
            calibrationResultText("worse", rollbackTargetActive = false),
        )
        assertEquals(
            CalibrationResultText("Calibration cancelled", "It was removed, and calibration remains off. Your pre-calibration audio settings are unchanged."),
            calibrationResultText("cancelled", rollbackTargetActive = false),
        )
        assertEquals(
            CalibrationResultText(
                "Calibration could not be validated",
                "It was removed, and calibration remains off. Your pre-calibration audio settings are unchanged.\nReason: signal_too_low",
            ),
            calibrationResultText("error", "signal_too_low", rollbackTargetActive = false),
        )
        assertEquals(
            CalibrationResultText(
                "Calibration rejected",
                "The candidate did not improve the measured result. The previously active calibration was restored.",
            ),
            calibrationResultText("worse", rollbackTargetActive = true),
        )
    }
}
