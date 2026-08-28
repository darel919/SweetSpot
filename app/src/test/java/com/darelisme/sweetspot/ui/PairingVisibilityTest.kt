package com.darelisme.sweetspot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingVisibilityTest {
    @Test
    fun onlyWaitingWithANonBlankCodeShowsPairingQr() {
        assertTrue(shouldShowPairingQr("ABCD", OverlayController.CONNECTION_WAITING))
        assertFalse(shouldShowPairingQr(null, OverlayController.CONNECTION_WAITING))
        assertFalse(shouldShowPairingQr("   ", OverlayController.CONNECTION_WAITING))
        assertFalse(shouldShowPairingQr("ABCD", OverlayController.CONNECTION_DISCONNECTED))
        assertFalse(shouldShowPairingQr("ABCD", OverlayController.CONNECTION_CONNECTING))
        assertFalse(shouldShowPairingQr("ABCD", OverlayController.CONNECTION_CONNECTED))
    }
}
