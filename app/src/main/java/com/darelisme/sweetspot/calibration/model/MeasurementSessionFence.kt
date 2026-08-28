package com.darelisme.sweetspot.calibration.model

import java.util.LinkedHashSet

internal class MeasurementSessionFence {
    companion object {
        private const val MAX_TERMINAL_SESSIONS = 32
    }

    private var activeSessionId: String? = null
    private val terminalSessionIds = LinkedHashSet<String>()

    fun begin(sessionId: String): Boolean {
        if (activeSessionId != null || terminalSessionIds.contains(sessionId)) return false
        activeSessionId = sessionId
        return true
    }

    fun isActive(sessionId: String): Boolean = activeSessionId == sessionId

    fun terminate(sessionId: String): Boolean {
        if (activeSessionId != sessionId) return false
        activeSessionId = null
        terminalSessionIds.add(sessionId)
        while (terminalSessionIds.size > MAX_TERMINAL_SESSIONS) {
            terminalSessionIds.remove(terminalSessionIds.first())
        }
        return true
    }

    fun shouldIgnore(sessionId: String): Boolean = terminalSessionIds.contains(sessionId)
}
