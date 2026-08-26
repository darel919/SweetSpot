package com.darelisme.sweetspot

import java.security.SecureRandom

class PairCodeManager {

    data class Session(val code: String, val expiresAt: Long)
    data class RotationResult(val session: Session, val rotated: Boolean)
    enum class RotationDecision {
        ROTATE_NOW,
        DEFER,
    }

    companion object {
        private const val ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
        const val LENGTH = 8
        const val TTL_MS = 10L * 60 * 1000
        const val ROTATION_MARGIN_MS = 30L * 1000

        fun normalize(code: String): String = code.replace("-", "").trim().uppercase()

        internal fun connectUrl(code: String): String =
            "${Config.DASHBOARD_URL}/connect/${normalize(code)}"

        internal fun rotationDecision(
            clientConnected: Boolean,
            calibrationCritical: Boolean,
        ): RotationDecision = if (clientConnected || calibrationCritical) {
            RotationDecision.DEFER
        } else {
            RotationDecision.ROTATE_NOW
        }
    }

    private val random = SecureRandom()

    @Volatile
    private var session: Session? = null

    @Synchronized
    fun ensureActive(now: Long = System.currentTimeMillis()): Session {
        val current = session
        if (current != null && now < current.expiresAt) return current
        return generate(now)
    }

    @Synchronized
    fun current(): String = session?.code ?: throw IllegalStateException("Pairing session is not initialized")

    @Synchronized
    fun currentSession(): Session = session ?: throw IllegalStateException("Pairing session is not initialized")

    @Synchronized
    fun rotate(now: Long = System.currentTimeMillis()): Session = generate(now)

    @Synchronized
    fun isExpired(now: Long = System.currentTimeMillis()): Boolean =
        session?.let { now >= it.expiresAt } ?: true

    private fun generate(now: Long): Session {
        val chars = CharArray(LENGTH) { ALPHABET[random.nextInt(ALPHABET.length)] }
        return Session(String(chars), now + TTL_MS).also { session = it }
    }
}
