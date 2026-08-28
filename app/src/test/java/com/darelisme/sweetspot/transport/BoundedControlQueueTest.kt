package com.darelisme.sweetspot.transport

import com.darelisme.sweetspot.transport.webrtc.BoundedControlQueue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundedControlQueueTest {
    @Test
    fun priorityMessagesDrainBeforeNormalMessagesWithoutExceedingLimits() {
        val queue = BoundedControlQueue(
            maxMessages = 2,
            maxBytes = 8,
            maxPriorityMessages = 1,
            maxPriorityBytes = 4,
        )
        val normal = byteArrayOf(1, 2, 3, 4)
        val priority = byteArrayOf(9, 8)

        assertTrue(queue.enqueue(normal, isPriority = false))
        assertTrue(queue.enqueue(priority, isPriority = true))
        assertFalse(queue.enqueue(byteArrayOf(7, 6, 5), isPriority = true))
        assertEquals(priority.toList(), queue.peek()?.toList())
        assertEquals(priority.toList(), queue.removeFirst()?.toList())
        assertEquals(normal.toList(), queue.removeFirst()?.toList())
        assertNull(queue.removeFirst())
    }

    @Test
    fun normalAndPriorityByteBudgetsAreIndependentAndClearIsIdempotent() {
        val queue = BoundedControlQueue(
            maxMessages = 2,
            maxBytes = 4,
            maxPriorityMessages = 2,
            maxPriorityBytes = 4,
        )

        assertTrue(queue.enqueue(byteArrayOf(1, 2, 3, 4), isPriority = false))
        assertTrue(queue.enqueue(byteArrayOf(5, 6, 7, 8), isPriority = true))
        assertFalse(queue.enqueue(byteArrayOf(9), isPriority = false))
        assertFalse(queue.enqueue(byteArrayOf(9), isPriority = true))
        queue.clear()
        queue.clear()
        assertTrue(queue.isEmpty)
    }
}
