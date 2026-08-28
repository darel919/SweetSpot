package com.darelisme.sweetspot.audio.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioOperationGateTest {
    @Test
    fun persistentLeaseBlocksTransientOperationsUntilReleased() {
        val gate = AudioOperationGate()

        assertTrue(gate.tryAcquireTransient())
        assertTrue(gate.promoteToPersistent())
        assertFalse(gate.tryAcquireTransient())
        assertTrue(gate.isPersistentHeld())

        gate.releasePersistent()

        assertTrue(gate.tryAcquireTransient())
    }

    @Test
    fun transientLeaseCanBeReleasedWithoutReleasingAnotherOwner() {
        val gate = AudioOperationGate()

        assertTrue(gate.tryAcquireTransient())
        gate.releaseTransient()
        gate.releasePersistent()

        assertFalse(gate.isHeld())
        assertTrue(gate.tryAcquireTransient())
        assertTrue(gate.promoteToPersistent())
    }
}
