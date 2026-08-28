package com.darelisme.sweetspot.transport.webrtc

/** Keeps control messages bounded while the DataChannel drains. */
internal class BoundedControlQueue(
    private val maxMessages: Int,
    private val maxBytes: Int,
    private val maxPriorityMessages: Int,
    private val maxPriorityBytes: Int,
) {
    private val priority = ArrayDeque<ByteArray>()
    private val normal = ArrayDeque<ByteArray>()
    private var priorityBytes = 0
    private var normalBytes = 0

    val isEmpty: Boolean
        get() = priority.isEmpty() && normal.isEmpty()

    fun enqueue(bytes: ByteArray, isPriority: Boolean): Boolean {
        val queue = if (isPriority) priority else normal
        val queueBytes = if (isPriority) priorityBytes else normalBytes
        val queueLimit = if (isPriority) maxPriorityMessages else maxMessages
        val byteLimit = if (isPriority) maxPriorityBytes else maxBytes
        if (bytes.isEmpty() || queue.size >= queueLimit || bytes.size > byteLimit - queueBytes) return false
        queue.addLast(bytes)
        if (isPriority) priorityBytes += bytes.size else normalBytes += bytes.size
        return true
    }

    fun peek(): ByteArray? = priority.firstOrNull() ?: normal.firstOrNull()

    fun removeFirst(): ByteArray? {
        if (priority.isNotEmpty()) {
            return priority.removeFirst().also { priorityBytes -= it.size }
        }
        if (normal.isNotEmpty()) {
            return normal.removeFirst().also { normalBytes -= it.size }
        }
        return null
    }

    fun clear() {
        priority.clear()
        normal.clear()
        priorityBytes = 0
        normalBytes = 0
    }
}
