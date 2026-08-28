package com.darelisme.sweetspot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MeasurementSessionFenceTest {
    @Test
    fun terminalTransitionIsIdempotentAndFencesLateCommands() {
        val fence = MeasurementSessionFence()

        assertTrue(fence.begin("session-1"))
        assertTrue(fence.isActive("session-1"))
        assertTrue(fence.terminate("session-1"))
        assertFalse(fence.isActive("session-1"))
        assertTrue(fence.shouldIgnore("session-1"))
        assertFalse(fence.terminate("session-1"))
    }

    @Test
    fun duplicateBeginDoesNotReplaceTheActiveSession() {
        val fence = MeasurementSessionFence()

        assertTrue(fence.begin("session-1"))
        assertFalse(fence.begin("session-2"))
        assertTrue(fence.isActive("session-1"))
        assertFalse(fence.isActive("session-2"))
    }

    @Test
    fun aNewSessionClearsThePreviousTerminalFence() {
        val fence = MeasurementSessionFence()

        assertTrue(fence.begin("session-1"))
        assertTrue(fence.terminate("session-1"))
        assertTrue(fence.begin("session-2"))
        assertFalse(fence.shouldIgnore("session-2"))
        assertTrue(fence.shouldIgnore("session-1"))
        assertFalse(fence.begin("session-1"))
        assertFalse(fence.isActive("session-1"))
    }
}
