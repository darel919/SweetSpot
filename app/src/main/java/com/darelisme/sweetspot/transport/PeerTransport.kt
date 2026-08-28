package com.darelisme.sweetspot.transport

import org.json.JSONObject
import com.darelisme.sweetspot.calibration.transport.CalibrationCaptureStreamFrame

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
    val lastControlMessageAt: Long? = null,
    val lastPeerTrafficAt: Long? = null,
    val lastError: String? = null,
)

interface PeerTransport {
    interface CommandHandler {
        fun onCommand(type: String, payload: JSONObject, replyTo: (String, JSONObject) -> Unit)

        fun onCaptureFrame(frame: CalibrationCaptureStreamFrame)
    }

    interface Listener {
        fun onStateChanged(state: PeerTransportState, diagnostics: PeerTransportDiagnostics) {}

        fun onPeerPresence(present: Boolean, sessionId: String?) {}

        fun onError(message: String) {}
    }

    var listener: Listener?

    fun start()

    fun stop()

    fun reconnectForPairingRotation()

    fun diagnostics(): PeerTransportDiagnostics

    fun publish(type: String, payload: JSONObject = JSONObject())
}
