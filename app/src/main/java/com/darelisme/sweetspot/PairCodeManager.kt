package com.darelisme.sweetspot

import java.security.SecureRandom

/**
 * Short-lived pairing code shown on the TV and embedded in the hosted
 * dashboard URL. Format: 8 characters from an unambiguous uppercase alphabet,
 * displayed as XXXX-XXXX.
 *
 * A code expires after [TTL_MS] and can be regenerated on demand. The relay
 * treats the code only as a room key; it grants no permanent control.
 */
class PairCodeManager {

    companion object {
        // No 0/O or 1/I/L to keep the code readable from a TV across a room.
        private const val ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
        const val LENGTH = 8
        const val TTL_MS = 10L * 60 * 1000

        /** Strips dashes, uppercases: the exact normalization the web app applies. */
        fun normalize(code: String): String = code.replace("-", "").trim().uppercase()
    }

    private val random = SecureRandom()

    @Volatile
    private var current: String? = null

    @Volatile
    private var expiresAt: Long = 0

    /** Returns the active code, regenerating it if missing or expired. */
    @Synchronized
    fun current(): String {
        val now = System.currentTimeMillis()
        val c = current
        if (c != null && now < expiresAt) return c
        return generate(now)
    }

    /** Forces a fresh code (e.g. user requests a new one from the TV UI). */
    @Synchronized
    fun rotate(): String = generate(System.currentTimeMillis())

    @Synchronized
    fun isExpired(): Boolean = System.currentTimeMillis() >= expiresAt

    private fun generate(now: Long): String {
        val chars = CharArray(LENGTH) { ALPHABET[random.nextInt(ALPHABET.length)] }
        val code = String(chars)
        current = code
        expiresAt = now + TTL_MS
        return code
    }
}
