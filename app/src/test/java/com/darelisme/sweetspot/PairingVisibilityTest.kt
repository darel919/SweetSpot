package com.darelisme.sweetspot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingVisibilityTest {
    @Test
    fun onlyWaitingWithANonBlankCodeShowsPairingQr() {
        assertTrue(shouldShowPairingQr("ABCD", OverlayController.RELAY_WAITING))
        assertFalse(shouldShowPairingQr(null, OverlayController.RELAY_WAITING))
        assertFalse(shouldShowPairingQr("   ", OverlayController.RELAY_WAITING))
        assertFalse(shouldShowPairingQr("ABCD", OverlayController.RELAY_DISCONNECTED))
        assertFalse(shouldShowPairingQr("ABCD", OverlayController.RELAY_CONNECTING))
        assertFalse(shouldShowPairingQr("ABCD", OverlayController.RELAY_CONNECTED))
    }
}
