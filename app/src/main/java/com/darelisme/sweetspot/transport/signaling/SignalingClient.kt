package com.darelisme.sweetspot.transport.signaling

import com.darelisme.sweetspot.transport.Config
import com.darelisme.sweetspot.pairing.PairingSessionManager
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** WebSocket is used only for short SDP/ICE rendezvous messages. */
class SignalingClient(
    private val role: String,
    private val sessionProvider: () -> PairingSessionManager.Session,
    private val listener: Listener,
) {
    interface Listener {
        fun onConnected(roundTripMs: Long?)

        fun onMessage(message: JSONObject)

        fun onClosed(reason: String)
    }

    companion object {
        private const val MAX_MESSAGE_BYTES = 64 * 1024
        private const val RECONNECT_MIN_MS = 1_000L
        private const val RECONNECT_MAX_MS = 30_000L
        private const val SIGNALING_SUBPROTOCOL = "sweetspot.v1"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "sweetspot-signaling").apply { isDaemon = true }
    }
    private val running = AtomicBoolean(false)
    private val socketLock = Any()
    @Volatile
    private var socket: WebSocket? = null
    @Volatile
    private var socketToken = 0L
    private val connectionToken = AtomicLong(0L)
    private var reconnectDelayMs = RECONNECT_MIN_MS
    private var reconnectScheduled = false
    private var reconnectTask: ScheduledFuture<*>? = null
    private var connectStartedAtMs: Long? = null
    @Volatile
    private var generation: String? = null

    fun start() {
        if (running.compareAndSet(false, true)) {
            post(::connect)
            return
        }
        post {
            if (socket == null && !reconnectScheduled) {
                reconnectDelayMs = RECONNECT_MIN_MS
                connect()
            }
        }
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        connectionToken.incrementAndGet()
        reconnectTask?.cancel(false)
        reconnectTask = null
        reconnectScheduled = false
        val current = synchronized(socketLock) {
            val value = socket
            socket = null
            socketToken = 0L
            value
        }
        current?.close(1000, "signaling stopped")
        executor.shutdownNow()
        client.dispatcher.cancelAll()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    fun reconnect() {
        if (!running.get()) return
        post {
            reconnectTask?.cancel(false)
            reconnectTask = null
            reconnectScheduled = false
            connectionToken.incrementAndGet()
            val current = synchronized(socketLock) {
                val value = socket
                socket = null
                socketToken = 0L
                value
            }
            current?.close(1000, "signaling reconnect")
            reconnectDelayMs = RECONNECT_MIN_MS
            connect()
        }
    }

    fun setGeneration(value: String) {
        if (value.isBlank()) return
        generation = value
    }

    /** Temporarily close signaling after direct peer setup without ending the session. */
    fun suspend() {
        if (!running.get()) return
        post {
            reconnectTask?.cancel(false)
            reconnectTask = null
            reconnectScheduled = false
            connectionToken.incrementAndGet()
            val current = synchronized(socketLock) {
                val value = socket
                socket = null
                socketToken = 0L
                value
            }
            current?.close(1000, "signaling suspended")
        }
    }

    fun resetGeneration() {
        generation = null
    }

    fun send(message: JSONObject): Boolean {
        val text = message.toString()
        if (text.toByteArray(StandardCharsets.UTF_8).size > MAX_MESSAGE_BYTES) return false
        return socket?.send(text) == true
    }

    private fun connect() {
        if (!running.get() || socket != null) return
        val token = connectionToken.incrementAndGet()
        val session = try {
            sessionProvider()
        } catch (error: Throwable) {
            scheduleReconnect(error.message ?: "Pairing session is unavailable")
            return
        }
        val nextGeneration = generation ?: newGeneration()
        generation = nextGeneration
        connectStartedAtMs = System.currentTimeMillis()
        val request = Request.Builder()
            .url(socketUrl(session))
            .header("Sec-WebSocket-Protocol", "$SIGNALING_SUBPROTOCOL, ${session.pairSecret}")
            .build()
        try {
            val candidate = synchronized(socketLock) {
                client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    if (!isCurrent(webSocket, token)) {
                        webSocket.close(1000, "signaling stopped")
                        return
                    }
                    reconnectDelayMs = RECONNECT_MIN_MS
                    val helloSent = webSocket.send(JSONObject().apply {
                        put("v", 1)
                        put("type", "signal.hello")
                        put("generation", nextGeneration)
                    }.toString())
                    if (!helloSent) {
                        webSocket.close(1001, "signaling hello rejected")
                        post { handleClosed(webSocket, token, "Signaling hello was rejected") }
                        return
                    }
                    val roundTripMs = connectStartedAtMs?.let { (System.currentTimeMillis() - it).coerceAtLeast(0L) }
                    connectStartedAtMs = null
                    listener.onConnected(roundTripMs)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (!isCurrent(webSocket, token)) return
                    post {
                        if (isCurrent(webSocket, token)) {
                            if (text.toByteArray(StandardCharsets.UTF_8).size > MAX_MESSAGE_BYTES) {
                                handleClosed(webSocket, token, "Signaling message too large")
                                return@post
                            }
                            try {
                                listener.onMessage(JSONObject(text))
                            } catch (error: Throwable) {
                                handleClosed(webSocket, token, "Invalid signaling message: ${error.message}")
                            }
                        }
                    }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    post { handleClosed(webSocket, token, "closed $code: $reason") }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    post { handleClosed(webSocket, token, "failure: ${t.message ?: "unknown"}") }
                }
                })
            }
            if (!running.get() || connectionToken.get() != token) {
                candidate.close(1000, "stale signaling connection")
            } else {
                synchronized(socketLock) {
                    if (!running.get() || connectionToken.get() != token) {
                        candidate.close(1000, "stale signaling connection")
                    } else {
                        socketToken = token
                        socket = candidate
                    }
                }
            }
        } catch (error: Throwable) {
            scheduleReconnect(error.message ?: "Signaling connection failed")
        }
    }

    private fun isCurrent(candidate: WebSocket, token: Long): Boolean = synchronized(socketLock) {
        running.get() && socket === candidate && socketToken == token && connectionToken.get() == token
    }

    private fun handleClosed(closed: WebSocket, token: Long, reason: String) {
        val current = synchronized(socketLock) {
            if (!running.get() || socket !== closed || socketToken != token || connectionToken.get() != token) {
                false
            } else {
                socket = null
                socketToken = 0L
                true
            }
        }
        if (!current) return
        listener.onClosed(reason)
        scheduleReconnect(reason, notify = false)
    }

    private fun scheduleReconnect(reason: String, notify: Boolean = true) {
        if (!running.get() || reconnectScheduled) return
        if (notify) listener.onClosed(reason)
        reconnectScheduled = true
        val delay = reconnectDelayMs
        reconnectDelayMs = (reconnectDelayMs * 2).coerceAtMost(RECONNECT_MAX_MS)
        try {
            reconnectTask = executor.schedule({
                reconnectTask = null
                reconnectScheduled = false
                connect()
            }, delay, TimeUnit.MILLISECONDS)
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            reconnectScheduled = false
            reconnectTask = null
        }
    }

    private fun post(block: () -> Unit) {
        if (!running.get()) return
        try {
            executor.execute {
                if (running.get()) block()
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            // Teardown races with OkHttp callbacks are expected during service shutdown.
        }
    }

    private fun socketUrl(session: PairingSessionManager.Session): String {
        val base = Config.SIGNALING_URL.trimEnd('/')
        val wsBase = when {
            base.startsWith("https://") -> "wss://${base.removePrefix("https://")}"
            base.startsWith("http://") -> "ws://${base.removePrefix("http://")}"
            else -> base
        }
        return "$wsBase/api/signaling/${session.rendezvousId}/ws?role=$role"
    }

    private fun newGeneration(): String = buildString {
        append(System.currentTimeMillis().toString(36))
        append('_')
        append(java.util.UUID.randomUUID().toString().replace("-", ""))
    }
}
