package com.darelisme.sweetspot.transport.webrtc

import android.content.Context
import android.util.Log
import com.darelisme.sweetspot.pairing.PairingSessionManager
import com.darelisme.sweetspot.calibration.transport.CalibrationCaptureStreamFrame
import com.darelisme.sweetspot.calibration.transport.CalibrationCaptureStreamWire
import com.darelisme.sweetspot.transport.PeerTransport
import com.darelisme.sweetspot.transport.PeerTransportDiagnostics
import com.darelisme.sweetspot.transport.PeerTransportState
import com.darelisme.sweetspot.transport.protocol.PeerEnvelopeValidator
import com.darelisme.sweetspot.transport.signaling.SignalingClient
import livekit.org.webrtc.DataChannel
import livekit.org.webrtc.IceCandidate
import livekit.org.webrtc.MediaConstraints
import livekit.org.webrtc.PeerConnection
import livekit.org.webrtc.PeerConnectionFactory
import livekit.org.webrtc.RTCStatsCollectorCallback
import livekit.org.webrtc.RTCStatsReport
import livekit.org.webrtc.SdpObserver
import livekit.org.webrtc.SessionDescription
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.LinkedHashSet
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Direct, data-channel-only peer transport owned by the service. */
class WebRtcPeerTransport(
    context: Context,
    private val pairingSessionProvider: () -> PairingSessionManager.Session,
    private val commandHandler: PeerTransport.CommandHandler,
    private val onSessionConnected: (String) -> Unit,
    private val onSessionDisconnected: (String) -> Unit,
) : PeerTransport {
    companion object {
        private const val TAG = "SweetSpotWebRtc"
        private const val CONTROL_CHANNEL = "control"
        private const val CAPTURE_CHANNEL = "capture"
        private const val MAX_CONTROL_BYTES = 64 * 1024
        private const val MAX_CAPTURE_FRAME_BYTES = 32 * 1024
        private const val MAX_CAPTURE_QUEUE = 8
        private const val MAX_SEEN_MESSAGES = 512
        private const val RECONNECT_CLOSE_GRACE_MS = 30_000L
    }

    private val appContext = context.applicationContext
    private val controlExecutor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "sweetspot-peer-control").apply { isDaemon = true }
    }
    private val captureExecutor: ThreadPoolExecutor = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(MAX_CAPTURE_QUEUE),
        { runnable -> Thread(runnable, "sweetspot-peer-capture").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
    )
    private val signaling = SignalingClient(
        role = "device",
        sessionProvider = pairingSessionProvider,
        listener = object : SignalingClient.Listener {
            override fun onConnected() = postControl {
                if (currentState != PeerTransportState.DIRECT) setState(PeerTransportState.SIGNALING)
            }

            override fun onMessage(message: JSONObject) = postControl { handleSignal(message) }

            override fun onClosed(reason: String) = postControl {
                if (currentState != PeerTransportState.DIRECT && currentState != PeerTransportState.RECONNECTING) {
                    setError("Signaling unavailable: $reason")
                    setState(PeerTransportState.SIGNALING)
                }
            }
        },
    )

    override var listener: PeerTransport.Listener? = null

    private val running = AtomicBoolean(false)
    private var currentState = PeerTransportState.IDLE
    private var currentGeneration: String? = null
    private var peer: PeerConnection? = null
    private var factory: PeerConnectionFactory? = null
    private var control: DataChannel? = null
    private var capture: DataChannel? = null
    private var remoteDescriptionSet = false
    private var localReady = false
    private var remoteReady = false
    private var reconnectCount = 0
    private var bytesSent = 0L
    private var bytesReceived = 0L
    private var lastError: String? = null
    private var selectedCandidateType: String? = null
    private var selectedCandidateProtocol: String? = null
    private var rttMs: Double? = null
    @Volatile
    private var lastControlMessageAt: Long? = null
    @Volatile
    private var lastPeerTrafficAt: Long? = null
    private var directPresenceSent = false
    private var reconnectCloseTask: ScheduledFuture<*>? = null
    private val pendingCandidates = ArrayDeque<IceCandidate>()
    private val seenMessageIds = LinkedHashSet<String>()

    override fun start() {
        if (!running.compareAndSet(false, true)) return
        postControl {
            setState(PeerTransportState.PAIRING)
            signaling.start()
        }
    }

    override fun stop() {
        if (!running.getAndSet(false)) return
        try { signaling.stop() } catch (_: Throwable) {}
        val closed = CountDownLatch(1)
        try {
            controlExecutor.execute {
                try {
                    closePeer(notify = true)
                    setState(PeerTransportState.CLOSED)
                } finally {
                    closed.countDown()
                }
            }
        } catch (_: RejectedExecutionException) {
            closePeer(notify = true)
            setState(PeerTransportState.CLOSED)
            closed.countDown()
        }
        if (closed.await(1, TimeUnit.SECONDS).not()) {
            closePeer(notify = true)
            setState(PeerTransportState.CLOSED)
        }
        controlExecutor.shutdown()
        captureExecutor.shutdownNow()
        captureExecutor.awaitTermination(1, TimeUnit.SECONDS)
    }

    override fun reconnectForPairingRotation() {
        if (!running.get()) return
        postControl {
            if (directPresenceSent) return@postControl
            closePeer(notify = false)
            currentGeneration = null
            signaling.resetGeneration()
            signaling.reconnect()
            setState(PeerTransportState.PAIRING)
        }
    }

    override fun publish(type: String, payload: JSONObject) {
        postControl {
            val envelope = envelope(type, payload)
            sendControl(envelope)
        }
    }

    private fun postControl(block: () -> Unit) {
        if (!running.get() && currentState != PeerTransportState.CLOSED) return
        try {
            controlExecutor.execute(block)
        } catch (_: RejectedExecutionException) {
            // Shutdown is final. No work may be queued after service teardown.
        }
    }

    private fun handleSignal(message: JSONObject) {
        when (message.optString("type")) {
            "signal.ready" -> if (message.optBoolean("peerOnline", false)) {
                setState(PeerTransportState.SIGNALING)
            } else {
                setState(PeerTransportState.PAIRING)
            }
            "signal.peer" -> Unit
            "signal.offer" -> handleOffer(message)
            "signal.ice" -> handleCandidate(message)
            "signal.error" -> {
                val code = message.optString("code", "signaling_error")
                val detail = message.optString("message", "Signaling failed")
                setError("$code: $detail")
                if (currentState != PeerTransportState.DIRECT) setState(PeerTransportState.FAILED)
            }
        }
    }

    private fun handleOffer(message: JSONObject) {
        val generation = message.optString("generation")
        val description = message.optJSONObject("description")
        val sdp = description?.optString("sdp").orEmpty()
        if (generation.isBlank() || description?.optString("type") != "offer" || sdp.isBlank()) {
            setError("The TV received an invalid direct connection offer")
            return
        }
        if (directPresenceSent && currentGeneration != generation) {
            setError("A dashboard is already connected to this TV")
            return
        }
        if (currentGeneration != generation || hasClosedDataChannel()) {
            closePeer(notify = directPresenceSent)
            currentGeneration = generation
        }
        signaling.setGeneration(generation)
        setState(PeerTransportState.CONNECTING)
        val connection = ensurePeer() ?: return
        val offer = SessionDescription(SessionDescription.Type.OFFER, sdp)
        connection.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(description: SessionDescription) = Unit

            override fun onSetSuccess() = postControl {
                if (peer !== connection || currentGeneration != generation) return@postControl
                remoteDescriptionSet = true
                while (pendingCandidates.isNotEmpty()) connection.addIceCandidate(pendingCandidates.removeFirst())
                createAnswer(connection, generation)
            }

            override fun onCreateFailure(error: String) = Unit

            override fun onSetFailure(error: String) = postControl {
                if (peer === connection) failPeer("The TV could not apply the browser connection offer: $error")
            }
        }, offer)
    }

    private fun createAnswer(connection: PeerConnection, generation: String) {
        connection.createAnswer(object : SdpObserver {
            override fun onCreateSuccess(description: SessionDescription) {
                if (description.type != SessionDescription.Type.ANSWER) return
                connection.setLocalDescription(object : SdpObserver {
                    override fun onCreateSuccess(description: SessionDescription) = Unit

                    override fun onSetSuccess() = postControl {
                        if (peer !== connection || currentGeneration != generation) return@postControl
                        signaling.send(JSONObject().apply {
                            put("v", 1)
                            put("type", "signal.answer")
                            put("generation", generation)
                            put("description", JSONObject().put("type", "answer").put("sdp", description.description))
                        })
                    }

                    override fun onCreateFailure(error: String) = Unit

                    override fun onSetFailure(error: String) = postControl {
                        if (peer === connection) failPeer("The TV could not start the direct connection: $error")
                    }
                }, description)
            }

            override fun onSetSuccess() = Unit

            override fun onCreateFailure(error: String) = postControl {
                if (peer === connection) failPeer("The TV could not create a direct answer: $error")
            }

            override fun onSetFailure(error: String) = Unit
        }, MediaConstraints())
    }

    private fun handleCandidate(message: JSONObject) {
        val generation = message.optString("generation")
        if (generation.isBlank() || generation != currentGeneration) return
        val value = message.optJSONObject("candidate") ?: return
        val candidate = IceCandidate(
            if (value.isNull("sdpMid")) null else value.optString("sdpMid"),
            value.optInt("sdpMLineIndex", -1),
            value.optString("candidate"),
        )
        if (candidate.sdp.isBlank() || candidate.sdpMLineIndex < 0) return
        val connection = peer ?: return
        if (remoteDescriptionSet) connection.addIceCandidate(candidate) else pendingCandidates.addLast(candidate)
    }

    private fun ensurePeer(): PeerConnection? {
        peer?.let { return it }
        val generation = currentGeneration ?: return null
        try {
            if (factory == null) {
                PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions.builder(appContext)
                        .createInitializationOptions(),
                )
                factory = PeerConnectionFactory.builder()
                    .setOptions(PeerConnectionFactory.Options().apply { disableNetworkMonitor = true })
                    .createPeerConnectionFactory()
            }
            var createdConnection: PeerConnection? = null
            val connection = factory?.createPeerConnection(
                PeerConnection.RTCConfiguration(emptyList()),
                object : PeerConnection.Observer {
                    override fun onSignalingChange(newState: PeerConnection.SignalingState) = Unit

                    override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState) = postControl {
                        if (peer === createdConnection) {
                            updatePeerState(newState.name, null)
                            requestStats(createdConnection!!)
                        }
                    }

                    override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit

                    override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState) = postControl {
                        if (peer === createdConnection) updatePeerState(null, newState.name)
                    }

                    override fun onIceCandidate(candidate: IceCandidate) = postControl {
                        if (peer === createdConnection && currentGeneration == generation) sendCandidate(candidate, generation)
                    }

                    override fun onIceCandidatesRemoved(candidates: Array<IceCandidate>) = Unit

                    override fun onAddStream(stream: livekit.org.webrtc.MediaStream) = Unit

                    override fun onRemoveStream(stream: livekit.org.webrtc.MediaStream) = Unit

                    override fun onDataChannel(channel: DataChannel) = postControl {
                        if (peer === createdConnection) configureChannel(channel)
                    }

                    override fun onRenegotiationNeeded() = Unit

                    override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) = postControl {
                        if (peer === createdConnection) {
                            handleConnectionState(newState)
                            requestStats(createdConnection!!)
                        }
                    }
                },
            ).also { createdConnection = it }
                ?: throw IllegalStateException("The WebRTC factory did not create a peer")
            peer = connection
            remoteDescriptionSet = false
            localReady = false
            remoteReady = false
            return connection
        } catch (error: Throwable) {
            failPeer("Direct connection setup failed: ${error.message ?: error.javaClass.simpleName}")
            return null
        }
    }

    private fun configureChannel(channel: DataChannel) {
        val owner = peer ?: run {
            disposeChannel(channel)
            return
        }
        when (channel.label()) {
            CONTROL_CHANNEL -> {
                control?.let { disposeChannel(it) }
                control = channel
                channel.registerObserver(channelObserver(channel, owner, binary = false))
            }
            CAPTURE_CHANNEL -> {
                capture?.let { disposeChannel(it) }
                capture = channel
                channel.registerObserver(channelObserver(channel, owner, binary = true))
            }
            else -> channel.close()
        }
        maybeDirect()
    }

    private fun channelObserver(channel: DataChannel, owner: PeerConnection, binary: Boolean): DataChannel.Observer = object : DataChannel.Observer {
        override fun onBufferedAmountChange(previousAmount: Long) = Unit

        override fun onStateChange() = postControl {
            if (peer === owner) handleChannelState(channel)
        }

        override fun onMessage(buffer: DataChannel.Buffer) {
            if (peer !== owner) return
            if (buffer.binary != binary) {
                postControl {
                    setError(if (binary) {
                        "The TV received a text message on the capture channel"
                    } else {
                        "The TV received binary data on the control channel"
                    })
                }
                return
            }
            val bytes = try {
                val copy = ByteArray(buffer.data.remaining())
                buffer.data.get(copy)
                copy
            } catch (error: Throwable) {
                Log.w(TAG, "Unable to copy DataChannel message", error)
                return
            }
            synchronized(this@WebRtcPeerTransport) { bytesReceived += bytes.size }
            lastPeerTrafficAt = System.currentTimeMillis()
            if (binary) {
                if (bytes.size > MAX_CAPTURE_FRAME_BYTES) {
                    postControl { setError("The capture chunk exceeds the direct transport limit") }
                    return
                }
                try {
                    captureExecutor.execute { handleCaptureFrame(owner, bytes) }
                } catch (_: RejectedExecutionException) {
                    postControl { setError("The TV capture queue is full. Retry this capture.") }
                }
            } else {
                postControl { handleControlMessage(owner, bytes) }
            }
        }
    }

    private fun handleChannelState(channel: DataChannel) {
        if (channel.state() == DataChannel.State.CLOSING || channel.state() == DataChannel.State.CLOSED) {
            if (currentState != PeerTransportState.RECONNECTING) {
                reconnectCount++
                signaling.reconnect()
                if (directPresenceSent) {
                    setState(PeerTransportState.RECONNECTING)
                    listener?.onPeerPresence(false, currentGeneration)
                    directPresenceSent = false
                } else {
                    setState(PeerTransportState.RECONNECTING)
                }
            }
            schedulePeerClose()
        } else {
            maybeDirect()
        }
    }

    private fun hasClosedDataChannel(): Boolean = listOf(control, capture).any {
        it?.state() == DataChannel.State.CLOSING || it?.state() == DataChannel.State.CLOSED
    }

    private fun handleCaptureFrame(owner: PeerConnection, bytes: ByteArray) {
        if (peer !== owner) return
        try {
            val frame = CalibrationCaptureStreamWire.decode(bytes)
            if (peer !== owner || frame.sessionId != currentGeneration) return
            commandHandler.onCaptureFrame(frame)
            if (frame is CalibrationCaptureStreamFrame.End) {
                postControl { setState(PeerTransportState.DIRECT) }
            }
        } catch (error: Throwable) {
            postControl { setError(error.message ?: "The TV rejected the capture stream") }
        }
    }

    private fun handleControlMessage(owner: PeerConnection, bytes: ByteArray) {
        if (peer !== owner) return
        lastControlMessageAt = System.currentTimeMillis()
        if (bytes.size > MAX_CONTROL_BYTES) {
            setError("The direct control message exceeds the size limit")
            return
        }
        val value = try {
            JSONObject(String(bytes, StandardCharsets.UTF_8))
        } catch (error: Throwable) {
            setError("The TV received invalid direct control data")
            return
        }
        if (value.optString("kind") == "sweetspot.transport") {
            handleCapability(value)
            return
        }
        val generation = currentGeneration ?: return
        if (!value.has("transportSessionId") || value.optString("transportSessionId") != generation) return
        if (!PeerEnvelopeValidator.isValid(value, generation)) {
            setError("The TV received an invalid control envelope")
            return
        }
        val id = value.getString("id")
        val type = value.getString("type")
        val payload = value.getJSONObject("payload")
        val expiresAt = value.opt("expiresAt") as? Number
        if (expiresAt != null && expiresAt.toDouble() <= System.currentTimeMillis()) return
        if (!seenMessageIds.add(id)) return
        while (seenMessageIds.size > MAX_SEEN_MESSAGES) seenMessageIds.iterator().let { seenMessageIds.remove(it.next()) }
        if (type == "ping") {
            sendControl(envelope("pong", JSONObject(), id))
            return
        }
        commandHandler.onCommand(type, payload) { replyType, replyPayload ->
            postControl { sendControl(envelope(replyType, replyPayload, id)) }
        }
    }

    private fun handleCapability(value: JSONObject) {
        val generation = currentGeneration ?: return
        if (value.optString("sessionId") != generation) return
        if (value.optString("type") != "hello" && value.optString("type") != "ready") {
            failPeer("The dashboard sent an invalid direct transport handshake")
            return
        }
        val capabilities = value.optJSONObject("capabilities") ?: return
        if (capabilities.optInt("protocolVersion", -1) != 1
            || capabilities.optInt("transportVersion", -1) != 1
            || capabilities.optInt("captureStreamVersion", -1) != 1
            || capabilities.optJSONArray("channels")?.let { it.length() == 2 && it.optString(0) == CONTROL_CHANNEL && it.optString(1) == CAPTURE_CHANNEL } != true
            || capabilities.optInt("maxCaptureChunkBytes", 0) !in 1..16 * 1024
        ) {
            failPeer("The dashboard and TV do not support the same direct transport")
            return
        }
        remoteReady = true
        if (value.optString("type") == "hello") sendCapability("ready")
        maybeDirect()
    }

    private fun sendCapability(type: String) {
        val generation = currentGeneration ?: return
        val payload = JSONObject().apply {
            put("kind", "sweetspot.transport")
            put("type", type)
            put("sessionId", generation)
            put("capabilities", JSONObject().apply {
                put("protocolVersion", 1)
                put("transportVersion", 1)
                put("captureStreamVersion", 1)
                put("buildId", com.darelisme.sweetspot.BuildConfig.SWEETSPOT_BUILD_ID)
                put("channels", org.json.JSONArray().put(CONTROL_CHANNEL).put(CAPTURE_CHANNEL))
                put("maxCaptureChunkBytes", 16 * 1024)
            })
        }
        if (sendRawControl(payload.toString())) localReady = true
    }

    private fun maybeDirect() {
        val generation = currentGeneration ?: return
        if (control?.state() != DataChannel.State.OPEN || capture?.state() != DataChannel.State.OPEN) return
        if (!localReady) sendCapability("hello")
        if (!localReady || !remoteReady) return
        reconnectCloseTask?.cancel(false)
        reconnectCloseTask = null
        setState(PeerTransportState.DIRECT)
        if (!directPresenceSent) {
            directPresenceSent = true
            pairingSessionConnected(generation)
            listener?.onPeerPresence(true, generation)
        }
        requestStats(peer ?: return)
        signaling.suspend()
    }

    private fun sendCandidate(candidate: IceCandidate, generation: String) {
        signaling.send(JSONObject().apply {
            put("v", 1)
            put("type", "signal.ice")
            put("generation", generation)
            put("candidate", JSONObject().apply {
                put("candidate", candidate.sdp)
                put("sdpMid", candidate.sdpMid ?: JSONObject.NULL)
                put("sdpMLineIndex", candidate.sdpMLineIndex)
            })
        })
    }

    private fun sendRawControl(text: String): Boolean {
        val channel = control ?: return false
        if (channel.state() != DataChannel.State.OPEN) return false
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        if (bytes.size > MAX_CONTROL_BYTES) return false
        val sent = channel.send(DataChannel.Buffer(ByteBuffer.wrap(bytes), false))
        if (sent) {
            bytesSent += bytes.size
            lastPeerTrafficAt = System.currentTimeMillis()
        }
        return sent
    }

    private fun sendControl(envelope: JSONObject): Boolean = sendRawControl(envelope.toString())

    private fun envelope(type: String, payload: JSONObject, replyTo: String? = null): JSONObject = JSONObject().apply {
        val id = "dev_${System.currentTimeMillis().toString(36)}_${bytesSent.toString(36)}"
        put("v", 1)
        put("id", id)
        put("type", type)
        put("ts", System.currentTimeMillis())
        put("transportSessionId", currentGeneration ?: "")
        put("payload", payload)
        if (replyTo != null) put("replyTo", replyTo)
    }

    private fun handleConnectionState(state: PeerConnection.PeerConnectionState) {
        when (state) {
            PeerConnection.PeerConnectionState.CONNECTED -> maybeDirect()
            PeerConnection.PeerConnectionState.DISCONNECTED,
            PeerConnection.PeerConnectionState.FAILED,
            -> {
                reconnectCount++
                signaling.reconnect()
                if (directPresenceSent) {
                    setState(PeerTransportState.RECONNECTING)
                    listener?.onPeerPresence(false, currentGeneration)
                    directPresenceSent = false
                } else {
                    setState(PeerTransportState.RECONNECTING)
                }
                schedulePeerClose()
            }
            PeerConnection.PeerConnectionState.CLOSED -> {
                closePeer(notify = true)
                if (running.get()) setState(PeerTransportState.PAIRING)
            }
            else -> if (currentState != PeerTransportState.DIRECT) setState(PeerTransportState.CONNECTING)
        }
    }

    private fun updatePeerState(ice: String?, gathering: String?) {
        val next = diagnostics().copy(
            iceConnectionState = ice ?: diagnostics().iceConnectionState,
            iceGatheringState = gathering ?: diagnostics().iceGatheringState,
        )
        listener?.onStateChanged(currentState, next)
    }

    private fun requestStats(connection: PeerConnection) {
        try {
            connection.getStats(object : RTCStatsCollectorCallback {
                override fun onStatsDelivered(report: RTCStatsReport) {
                    postControl {
                        if (peer === connection) applyStats(report)
                    }
                }
            })
        } catch (_: Throwable) {
            // Stats are optional diagnostics and may race peer teardown.
        }
    }

    private fun applyStats(report: RTCStatsReport) {
        val stats = report.statsMap.values
        val selectedPair = stats.firstOrNull { stat ->
            stat.type == "candidate-pair" && (
                stat.members["selected"] == true
                    || (stat.members["nominated"] == true && stat.members["state"] == "succeeded")
                )
        } ?: return
        val localId = selectedPair.members["localCandidateId"] as? String
        val remoteId = selectedPair.members["remoteCandidateId"] as? String
        val local = stats.firstOrNull { it.id == localId }
        val remote = stats.firstOrNull { it.id == remoteId }
        selectedCandidateType = (local?.members?.get("candidateType") as? String)
            ?: (remote?.members?.get("candidateType") as? String)
        selectedCandidateProtocol = (local?.members?.get("protocol") as? String)
            ?: (remote?.members?.get("protocol") as? String)
        rttMs = (selectedPair.members["currentRoundTripTime"] as? Number)
            ?.toDouble()
            ?.takeIf { it.isFinite() && it >= 0.0 }
            ?.times(1_000.0)
        emitDiagnostics()
    }

    private fun failPeer(message: String) {
        setError(message)
        setState(PeerTransportState.FAILED)
    }

    private fun setError(message: String) {
        lastError = message
        listener?.onError(message)
        emitDiagnostics()
    }

    private fun setState(next: PeerTransportState) {
        currentState = next
        emitDiagnostics()
        listener?.onStateChanged(next, diagnostics())
    }

    private fun emitDiagnostics() {
        listener?.onStateChanged(currentState, diagnostics())
    }

    override fun diagnostics(): PeerTransportDiagnostics = PeerTransportDiagnostics(
        state = currentState,
        sessionId = currentGeneration,
        peerConnectionState = peer?.connectionState()?.name,
        iceConnectionState = peer?.iceConnectionState()?.name,
        iceGatheringState = peer?.iceGatheringState()?.name,
        selectedCandidateType = selectedCandidateType,
        selectedCandidateProtocol = selectedCandidateProtocol,
        rttMs = rttMs,
        bytesSent = bytesSent,
        bytesReceived = bytesReceived,
        reconnectCount = reconnectCount,
        captureBufferedBytes = capture?.bufferedAmount() ?: 0L,
        lastControlMessageAt = lastControlMessageAt,
        lastPeerTrafficAt = lastPeerTrafficAt,
        lastError = lastError,
    )

    @Synchronized
    private fun closePeer(notify: Boolean) {
        reconnectCloseTask?.cancel(false)
        reconnectCloseTask = null
        val generation = currentGeneration
        val wasPresent = directPresenceSent
        directPresenceSent = false
        control?.let(::disposeChannel)
        capture?.let(::disposeChannel)
        control = null
        capture = null
        pendingCandidates.clear()
        remoteDescriptionSet = false
        localReady = false
        remoteReady = false
        selectedCandidateType = null
        selectedCandidateProtocol = null
        rttMs = null
        val oldPeer = peer
        peer = null
        try { oldPeer?.close() } catch (_: Throwable) {}
        try { oldPeer?.dispose() } catch (_: Throwable) {}
        val oldFactory = factory
        try { oldFactory?.dispose() } catch (_: Throwable) {}
        if (oldFactory != null) {
            try { PeerConnectionFactory.shutdownInternalTracer() } catch (_: Throwable) {}
        }
        factory = null
        pairingSessionDisconnected(generation)
        if (wasPresent && notify) listener?.onPeerPresence(false, generation)
    }

    private fun schedulePeerClose() {
        if (reconnectCloseTask != null || currentGeneration == null) return
        val generation = currentGeneration ?: return
        reconnectCloseTask = controlExecutor.schedule({
            if (currentGeneration == generation && !directPresenceSent) {
                closePeer(notify = false)
                if (running.get()) setState(PeerTransportState.PAIRING)
            }
        }, RECONNECT_CLOSE_GRACE_MS, TimeUnit.MILLISECONDS)
    }

    private fun disposeChannel(channel: DataChannel) {
        try { channel.unregisterObserver() } catch (_: Throwable) {}
        try { channel.close() } catch (_: Throwable) {}
        try { channel.dispose() } catch (_: Throwable) {}
    }

    private fun pairingSessionConnected(generation: String) {
        try { onSessionConnected(generation) } catch (_: Throwable) {}
    }

    private fun pairingSessionDisconnected(generation: String?) {
        if (generation == null) return
        try { onSessionDisconnected(generation) } catch (_: Throwable) {}
    }
}
