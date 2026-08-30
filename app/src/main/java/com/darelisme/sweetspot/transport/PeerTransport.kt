package com.darelisme.sweetspot.transport

import org.json.JSONObject

enum class PeerTransportState {
    IDLE,
    PAIRING,
    SIGNALING,
    CONNECTING,
    DIRECT,
    RECONNECTING,
    FAILED,
    CLOSED,
}

data class PeerTransportDiagnostics(
    val state: PeerTransportState = PeerTransportState.IDLE,
    val sessionId: String? = null,
    val iceConnectionState: String? = null,
    val iceGatheringState: String? = null,
    val peerConnectionState: String? = null,
    val selectedCandidateType: String? = null,
    val selectedCandidateProtocol: String? = null,
    val rttMs: Double? = null,
    val bytesSent: Long = 0,
    val bytesReceived: Long = 0,
    val reconnectCount: Int = 0,
    val captureBufferedBytes: Long = 0,
    val signalingRoundTripMs: Long? = null,
    val lastControlMessageAt: Long? = null,
    val lastPeerTrafficAt: Long? = null,
    val lastError: String? = null,
)

interface PeerTransport {
    interface CommandHandler {
        fun onCommand(sessionId: String, type: String, payload: JSONObject, replyTo: (String, JSONObject) -> Unit)

        fun onCaptureData(sessionId: String, data: ByteArray)

        fun onCaptureDataRejected(sessionId: String, data: ByteArray, reason: String) {}
    }

    interface Listener {
        fun onStateChanged(state: PeerTransportState, diagnostics: PeerTransportDiagnostics) {}

        fun onPeerPresence(present: Boolean, sessionId: String?) {}

        fun onError(message: String) {}
    }

    var listener: Listener?

    fun start()

    /** Opens the short-lived pairing/signaling path without changing service lifetime. */
    fun openPairing() {}

    /** Closes pairing availability while preserving an authenticated direct peer. */
    fun closePairing() {}

    fun stop()

    fun reconnectForPairingRotation()

    fun diagnostics(): PeerTransportDiagnostics

    fun publish(type: String, payload: JSONObject = JSONObject())
}
