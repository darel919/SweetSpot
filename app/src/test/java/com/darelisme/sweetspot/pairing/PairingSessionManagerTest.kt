package com.darelisme.sweetspot.pairing

import com.darelisme.sweetspot.pairing.PairingSessionManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingSessionManagerTest {
    @Test
    fun sessionIsStableUntilExpiryThenRotatesWhenUnused() {
        val manager = PairingSessionManager()
        val first = manager.ensureActive(now = 1_000L)
        assertEquals(first, manager.ensureActive(now = first.expiresAt - 1L))
        val second = manager.ensureActive(now = first.expiresAt)
        assertNotEquals(first.code, second.code)
        assertNotEquals(first.rendezvousId, second.rendezvousId)
        assertTrue(second.expiresAt > first.expiresAt)
    }

    @Test
    fun pairCodeNormalizationRemovesSeparatorsWithoutChangingMeaning() {
        assertEquals("ABCD2345", PairingSessionManager.normalize(" ab-cd-2345 "))
    }

    @Test
    fun activePeerKeepsPairingCredentialsValidPastTheirDisplayTtl() {
        val manager = PairingSessionManager()
        val first = manager.ensureActive(now = 1_000L)
        manager.markPeerConnected("generation-1")
        assertEquals(first, manager.ensureActive(now = first.expiresAt + 1L))
        assertTrue(!manager.isExpired(first.expiresAt + 1L))
        assertEquals(first, manager.rotate(now = first.expiresAt + 2L))
        manager.markPeerDisconnected("generation-1")
        assertTrue(manager.isExpired(first.expiresAt + 2L))
    }

    @Test
    fun rotationDefersWhileAClientOrCalibrationCriticalOperationIsActive() {
        assertEquals(
            PairingSessionManager.RotationDecision.ROTATE_NOW,
            PairingSessionManager.rotationDecision(clientConnected = false, calibrationCritical = false),
        )
        assertEquals(
            PairingSessionManager.RotationDecision.DEFER,
            PairingSessionManager.rotationDecision(clientConnected = true, calibrationCritical = false),
        )
        assertEquals(
            PairingSessionManager.RotationDecision.DEFER,
            PairingSessionManager.rotationDecision(clientConnected = false, calibrationCritical = true),
        )
        assertEquals(
            PairingSessionManager.RotationDecision.DEFER,
            PairingSessionManager.rotationDecision(clientConnected = false, calibrationCritical = false, peerSessionActive = true),
        )
    }
}
