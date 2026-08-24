package com.darelisme.sweetspot

import android.util.Log
import java.io.ByteArrayOutputStream
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * Device-side mailbox client. The TV keeps one room WebSocket while it is
 * running. HTTP long-polling remains as a rolling-upgrade fallback for an old
 * Worker that does not identify device WebSocket connections yet.
 *
 * OkHttp protocol pings keep the connection healthy without sending an
 * application heartbeat through the Durable Object.
 */
class MailboxClient(
    private val roomProvider: () -> String,
    private val snapshotProvider: () -> JSONObject,
    /** Runs effect-chain diagnostics on a background thread; called for 'diagnostics.effects'. */
    private val effectsDiagnosticsProvider: (() -> JSONObject)? = null,
    /** Dispatches control commands; replies are posted back to the room. Nullable = legacy. */
    private val commandHandler: CommandHandler? = null,
) {

    /** Handles dashboard control commands. All methods run on the mailbox worker thread. */
    interface CommandHandler {
        /** Handle a non-query command; return true if a state.snapshot should be posted back. */
        fun onCommand(type: String, payload: JSONObject, replyTo: (String, JSONObject) -> Unit)
    }

    companion object {
        private const val TAG = "SweetSpotMailbox"
        private const val POLL_WAIT_SECONDS = 9
        private const val ERROR_BACKOFF_MS = 3_000L
        private const val MAX_COMMAND_RESPONSE_BYTES = 1 * 1024 * 1024
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
        .readTimeout((POLL_WAIT_SECONDS + 6) * 1000L, TimeUnit.MILLISECONDS)
        .pingInterval(WS_PING_INTERVAL_SECONDS, TimeUnit.SECONDS)
        .build()

    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private val running = AtomicBoolean(false)
    private val fallbackStarted = AtomicBoolean(false)
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
        if (!running.get() || fallbackStarted.get() || socket != null) return
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
                    if (!running.get() || fallbackStarted.get()) {
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
                        if (isCurrentSocket(webSocket)) handleSocketMessage(text, webSocket)
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
        if (!running.get() || fallbackStarted.get()) return
        listener?.onDeviceOnline(false)
        Log.w(TAG, "Room WebSocket $detail; reconnecting")
        scheduleReconnect()
    }

    private fun scheduleReconnect() {
        if (!running.get() || fallbackStarted.get()) return
        if (!reconnectScheduled.compareAndSet(false, true)) return
        val delay = reconnectDelayMs
        reconnectDelayMs = (reconnectDelayMs * 2).coerceAtMost(WS_RECONNECT_MAX_MS)
        executor.schedule({
            reconnectScheduled.set(false)
            connectWebSocket()
        }, delay, TimeUnit.MILLISECONDS)
    }

    private fun handleSocketMessage(text: String, webSocket: WebSocket) {
        val json = try {
            JSONObject(text)
        } catch (e: Exception) {
            Log.w(TAG, "Ignoring malformed room WebSocket message: ${e.message}")
            return
        }
        when (json.optString("kind")) {
            "room.ready" -> {
                // The old Worker sends room.ready without a role. Fall back
                // once, then stay on HTTP until that deployment is upgraded.
                if (json.optString("role") != "device") {
                    startHttpFallback(webSocket)
                    return
                }
                val commands = json.optJSONArray("messages") ?: JSONArray()
                for (i in 0 until commands.length()) handleCommand(commands.getJSONObject(i))
                listener?.onDeviceOnline(true)
            }
            "room.clientPresence" -> {
                listener?.onClientPresence(json.optBoolean("clientOnline", false))
            }
            "room.presence" -> {
                // Kept for compatibility with an older server message shape.
                listener?.onClientPresence(json.optBoolean("deviceOnline", false))
            }
            "room.error" -> Log.w(TAG, "Room WebSocket error: ${json.optString("message")}")
            else -> handleCommand(json)
        }
    }

    private fun startHttpFallback(webSocket: WebSocket) {
        if (!fallbackStarted.compareAndSet(false, true)) return
        if (isCurrentSocket(webSocket)) socket = null
        webSocket.close(1000, "use legacy HTTP mailbox")
        listener?.onDeviceOnline(false)
        Log.i(TAG, "Room Worker lacks device WebSocket support; using HTTP fallback")
        executor.execute { pollLoop() }
    }

    private fun pollLoop() {
        var wasOnline = false
        var wasClientPresent = false
        while (running.get()) {
            val room = PairCodeManager.normalize(roomProvider())
            try {
                // /commands also refreshes device presence. Do not make a
                // separate /register request before every long poll.
                val req = Request.Builder()
                    .url("${Config.MAILBOX_URL}/api/room/$room/commands?wait=$POLL_WAIT_SECONDS")
                    .get()
                    .build()

                client.newCall(req).execute().use { res ->
                    if (!res.isSuccessful) throw IllegalStateException("HTTP ${res.code}")
                    val body = readBodyBounded(res.body)
                    val json = JSONObject(body)
                    val commands = json.optJSONArray("commands") ?: JSONArray()
                    for (i in 0 until commands.length()) handleCommand(commands.getJSONObject(i))
                    val clientPresent = json.optBoolean("clientOnline", false)
                    if (clientPresent != wasClientPresent) {
                        wasClientPresent = clientPresent
                        listener?.onClientPresence(clientPresent)
                    }
                }

                if (!wasOnline) {
                    wasOnline = true
                    listener?.onDeviceOnline(true)
                    Log.i(TAG, "Registered in room (HTTP fallback device online)")
                }
            } catch (e: Exception) {
                if (running.get()) {
                    if (wasOnline) {
                        wasOnline = false
                        listener?.onDeviceOnline(false)
                    }
                    Log.w(TAG, "Poll failed: ${e.message}; retrying in ${ERROR_BACKOFF_MS}ms")
                    sleep(ERROR_BACKOFF_MS)
                }
            }
        }
    }

    /**
     * The relay bounds individual envelopes, but a response may contain a
     * batch. Keep a malformed or unexpectedly large batch from allocating an
     * unbounded String before it reaches JSONObject.
     */
    private fun readBodyBounded(body: okhttp3.ResponseBody?): String {
        if (body == null) return "{}"
        val declaredLength = body.contentLength()
        if (declaredLength > MAX_COMMAND_RESPONSE_BYTES.toLong()) {
            throw IllegalStateException("Mailbox response exceeds ${MAX_COMMAND_RESPONSE_BYTES} bytes")
        }
        val initialCapacity = if (declaredLength >= 0L && declaredLength <= MAX_COMMAND_RESPONSE_BYTES.toLong()) {
            declaredLength.toInt()
        } else {
            8 * 1024
        }
        val output = ByteArrayOutputStream(initialCapacity)
        body.byteStream().use { input ->
            val buffer = ByteArray(8 * 1024)
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > MAX_COMMAND_RESPONSE_BYTES) {
                    throw IllegalStateException("Mailbox response exceeds ${MAX_COMMAND_RESPONSE_BYTES} bytes")
                }
                output.write(buffer, 0, read)
            }
        }
        return output.toString(Charsets.UTF_8.name())
    }

    private fun handleCommand(env: JSONObject) {
        val type = env.optString("type")
        val payload = env.optJSONObject("payload") ?: JSONObject()
        when (type) {
            "ping" -> postToDevice(env, "pong")
            "state.get" -> postToDevice(env, "state.snapshot", snapshotProvider())
            "diagnostics.effects" -> {
                val provider = effectsDiagnosticsProvider
                if (provider == null) {
                    postToDevice(env, "diagnostics.effects", JSONObject().put("error", "unavailable"))
                } else {
                    try {
                        postToDevice(env, "diagnostics.effects", provider())
                    } catch (e: Exception) {
                        Log.e(TAG, "diagnostics.effects failed", e)
                        postToDevice(env, "diagnostics.effects", JSONObject().put("error", "${e.javaClass.simpleName}: ${e.message}"))
                    }
                }
            }
            else -> {
                val handler = commandHandler
                if (handler != null) {
                    try {
                        handler.onCommand(type, payload) { replyType, replyPayload ->
                            postToDevice(env, replyType, replyPayload)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "command $type failed", e)
                        postToDevice(
                            env, "state.snapshot", snapshotProvider(),
                            JSONObject().put("ok", false).put("error", "${e.javaClass.simpleName}: ${e.message}")
                        )
                    }
                } else {
                    Log.d(TAG, "Ignoring command type $type")
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
        if (activeSocket != null && activeSocket.send(out.toString())) return

        val room = PairCodeManager.normalize(roomProvider())
        val req = Request.Builder()
            .url("${Config.MAILBOX_URL}/api/room/$room/device")
            .post(out.toString().toRequestBody("application/json".toMediaTypeOrNull()))
            .build()
        client.newCall(req).execute().use { res ->
            if (!res.isSuccessful) Log.w(TAG, "post $type failed: HTTP ${res.code}")
        }
    }

    private fun sleep(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {}
    }
}
