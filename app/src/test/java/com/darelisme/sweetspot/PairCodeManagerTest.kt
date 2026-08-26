package com.darelisme.sweetspot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PairCodeManagerTest {
    @Test
    fun sessionIsStableUntilExpiryThenRotates() {
        val manager = PairCodeManager()
        val first = manager.ensureActive(now = 1_000L)
        assertEquals(first, manager.ensureActive(now = first.expiresAt - 1L))
        val second = manager.ensureActive(now = first.expiresAt)
        assertNotEquals(first.code, second.code)
        assertTrue(second.expiresAt > first.expiresAt)
    }

    @Test
    fun pairCodeNormalizationRemovesSeparatorsWithoutChangingMeaning() {
        assertEquals("ABCD2345", PairCodeManager.normalize(" ab-cd-2345 "))
    }
}
