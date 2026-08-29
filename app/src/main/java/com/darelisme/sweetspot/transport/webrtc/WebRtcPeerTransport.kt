package com.darelisme.sweetspot.transport.webrtc

import android.content.Context
import android.util.Log
import com.darelisme.sweetspot.pairing.PairingSessionManager
import com.darelisme.sweetspot.transport.PeerTransport
import com.darelisme.sweetspot.transport.PeerTransportDiagnostics
import com.darelisme.sweetspot.transport.PeerTransportState
import com.darelisme.sweetspot.transport.shouldKeepSignaling
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
import java.nio.charset.CodingErrorAction
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
import java.util.concurrent.atomic.AtomicLong

/** Direct, data-channel-only peer transport owned by the service. */
class WebRtcPeerTransport(
    context: Context,
    private val pairingSessionProvider: () -> PairingSessionManager.Session,
    private val commandHandler: PeerTransport.CommandHandler,
    private val onSessionConnected: (String) -> Boolean,
    private val onSessionDisconnected: (String) -> Unit,
) : PeerTransport {
    companion object {
        private const val TAG = "SweetSpotWebRtc"
        private const val CONTROL_CHANNEL = "control"
        private const val CAPTURE_CHANNEL = "capture"
        private const val MAX_CONTROL_BYTES = 16 * 1024
        private const val MAX_SIGNALING_TEXT_BYTES = 48 * 1024
        private const val MAX_CAPTURE_FRAME_BYTES = 32 * 1024
        private const val MAX_CAPTURE_QUEUE = 8
        private const val MAX_CONTROL_QUEUE = 128
        private const val MAX_CONTROL_BUFFERED_BYTES = 256 * 1024
        private const val MAX_PENDING_CONTROL_BYTES = 256 * 1024
        private const val MAX_PENDING_PRIORITY_CONTROL_BYTES = 64 * 1024
        private const val MAX_PENDING_PRIORITY_CONTROL = 16
        private const val CONTROL_FLUSH_DELAY_MS = 25L
        private const val MAX_PENDING_CANDIDATES = 64
        private const val MAX_SEEN_MESSAGES = 512
        private const val RECONNECT_CLOSE_GRACE_MS = 90_000L
        private val GENERATION_PATTERN = Regex("[A-Za-z0-9_-]{1,128}")
        private val ATTEMPT_PATTERN = Regex("[A-Za-z0-9_-]{1,128}")
        private val NON_RETRYABLE_SIGNALING_ERRORS = setOf(
            "bad_message",
            "bad_json",
            "device_in_use",
            "invalid_pairing",
            "origin_rejected",
            "pairing_expired",
            "payload_too_large",
            "peer_in_use",
            "stale_session",
            "protocol_mismatch",
            "rate_limited",
            "too_many_ice_candidates",
        )
    }

    private val appContext = context.applicationContext
    private val controlExecutor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "sweetspot-peer-control").apply { isDaemon = true }
    }
    private val controlQueuePermits = java.util.concurrent.Semaphore(MAX_CONTROL_QUEUE, true)
    private val priorityControlExecutor: ThreadPoolExecutor = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(16),
        { runnable -> Thread(runnable, "sweetspot-peer-priority").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
    )
    private val controlDispatchLock = Any()
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
            override fun onConnected(roundTripMs: Long?) = postControl {
                if (!signalingShouldRun()) {
                    if (currentState != PeerTransportState.DIRECT) setState(PeerTransportState.IDLE)
                    return@postControl
                }
                signalingRoundTripMs = roundTripMs
                if (currentState != PeerTransportState.DIRECT) setState(PeerTransportState.SIGNALING)
            }

            override fun onMessage(message: JSONObject) = postControl { handleSignal(message) }

            override fun onClosed(reason: String) = postControl {
                if (!signalingShouldRun()) return@postControl
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
    private var pairingVisible = false
    @Volatile
    private var currentGeneration: String? = null
    private var authenticatedGeneration: String? = null
    private var currentAttemptId: String? = null
    @Volatile
    private var peer: PeerConnection? = null
    private var factory: PeerConnectionFactory? = null
    private var control: DataChannel? = null
    private var capture: DataChannel? = null
    private var remoteDescriptionSet = false
    private var localReady = false
    private var remoteReady = false
    private var reconnectCount = 0
    private val messageCounter = AtomicLong(0L)
    private var bytesSent = 0L
    private var bytesReceived = 0L
    private var lastError: String? = null
    private var selectedCandidateType: String? = null
    private var selectedCandidateProtocol: String? = null
    private var rttMs: Double? = null
    private var signalingRoundTripMs: Long? = null
    @Volatile
    private var lastControlMessageAt: Long? = null
    @Volatile
    private var lastPeerTrafficAt: Long? = null
    private var directPresenceSent = false
    private var reconnectCloseTask: ScheduledFuture<*>? = null
    private var controlFlushTask: ScheduledFuture<*>? = null
    private val pendingCandidates = ArrayDeque<IceCandidate>()
    private val seenMessageIds = LinkedHashSet<String>()
    private val pendingControl = BoundedControlQueue(
        maxMessages = MAX_CONTROL_QUEUE,
        maxBytes = MAX_PENDING_CONTROL_BYTES,
        maxPriorityMessages = MAX_PENDING_PRIORITY_CONTROL,
        maxPriorityBytes = MAX_PENDING_PRIORITY_CONTROL_BYTES,
    )

    private fun signalingShouldRun(): Boolean = shouldKeepSignaling(
        serviceRunning = running.get(),
        pairingVisible = pairingVisible,
        directPeer = directPresenceSent,
        recoveryPending = currentState == PeerTransportState.RECONNECTING && authenticatedGeneration != null,
    )

    override fun start() {
        if (!running.compareAndSet(false, true)) return
        postControl {
            pairingVisible = false
            setState(PeerTransportState.IDLE)
        }
    }

    override fun openPairing() {
        if (!running.get()) return
        postControl {
            pairingVisible = true
            if (directPresenceSent) return@postControl
            setState(PeerTransportState.PAIRING)
            signaling.start()
        }
    }

    override fun closePairing() {
        if (!running.get()) return
        postControl {
            pairingVisible = false
            val recoveryPending = currentState == PeerTransportState.RECONNECTING
                && authenticatedGeneration != null
            if (directPresenceSent) return@postControl
            if (recoveryPending) {
                signaling.start()
                return@postControl
            }
            closePeer(notify = false)
            currentGeneration = null
            signaling.resetGeneration()
            signaling.suspend()
            setState(PeerTransportState.IDLE)
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
        controlExecutor.shutdownNow()
        priorityControlExecutor.shutdownNow()
        captureExecutor.shutdownNow()
        captureExecutor.awaitTermination(1, TimeUnit.SECONDS)
    }

    override fun reconnectForPairingRotation() {
        if (!running.get()) return
        postControl {
            if (directPresenceSent || authenticatedGeneration != null) return@postControl
            closePeer(notify = false)
            currentGeneration = null
            signaling.resetGeneration()
            if (pairingVisible) {
                signaling.start()
                setState(PeerTransportState.PAIRING)
            } else {
                signaling.suspend()
                setState(PeerTransportState.IDLE)
            }
        }
    }

    override fun publish(type: String, payload: JSONObject) {
        postControl {
            val envelope = envelope(type, payload)
            sendControl(envelope)
        }
    }

    private fun postControl(priority: Boolean = false, block: () -> Unit) {
        if (!running.get()) return
        if (priority) {
            try {
                priorityControlExecutor.execute {
                    synchronized(controlDispatchLock) { block() }
                }
            } catch (_: RejectedExecutionException) {
                if (!controlQueuePermits.tryAcquire()) {
                    setError("The TV control queue is unavailable. Retry the connection.")
                    return
                }
                try {
                    controlExecutor.execute {
                        try {
                            synchronized(controlDispatchLock) { block() }
                        } finally {
                            controlQueuePermits.release()
                        }
                    }
                } catch (_: RejectedExecutionException) {
                    controlQueuePermits.release()
                    setError("The TV control queue is unavailable. Retry the connection.")
                }
            }
            return
        }
        if (!controlQueuePermits.tryAcquire()) {
            setError("The TV control queue is full. Retry the connection.")
            return
        }
        try {
            controlExecutor.execute {
                try {
                    synchronized(controlDispatchLock) { block() }
                } finally {
                    controlQueuePermits.release()
                }
            }
        } catch (_: RejectedExecutionException) {
            controlQueuePermits.release()
        }
    }

    private fun handleSignal(message: JSONObject) {
        if (!signalingShouldRun()) return
        if (!exactInt(message.opt("v"), 1)) {
            signaling.suspend()
            failPeer("The signaling service sent an unsupported message", retryable = false)
            return
        }
        when (message.optString("type")) {
            "signal.ready" -> {
                val role = message.opt("role") as? String
                val peerOnline = message.opt("peerOnline") as? Boolean
                if (role != "device" || peerOnline == null) {
                    rejectSignalingMessage("The signaling service sent an invalid ready message")
                    return
                }
                if (peerOnline) setState(PeerTransportState.SIGNALING) else setState(PeerTransportState.PAIRING)
            }
            "signal.peer" -> {
                val role = message.opt("role") as? String
                val online = message.opt("online") as? Boolean
                if (role != "client" || online == null) {
                    rejectSignalingMessage("The signaling service sent an invalid peer message")
                } else if (!online && authenticatedGeneration == null && currentGeneration != null) {
                    closePeer(notify = false)
                    currentGeneration = null
                    signaling.resetGeneration()
                    if (pairingVisible) {
                        signaling.start()
                        setState(PeerTransportState.PAIRING)
                    } else {
                        signaling.suspend()
                        setState(PeerTransportState.IDLE)
                    }
                }
            }
            "signal.offer" -> handleOffer(message)
            "signal.ice" -> handleCandidate(message)
            "signal.error" -> {
                val code = message.opt("code") as? String ?: ""
                val detail = message.opt("message") as? String ?: ""
                if (code.isBlank() || detail.isBlank() || code.length > 128 || detail.length > 2_048
                    || code.toByteArray(StandardCharsets.UTF_8).size > 128
                    || detail.toByteArray(StandardCharsets.UTF_8).size > MAX_SIGNALING_TEXT_BYTES
                ) {
                    rejectSignalingMessage("The signaling service sent an invalid error")
                    return
                }
                setError("$code: $detail")
                if (currentState != PeerTransportState.DIRECT) {
                    val retryable = code !in NON_RETRYABLE_SIGNALING_ERRORS
                    if (!retryable) signaling.suspend()
                    if (retryable) failPeer(detail) else failPeer(detail, retryable = false)
                }
            }
            else -> rejectSignalingMessage("The signaling service sent an unsupported message")
        }
    }

    private fun rejectSignalingMessage(message: String) {
        signaling.suspend()
        failPeer(message, retryable = false)
    }

    private fun handleOffer(message: JSONObject) {
        val generation = message.opt("generation") as? String ?: ""
        val attemptId = message.opt("attemptId") as? String ?: ""
        val description = message.optJSONObject("description")
        val descriptionType = description?.opt("type") as? String
        val sdp = description?.opt("sdp") as? String ?: ""
        if (!GENERATION_PATTERN.matches(generation)
            || !ATTEMPT_PATTERN.matches(attemptId)
            || descriptionType != "offer"
            || sdp.isBlank()
            || sdp.toByteArray(StandardCharsets.UTF_8).size > MAX_SIGNALING_TEXT_BYTES
        ) {
            rejectSignalingMessage("The TV received an invalid direct connection offer")
            return
        }
        if (authenticatedGeneration != null && authenticatedGeneration != generation) {
            setError("A dashboard is already connected to this TV")
            return
        }
        if (currentGeneration != generation || hasClosedDataChannel()
            || (currentAttemptId != null && currentAttemptId != attemptId)
        ) {
            val preserveSession = authenticatedGeneration == generation
            closePeer(notify = directPresenceSent && !preserveSession, releaseSession = !preserveSession)
            currentGeneration = generation
        }
        currentAttemptId = attemptId
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
                createAnswer(connection, generation, attemptId)
            }

            override fun onCreateFailure(error: String) = Unit

            override fun onSetFailure(error: String) = postControl {
                if (peer === connection) failPeer("The TV could not apply the browser connection offer: $error")
            }
        }, offer)
    }

    private fun createAnswer(connection: PeerConnection, generation: String, attemptId: String) {
        connection.createAnswer(object : SdpObserver {
            override fun onCreateSuccess(description: SessionDescription) {
                if (description.type != SessionDescription.Type.ANSWER) return
                connection.setLocalDescription(object : SdpObserver {
                    override fun onCreateSuccess(description: SessionDescription) = Unit

                    override fun onSetSuccess() = postControl {
                        if (peer !== connection || currentGeneration != generation) return@postControl
                        val sent = signaling.send(JSONObject().apply {
                            put("v", 1)
                            put("type", "signal.answer")
                            put("generation", generation)
                            put("attemptId", attemptId)
                            put("description", JSONObject().put("type", "answer").put("sdp", description.description))
                        })
                        if (!sent) failPeer("The signaling service is unavailable while connecting")
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
        val generation = message.opt("generation") as? String ?: return
        val attemptId = message.opt("attemptId") as? String ?: return
        if (generation.isBlank() || generation != currentGeneration || attemptId != currentAttemptId) return
        val value = message.optJSONObject("candidate") ?: run {
            rejectSignalingMessage("The signaling service sent an invalid ICE candidate")
            return
        }
        val sdpMid = when (val raw = value.opt("sdpMid")) {
            null,
            JSONObject.NULL,
            -> null
            is String -> raw.takeIf { it.length <= 128 } ?: run {
                rejectSignalingMessage("The signaling service sent an invalid ICE candidate")
                return
            }
            else -> {
                rejectSignalingMessage("The signaling service sent an invalid ICE candidate")
                return
            }
        }
        val sdpMLineIndex = (value.opt("sdpMLineIndex") as? Number)?.toDouble()?.let { number ->
            if (!number.isFinite() || number < 0.0 || number > 32.0 || number % 1.0 != 0.0) null else number.toInt()
        } ?: run {
            rejectSignalingMessage("The signaling service sent an invalid ICE candidate")
            return
        }
        val sdp = value.opt("candidate") as? String ?: run {
            rejectSignalingMessage("The signaling service sent an invalid ICE candidate")
            return
        }
        val candidate = IceCandidate(
            sdpMid,
            sdpMLineIndex,
            sdp,
        )
        if (candidate.sdp.isBlank()
            || candidate.sdp.toByteArray(StandardCharsets.UTF_8).size > MAX_SIGNALING_TEXT_BYTES
            || candidate.sdpMLineIndex !in 0..32
            || candidate.sdpMid?.length?.let { it > 128 } == true
        ) {
            rejectSignalingMessage("The signaling service sent an invalid ICE candidate")
            return
        }
        val connection = peer ?: return
        if (remoteDescriptionSet) {
            connection.addIceCandidate(candidate)
        } else if (pendingCandidates.size < MAX_PENDING_CANDIDATES) {
            pendingCandidates.addLast(candidate)
        } else {
            failPeer("The direct connection sent too many ICE candidates")
        }
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
                        if (peer === createdConnection && currentGeneration == generation) {
                            sendCandidate(candidate, generation, currentAttemptId ?: return@postControl)
                        }
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
        override fun onBufferedAmountChange(previousAmount: Long) {
            if (!binary && peer === owner) {
                postControl(priority = true) {
                    if (peer === owner && control === channel) flushPendingControl()
                }
            }
        }

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
            val frameBytes = buffer.data.remaining()
            val maxBytes = if (binary) MAX_CAPTURE_FRAME_BYTES else MAX_CONTROL_BYTES
            if (frameBytes > maxBytes) {
                postControl {
                    setError(if (binary) {
                        "The capture frame exceeds the direct transport limit"
                    } else {
                        "The direct control message exceeds the size limit"
                    })
                }
                return
            }
            val bytes = try {
                val copy = ByteArray(frameBytes)
                buffer.data.get(copy)
                copy
            } catch (error: Throwable) {
                Log.w(TAG, "Unable to copy DataChannel message", error)
                return
            }
            synchronized(this@WebRtcPeerTransport) { bytesReceived += bytes.size }
            lastPeerTrafficAt = System.currentTimeMillis()
            if (binary) {
                val generation = currentGeneration
                if (generation == null) return
                try {
                    captureExecutor.execute { handleCaptureData(owner, generation, bytes) }
                } catch (_: RejectedExecutionException) {
                    postControl(priority = true) {
                        if (peer === owner && currentGeneration == generation) {
                            commandHandler.onCaptureDataRejected(
                                generation,
                                bytes,
                                "The TV capture queue is full. Retry this measurement without moving the phone.",
                            )
                        }
                    }
                }
            } else {
                postControl(priority = isPriorityControl(bytes)) {
                    handleControlMessage(owner, bytes)
                }
            }
        }
    }

    private fun handleChannelState(channel: DataChannel) {
        if (channel.state() == DataChannel.State.CLOSING || channel.state() == DataChannel.State.CLOSED) {
            if (currentState != PeerTransportState.RECONNECTING) {
                reconnectCount++
                if (directPresenceSent) {
                    listener?.onPeerPresence(false, currentGeneration)
                    directPresenceSent = false
                }
                setState(PeerTransportState.RECONNECTING)
                if (signalingShouldRun()) signaling.reconnect() else signaling.suspend()
            }
            schedulePeerClose()
        } else {
            if (channel === control) flushPendingControl()
            maybeDirect()
        }
    }

    private fun hasClosedDataChannel(): Boolean = listOf(control, capture).any {
        it?.state() == DataChannel.State.CLOSING || it?.state() == DataChannel.State.CLOSED
    }

    private fun handleCaptureData(owner: PeerConnection, generation: String, bytes: ByteArray) {
        if (peer !== owner || currentGeneration != generation) return
        try {
            commandHandler.onCaptureData(generation, bytes)
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
            val text = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
            JSONObject(text)
        } catch (error: Throwable) {
            setError("The TV received invalid direct control data")
            return
        }
        if ((value.opt("kind") as? String) == "sweetspot.transport") {
            handleCapability(value)
            return
        }
        val generation = currentGeneration ?: return
        if (value.opt("transportSessionId") as? String != generation) return
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
        commandHandler.onCommand(generation, type, payload) { replyType, replyPayload ->
            postControl {
                if (peer !== owner || currentGeneration != generation) return@postControl
                sendControl(envelope(replyType, replyPayload, id))
            }
        }
    }

    private fun handleCapability(value: JSONObject) {
        val generation = currentGeneration ?: return
        if ((value.opt("sessionId") as? String) != generation) return
        val type = value.opt("type") as? String
        if (type != "hello" && type != "ready") {
            signaling.suspend()
            failPeer("The dashboard sent an invalid direct transport handshake", retryable = false)
            return
        }
        val capabilities = value.optJSONObject("capabilities") ?: run {
            signaling.suspend()
            failPeer("The dashboard sent an incomplete direct transport handshake", retryable = false)
            return
        }
        val channels = capabilities.optJSONArray("channels")
        if (!exactInt(capabilities.opt("protocolVersion"), 1)
            || !exactInt(capabilities.opt("transportVersion"), 1)
            || !exactInt(capabilities.opt("captureStreamVersion"), 1)
            || channels?.let {
                it.length() == 2
                    && it.opt(0) as? String == CONTROL_CHANNEL
                    && it.opt(1) as? String == CAPTURE_CHANNEL
            } != true
            || !exactInt(capabilities.opt("maxCaptureChunkBytes"), 16 * 1024)
        ) {
            signaling.suspend()
            failPeer("The dashboard and TV do not support the same direct transport", retryable = false)
            return
        }
        remoteReady = true
        if (type == "hello") sendCapability("ready")
        maybeDirect()
    }

    private fun exactInt(value: Any?, expected: Int): Boolean {
        val number = value as? Number ?: return false
        val asDouble = number.toDouble()
        return asDouble.isFinite() && asDouble == expected.toDouble()
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
            if (!pairingSessionConnected(generation)) {
                failPeer("A dashboard is already connected to this TV")
                closePeer(notify = false, releaseSession = false)
                return
            }
            authenticatedGeneration = generation
            directPresenceSent = true
            listener?.onPeerPresence(true, generation)
        }
        requestStats(peer ?: return)
        signaling.suspend()
    }

    private fun sendCandidate(candidate: IceCandidate, generation: String, attemptId: String) {
        val sent = signaling.send(JSONObject().apply {
            put("v", 1)
            put("type", "signal.ice")
            put("generation", generation)
            put("attemptId", attemptId)
            put("candidate", JSONObject().apply {
                put("candidate", candidate.sdp)
                put("sdpMid", candidate.sdpMid ?: JSONObject.NULL)
                put("sdpMLineIndex", candidate.sdpMLineIndex)
            })
        })
        if (!sent && currentState != PeerTransportState.DIRECT) {
            failPeer("The signaling service is unavailable while connecting")
        }
    }

    private fun sendRawControl(text: String, queueIfBlocked: Boolean = false, priority: Boolean = false): Boolean {
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        if (bytes.size > MAX_CONTROL_BYTES) return false
        synchronized(controlDispatchLock) {
            if (queueIfBlocked) flushPendingControl()
            if (sendControlBytes(bytes)) return true
            val channel = control
            if (!queueIfBlocked || channel == null || channel.state() != DataChannel.State.OPEN) return false
            if (pendingControl.enqueue(bytes, isPriority = priority)) {
                scheduleControlFlush()
                return true
            }
            setError("The TV direct control buffer is full. Retry the operation.")
            return false
        }
    }

    private fun sendControl(envelope: JSONObject): Boolean {
        val text = envelope.toString()
        return sendRawControl(text, queueIfBlocked = true, priority = isPriorityControl(text.toByteArray(StandardCharsets.UTF_8)))
    }

    private fun isPriorityControl(bytes: ByteArray): Boolean = try {
        val value = JSONObject(String(bytes, StandardCharsets.UTF_8))
        when (value.opt("type") as? String) {
            "calibration.job.cancel", "calibration.job.discard", "pong" -> true
            else -> false
        }
    } catch (_: Throwable) {
        false
    }

    private fun sendControlBytes(bytes: ByteArray): Boolean {
        val channel = control ?: return false
        if (channel.state() != DataChannel.State.OPEN) return false
        if (channel.bufferedAmount() > MAX_CONTROL_BUFFERED_BYTES - bytes.size) return false
        val sent = channel.send(DataChannel.Buffer(ByteBuffer.wrap(bytes), false))
        if (sent) {
            bytesSent += bytes.size
            lastPeerTrafficAt = System.currentTimeMillis()
        }
        return sent
    }

    private fun flushPendingControl() {
        while (true) {
            val bytes = pendingControl.peek() ?: break
            if (!sendControlBytes(bytes)) {
                scheduleControlFlush()
                return
            }
            pendingControl.removeFirst()
        }
        controlFlushTask?.cancel(false)
        controlFlushTask = null
    }

    private fun scheduleControlFlush() {
        if (controlFlushTask != null || !running.get() || pendingControl.isEmpty) return
        controlFlushTask = try {
            controlExecutor.schedule({
                synchronized(controlDispatchLock) {
                    controlFlushTask = null
                    flushPendingControl()
                }
            }, CONTROL_FLUSH_DELAY_MS, TimeUnit.MILLISECONDS)
        } catch (_: RejectedExecutionException) {
            null
        }
    }

    private fun envelope(type: String, payload: JSONObject, replyTo: String? = null): JSONObject = JSONObject().apply {
        val id = "dev_${System.currentTimeMillis().toString(36)}_${messageCounter.getAndIncrement().toString(36)}"
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
                if (directPresenceSent) {
                    listener?.onPeerPresence(false, currentGeneration)
                    directPresenceSent = false
                }
                setState(PeerTransportState.RECONNECTING)
                if (signalingShouldRun()) signaling.reconnect() else signaling.suspend()
                schedulePeerClose()
            }
            PeerConnection.PeerConnectionState.CLOSED -> {
                closePeer(notify = true)
                if (running.get()) {
                    if (pairingVisible) {
                        signaling.start()
                        setState(PeerTransportState.PAIRING)
                    } else {
                        signaling.suspend()
                        currentGeneration = null
                        signaling.resetGeneration()
                        setState(PeerTransportState.IDLE)
                    }
                }
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

    private fun failPeer(message: String, retryable: Boolean = true) {
        setError(message)
        if (!running.get()) {
            setState(PeerTransportState.FAILED)
            return
        }
        if (!retryable) {
            closePeer(notify = true, releaseSession = true)
            setState(PeerTransportState.FAILED)
            return
        }
        reconnectCount++
        val generation = currentGeneration
        if (directPresenceSent) {
            directPresenceSent = false
            listener?.onPeerPresence(false, generation)
        }
        closePeer(notify = false, releaseSession = false)
        setState(PeerTransportState.RECONNECTING)
        if (signalingShouldRun()) signaling.reconnect() else signaling.suspend()
        if (generation != null) schedulePeerClose()
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
        signalingRoundTripMs = signalingRoundTripMs,
        lastControlMessageAt = lastControlMessageAt,
        lastPeerTrafficAt = lastPeerTrafficAt,
        lastError = lastError,
    )

    @Synchronized
    private fun closePeer(notify: Boolean, releaseSession: Boolean = true) {
        reconnectCloseTask?.cancel(false)
        reconnectCloseTask = null
        synchronized(controlDispatchLock) {
            controlFlushTask?.cancel(false)
            controlFlushTask = null
            pendingControl.clear()
        }
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
        currentAttemptId = null
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
        if (releaseSession) {
            authenticatedGeneration = null
            pairingSessionDisconnected(generation)
        }
        if (wasPresent && notify) listener?.onPeerPresence(false, generation)
    }

    private fun schedulePeerClose() {
        if (reconnectCloseTask != null || currentGeneration == null) return
        val generation = currentGeneration ?: return
        reconnectCloseTask = controlExecutor.schedule({
            reconnectCloseTask = null
            if (currentGeneration == generation && !directPresenceSent) {
                closePeer(notify = false)
                if (running.get()) {
                    if (pairingVisible) {
                        signaling.start()
                        setState(PeerTransportState.PAIRING)
                    } else {
                        currentGeneration = null
                        signaling.resetGeneration()
                        signaling.suspend()
                        setState(PeerTransportState.IDLE)
                    }
                }
            }
        }, RECONNECT_CLOSE_GRACE_MS, TimeUnit.MILLISECONDS)
    }

    private fun disposeChannel(channel: DataChannel) {
        try { channel.unregisterObserver() } catch (_: Throwable) {}
        try { channel.close() } catch (_: Throwable) {}
        try { channel.dispose() } catch (_: Throwable) {}
    }

    private fun pairingSessionConnected(generation: String): Boolean {
        return try { onSessionConnected(generation) } catch (_: Throwable) { false }
    }

    private fun pairingSessionDisconnected(generation: String?) {
        if (generation == null) return
        try { onSessionDisconnected(generation) } catch (_: Throwable) {}
    }
}
