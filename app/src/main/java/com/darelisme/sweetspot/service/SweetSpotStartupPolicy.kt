package com.darelisme.sweetspot.service

internal enum class SweetSpotStartReason {
    USER_LAUNCH,
    BOOT_COMPLETED,
    EXPLICIT_COMMAND,
    STICKY_RESTART,
}

internal data class SweetSpotStartupDecision(
    val shouldStart: Boolean,
    val showOverlay: Boolean,
)

internal const val EXTRA_START_REASON = "com.darelisme.sweetspot.extra.START_REASON"

internal object SweetSpotStartupPolicy {
    fun decide(
        enabled: Boolean,
        reason: SweetSpotStartReason,
        requestedShowOverlay: Boolean = false,
        startOnBoot: Boolean = enabled,
    ): SweetSpotStartupDecision {
        val shouldStart = when (reason) {
            SweetSpotStartReason.USER_LAUNCH,
            SweetSpotStartReason.EXPLICIT_COMMAND -> true
            SweetSpotStartReason.BOOT_COMPLETED,
            SweetSpotStartReason.STICKY_RESTART -> startOnBoot
        }
        return SweetSpotStartupDecision(
            shouldStart = shouldStart,
            showOverlay = reason == SweetSpotStartReason.USER_LAUNCH && requestedShowOverlay,
        )
    }
}
