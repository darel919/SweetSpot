package com.darelisme.sweetspot.transport

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingSignalingPolicyTest {
    @Test
    fun idleServiceDoesNotKeepSignalingOpen() {
        assertFalse(shouldKeepSignaling(serviceRunning = true, pairingVisible = false, directPeer = false, recoveryPending = false))
    }

    @Test
    fun visiblePairingOpensSignaling() {
        assertTrue(shouldKeepSignaling(serviceRunning = true, pairingVisible = true, directPeer = false, recoveryPending = false))
    }

    @Test
    fun directPeerSuspendsSignalingEvenIfPairingUiRemainsVisible() {
        assertFalse(shouldKeepSignaling(serviceRunning = true, pairingVisible = true, directPeer = true, recoveryPending = false))
    }

    @Test
    fun hiddenPairingCanKeepSignalingForAuthenticatedRecovery() {
        assertTrue(shouldKeepSignaling(serviceRunning = true, pairingVisible = false, directPeer = false, recoveryPending = true))
    }
}
