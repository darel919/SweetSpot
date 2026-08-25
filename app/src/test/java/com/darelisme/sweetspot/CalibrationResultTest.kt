package com.darelisme.sweetspot

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
                "The result could not be proven better. Previous settings were restored.",
            ),
            calibrationResultText("inconclusive"),
        )
        assertEquals(
            CalibrationResultText(
                "Calibration rejected",
                "The candidate measured worse. Previous settings were restored.",
            ),
            calibrationResultText("worse"),
        )
        assertEquals(
            CalibrationResultText("Calibration cancelled", "Previous settings were restored."),
            calibrationResultText("cancelled"),
        )
        assertEquals(
            CalibrationResultText(
                "Calibration could not be validated",
                "Previous settings were restored.\nReason: signal_too_low",
            ),
            calibrationResultText("error", "signal_too_low"),
        )
    }
}
