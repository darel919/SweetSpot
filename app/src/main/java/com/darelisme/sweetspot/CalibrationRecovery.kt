package com.darelisme.sweetspot

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

internal fun calibrationResultText(outcome: String, reason: String? = null): CalibrationResultText {
    val result = when (outcome) {
        "improved" -> CalibrationResultText(
            title = "Calibration complete — improved",
            body = "The new calibration is active.",
        )
        "inconclusive" -> CalibrationResultText(
            title = "Calibration inconclusive",
            body = "The result could not be proven better. Previous settings were restored.",
        )
        "worse" -> CalibrationResultText(
            title = "Calibration rejected",
            body = "The candidate measured worse. Previous settings were restored.",
        )
        "cancelled" -> CalibrationResultText(
            title = "Calibration cancelled",
            body = "Previous settings were restored.",
        )
        else -> CalibrationResultText(
            title = "Calibration could not be validated",
            body = "Previous settings were restored.",
        )
    }
    val conciseReason = reason?.trim()?.takeIf { it.isNotEmpty() }
    return if (outcome == "error" && conciseReason != null) {
        result.copy(body = "${result.body}\nReason: $conciseReason")
    } else {
        result
    }
}
