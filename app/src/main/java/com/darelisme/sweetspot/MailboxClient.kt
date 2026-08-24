package com.darelisme.sweetspot

import android.util.Log
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject

/**
 * Device-side mailbox client. The TV keeps one room WebSocket while it is
 * running.
 *
 * OkHttp protocol pings keep the connection healthy without sending an
 * application heartbeat through the Durable Object.
 */
class MailboxClient(
    private val roomProvider: () -> String,
    private val snapshotProvider: () -> JSONObject,
    /** Runs effect-chain diagnostics on a background thread; called for 'diagnostics.effects'. */
    private val effectsDiagnosticsProvider: () -> JSONObject,
    /** Dispatches control commands; replies are posted back to the room. */
    private val commandHandler: CommandHandler,
) {

    /** Handles dashboard control commands. All methods run on the mailbox worker thread. */
    interface CommandHandler {
        /** Handle a non-query command; return true if a state.snapshot should be posted back. */
        fun onCommand(type: String, payload: JSONObject, replyTo: (String, JSONObject) -> Unit)
    }

    companion object {
        private const val TAG = "SweetSpotMailbox"
        private const val WS_PING_INTERVAL_SECONDS = 20L
        private const val WS_RECONNECT_MIN_MS = 1_000L
        private const val WS_RECONNECT_MAX_MS = 30_000L
    }

    interface Listener {
        fun onDeviceOnline(online: Boolean)

        /** True while a dashboard is actively connected or posting to the room. */
        fun onClientPresence(present: Boolean)
    }

    @Volatile
    var listener: Listener? = null

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .pingInterval(WS_PING_INTERVAL_SECONDS, TimeUnit.SECONDS)
        .build()

    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private val running = AtomicBoolean(false)
    private val reconnectScheduled = AtomicBoolean(false)
    private val responseCounter = AtomicLong(0)

    @Volatile
    private var socket: WebSocket? = null
    private var reconnectDelayMs = WS_RECONNECT_MIN_MS

    fun start() {
        if (!running.compareAndSet(false, true)) return
        executor.execute { connectWebSocket() }
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        socket?.close(1000, "mailbox stopped")
        socket = null
        executor.shutdownNow()
    }

    private fun connectWebSocket() {
        if (!running.get() || socket != null) return
        val room = PairCodeManager.normalize(roomProvider())
        if (room.isBlank()) {
            scheduleReconnect()
            return
        }
        val request = Request.Builder()
            .url(socketUrl(room))
            .build()
        try {
            val candidate = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    if (!running.get()) {
                        webSocket.close(1000, "mailbox stopped")
                        return
                    }
                    socket = webSocket
                    reconnectDelayMs = WS_RECONNECT_MIN_MS
                    listener?.onDeviceOnline(true)
                    Log.i(TAG, "Connected to room WebSocket (device online)")
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    executor.execute {
                        if (isCurrentSocket(webSocket)) handleSocketMessage(text)
                    }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    executor.execute { handleSocketClosed(webSocket, "closed $code: $reason") }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    executor.execute { handleSocketClosed(webSocket, "failure: ${t.message}") }
                }
            })
            socket = candidate
        } catch (e: Exception) {
            Log.w(TAG, "WebSocket connect failed: ${e.message}")
            scheduleReconnect()
        }
    }

    private fun socketUrl(room: String): String {
        val base = Config.MAILBOX_URL.trimEnd('/')
        val wsBase = when {
            base.startsWith("https://") -> "wss://${base.removePrefix("https://")}"
            base.startsWith("http://") -> "ws://${base.removePrefix("http://")}"
            else -> base
        }
        return "$wsBase/api/room/$room/ws?role=device"
    }

    private fun isCurrentSocket(candidate: WebSocket): Boolean = socket === candidate

    private fun handleSocketClosed(closed: WebSocket, detail: String) {
        if (!isCurrentSocket(closed)) return
        socket = null
        if (!running.get()) return
        listener?.onDeviceOnline(false)
        Log.w(TAG, "Room WebSocket $detail; reconnecting")
        scheduleReconnect()
    }

    private fun scheduleReconnect() {
        if (!running.get()) return
        if (!reconnectScheduled.compareAndSet(false, true)) return
        val delay = reconnectDelayMs
        reconnectDelayMs = (reconnectDelayMs * 2).coerceAtMost(WS_RECONNECT_MAX_MS)
        executor.schedule({
            reconnectScheduled.set(false)
            connectWebSocket()
        }, delay, TimeUnit.MILLISECONDS)
    }

    private fun handleSocketMessage(text: String) {
        val json = try {
            JSONObject(text)
        } catch (e: Exception) {
            Log.w(TAG, "Ignoring malformed room WebSocket message: ${e.message}")
            return
        }
        when (json.optString("kind")) {
            "room.ready" -> {
                val commands = json.optJSONArray("messages") ?: JSONArray()
                for (i in 0 until commands.length()) handleCommand(commands.getJSONObject(i))
                listener?.onDeviceOnline(true)
            }
            "room.clientPresence" -> {
                listener?.onClientPresence(json.optBoolean("clientOnline", false))
            }
            "room.error" -> Log.w(TAG, "Room WebSocket error: ${json.optString("message")}")
            else -> handleCommand(json)
        }
    }

    private fun handleCommand(env: JSONObject) {
        val expiresAt = if (env.has("expiresAt")) env.optDouble("expiresAt", Double.NaN) else Double.NaN
        if (expiresAt.isFinite() && expiresAt <= System.currentTimeMillis()) {
            Log.i(TAG, "Ignoring expired room command ${env.optString("type")}")
            return
        }
        val type = env.optString("type")
        val payload = env.optJSONObject("payload") ?: JSONObject()
        when (type) {
            "ping" -> postToDevice(env, "pong")
            "state.get" -> postToDevice(env, "state.snapshot", snapshotProvider())
            "diagnostics.effects" -> {
                try {
                    postToDevice(env, "diagnostics.effects", effectsDiagnosticsProvider())
                } catch (e: Exception) {
                    Log.e(TAG, "diagnostics.effects failed", e)
                    postToDevice(env, "diagnostics.effects", JSONObject().put("error", "${e.javaClass.simpleName}: ${e.message}"))
                }
            }
            else -> {
                try {
                    commandHandler.onCommand(type, payload) { replyType, replyPayload ->
                        postToDevice(env, replyType, replyPayload)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "command $type failed", e)
                    postToDevice(
                        env, "state.snapshot", snapshotProvider(),
                        JSONObject().put("ok", false).put("error", "${e.javaClass.simpleName}: ${e.message}")
                    )
                }
            }
        }
    }

    private fun postToDevice(requestEnv: JSONObject, type: String, payload: JSONObject = JSONObject(), extra: JSONObject? = null) {
        val out = JSONObject().apply {
            put("v", 1)
            put("id", "dev_${System.currentTimeMillis().toString(36)}_${responseCounter.incrementAndGet().toString(36)}")
            put("type", type)
            put("ts", System.currentTimeMillis())
            put("replyTo", requestEnv.optString("id"))
            put("payload", payload)
            if (extra != null) {
                for (key in extra.keys()) payload.put(key, extra.get(key))
            }
        }
        val activeSocket = socket
        if (activeSocket?.send(out.toString()) != true) {
            Log.w(TAG, "post $type skipped because the room WebSocket is unavailable")
        }
    }
}
