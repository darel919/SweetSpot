package com.darelisme.sweetspot.pairing

import com.darelisme.sweetspot.Config
import java.security.SecureRandom
import java.util.Base64

class PairingSessionManager {

    data class Session(
        val code: String,
        val rendezvousId: String,
        val pairSecret: String,
        val expiresAt: Long,
    )

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
        private const val SECRET_BYTES = 32

        fun normalize(code: String): String = code.replace("-", "").trim().uppercase()

        internal fun connectUrl(session: Session): String =
            "${Config.DASHBOARD_URL}/connect/${normalize(session.code)}?r=${session.rendezvousId}#${session.pairSecret}"

        internal fun rotationDecision(
            clientConnected: Boolean,
            calibrationCritical: Boolean,
            peerSessionActive: Boolean = false,
        ): RotationDecision = if (clientConnected || calibrationCritical || peerSessionActive) {
            RotationDecision.DEFER
        } else {
            RotationDecision.ROTATE_NOW
        }
    }

    private val random = SecureRandom()

    @Volatile
    private var session: Session? = null

    @Volatile
    private var activePeerGeneration: String? = null

    @Synchronized
    fun ensureActive(now: Long = System.currentTimeMillis()): Session {
        val current = session
        if (current != null && (now < current.expiresAt || activePeerGeneration != null)) return current
        return generate(now)
    }

    @Synchronized
    fun current(): String = currentSession().code

    @Synchronized
    fun currentSession(): Session = session ?: throw IllegalStateException("Pairing session is not initialized")

    @Synchronized
    fun rotate(now: Long = System.currentTimeMillis()): Session {
        if (activePeerGeneration != null) return currentSession()
        return generate(now)
    }

    @Synchronized
    fun isExpired(now: Long = System.currentTimeMillis()): Boolean =
        activePeerGeneration == null && (session?.let { now >= it.expiresAt } ?: true)

    @Synchronized
    fun markPeerConnected(generation: String) {
        require(generation.isNotBlank())
        activePeerGeneration = generation
    }

    @Synchronized
    fun markPeerDisconnected(generation: String): Boolean {
        if (activePeerGeneration != generation) return false
        activePeerGeneration = null
        return true
    }

    @Synchronized
    fun hasActivePeer(): Boolean = activePeerGeneration != null

    private fun generate(now: Long): Session {
        val chars = CharArray(LENGTH) { ALPHABET[random.nextInt(ALPHABET.length)] }
        val rendezvousBytes = ByteArray(16).also(random::nextBytes)
        val secretBytes = ByteArray(SECRET_BYTES).also(random::nextBytes)
        return Session(
            code = String(chars),
            rendezvousId = rendezvousBytes.toHex(),
            pairSecret = Base64.getUrlEncoder().withoutPadding().encodeToString(secretBytes),
            expiresAt = now + TTL_MS,
        ).also { session = it }
    }
}

private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
