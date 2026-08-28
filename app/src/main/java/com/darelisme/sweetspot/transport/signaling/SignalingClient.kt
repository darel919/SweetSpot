package com.darelisme.sweetspot.transport.signaling

import com.darelisme.sweetspot.transport.Config
import com.darelisme.sweetspot.pairing.PairingSessionManager
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** WebSocket is used only for short SDP/ICE rendezvous messages. */
class SignalingClient(
    private val role: String,
    private val sessionProvider: () -> PairingSessionManager.Session,
    private val listener: Listener,
) {
    interface Listener {
        fun onConnected()

        fun onMessage(message: JSONObject)

        fun onClosed(reason: String)
    }

    companion object {
        private const val RECONNECT_MIN_MS = 1_000L
        private const val RECONNECT_MAX_MS = 30_000L
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "sweetspot-signaling").apply { isDaemon = true }
    }
    private val running = AtomicBoolean(false)
    @Volatile
    private var socket: WebSocket? = null
    private var reconnectDelayMs = RECONNECT_MIN_MS
    private var reconnectScheduled = false
    private var reconnectTask: ScheduledFuture<*>? = null
    @Volatile
    private var generation: String? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        post(::connect)
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        reconnectTask?.cancel(false)
        reconnectTask = null
        reconnectScheduled = false
        socket?.close(1000, "signaling stopped")
        socket = null
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
            socket?.close(1000, "signaling reconnect")
            socket = null
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
            val current = socket
            socket = null
            current?.close(1000, "signaling suspended")
        }
    }

    fun resetGeneration() {
        generation = null
    }

    fun send(message: JSONObject): Boolean = socket?.send(message.toString()) == true

    private fun connect() {
        if (!running.get() || socket != null) return
        val session = try {
            sessionProvider()
        } catch (error: Throwable) {
            scheduleReconnect(error.message ?: "Pairing session is unavailable")
            return
        }
        val nextGeneration = generation ?: newGeneration()
        generation = nextGeneration
        val request = Request.Builder().url(socketUrl(session)).build()
        try {
            val candidate = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    if (!running.get()) {
                        webSocket.close(1000, "signaling stopped")
                        return
                    }
                    socket = webSocket
                    reconnectDelayMs = RECONNECT_MIN_MS
                    send(JSONObject().apply {
                        put("v", 1)
                        put("type", "signal.hello")
                        put("generation", nextGeneration)
                    })
                    listener.onConnected()
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (socket !== webSocket) return
                    post {
                        if (socket === webSocket) {
                            try {
                                listener.onMessage(JSONObject(text))
                            } catch (error: Throwable) {
                                handleClosed(webSocket, "Invalid signaling message: ${error.message}")
                            }
                        }
                    }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    post { handleClosed(webSocket, "closed $code: $reason") }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    post { handleClosed(webSocket, "failure: ${t.message ?: "unknown"}") }
                }
            })
            socket = candidate
        } catch (error: Throwable) {
            scheduleReconnect(error.message ?: "Signaling connection failed")
        }
    }

    private fun handleClosed(closed: WebSocket, reason: String) {
        if (socket !== closed) return
        socket = null
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
        return "$wsBase/api/signaling/${session.rendezvousId}/ws?role=$role&secret=${session.pairSecret}"
    }

    private fun newGeneration(): String = buildString {
        append(System.currentTimeMillis().toString(36))
        append('_')
        append(java.util.UUID.randomUUID().toString().replace("-", ""))
    }
}
