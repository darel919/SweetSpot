package com.darelisme.sweetspot.service

import org.junit.Assert.assertEquals
import org.junit.Test

class SweetSpotStartupPolicyTest {

    @Test
    fun enabledBootStartsWithoutOverlay() {
        assertEquals(
            SweetSpotStartupDecision(shouldStart = true, showOverlay = false),
            decide(true, SweetSpotStartReason.BOOT_COMPLETED)
        )
    }

    @Test
    fun disabledBootDoesNotStart() {
        assertEquals(
            SweetSpotStartupDecision(shouldStart = false, showOverlay = false),
            decide(false, SweetSpotStartReason.BOOT_COMPLETED)
        )
    }

    @Test
    fun enabledStickyRestartStartsWithoutOverlay() {
        assertEquals(
            SweetSpotStartupDecision(shouldStart = true, showOverlay = false),
            decide(true, SweetSpotStartReason.STICKY_RESTART)
        )
    }

    @Test
    fun disabledStickyRestartDoesNotStart() {
        assertEquals(
            SweetSpotStartupDecision(shouldStart = false, showOverlay = false),
            decide(false, SweetSpotStartReason.STICKY_RESTART)
        )
    }

    @Test
    fun explicitCommandStartsWhetherEnabledOrDisabled() {
        val expected = SweetSpotStartupDecision(shouldStart = true, showOverlay = false)

        assertEquals(expected, decide(true, SweetSpotStartReason.EXPLICIT_COMMAND))
        assertEquals(expected, decide(false, SweetSpotStartReason.EXPLICIT_COMMAND))
    }

    @Test
    fun userLaunchShowsOverlayOnlyWhenRequested() {
        assertEquals(
            SweetSpotStartupDecision(shouldStart = true, showOverlay = true),
            decide(false, SweetSpotStartReason.USER_LAUNCH, requestedShowOverlay = true)
        )
        assertEquals(
            SweetSpotStartupDecision(shouldStart = true, showOverlay = false),
            decide(true, SweetSpotStartReason.USER_LAUNCH, requestedShowOverlay = false)
        )
    }

    @Test
    fun automaticStartsIgnoreOverlayRequest() {
        val expected = SweetSpotStartupDecision(shouldStart = true, showOverlay = false)

        assertEquals(
            expected,
            decide(true, SweetSpotStartReason.BOOT_COMPLETED, requestedShowOverlay = true)
        )
        assertEquals(
            expected,
            decide(true, SweetSpotStartReason.STICKY_RESTART, requestedShowOverlay = true)
        )
    }

    private fun decide(
        enabled: Boolean,
        reason: SweetSpotStartReason,
        requestedShowOverlay: Boolean = false,
    ): SweetSpotStartupDecision = SweetSpotStartupPolicy.decide(
        enabled = enabled,
        reason = reason,
        requestedShowOverlay = requestedShowOverlay,
    )
}
