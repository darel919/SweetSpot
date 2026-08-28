package com.darelisme.sweetspot.calibration.model

internal data class ValidationRecoveryResult(
    val validationStateRestored: Boolean,
    val candidateRolledBack: Boolean,
    val finalStateVerified: Boolean,
)

/**
 * Owns the one-shot recovery sequence for a validation session.
 *
 * The callbacks are deliberately ordered here rather than at each caller:
 * restoring the temporary validation override must finish before the staged
 * candidate is rolled back. The cached result makes repeated cancellation,
 * lifecycle, or watchdog signals harmless.
 */
internal class ValidationRecoveryGate {
    private var cachedResult: ValidationRecoveryResult? = null

    fun recover(
        candidateId: String?,
        restoreValidationState: () -> Boolean,
        rollbackCandidate: (String) -> Boolean,
        verifyFinalState: () -> Boolean,
    ): ValidationRecoveryResult {
        cachedResult?.let { return it }

        val restored = try {
            restoreValidationState()
        } catch (_: Throwable) {
            false
        }
        val rolledBack = try {
            candidateId?.let(rollbackCandidate) == true
        } catch (_: Throwable) {
            false
        }
        val verified = if (restored && rolledBack) {
            try {
                verifyFinalState()
            } catch (_: Throwable) {
                false
            }
        } else {
            false
        }
        return ValidationRecoveryResult(restored, rolledBack, verified).also { cachedResult = it }
    }
}

internal data class CalibrationResultText(
    val title: String,
    val body: String,
)

internal fun calibrationResultText(
    outcome: String,
    reason: String? = null,
    rollbackTargetActive: Boolean? = null,
): CalibrationResultText {
    val rollbackSummary = when (rollbackTargetActive) {
        true -> "The previously active calibration was restored."
        false -> "It was removed, and calibration remains off. Your pre-calibration audio settings are unchanged."
        null -> "The original pre-candidate audio state was kept."
    }
    val result = when (outcome) {
        "improved" -> CalibrationResultText(
            title = "Calibration complete — improved",
            body = "The new calibration is active.",
        )
        "inconclusive" -> CalibrationResultText(
            title = "Calibration inconclusive",
            body = "The candidate could not be proven better. $rollbackSummary",
        )
        "worse" -> CalibrationResultText(
            title = "Calibration rejected",
            body = "The candidate did not improve the measured result. $rollbackSummary",
        )
        "cancelled" -> CalibrationResultText(
            title = "Calibration cancelled",
            body = rollbackSummary,
        )
        else -> CalibrationResultText(
            title = "Calibration could not be validated",
            body = rollbackSummary,
        )
    }
    val conciseReason = reason?.trim()?.takeIf { it.isNotEmpty() }
    return if (outcome == "error" && conciseReason != null) {
        result.copy(body = "${result.body}\nReason: $conciseReason")
    } else {
        result
    }
}
