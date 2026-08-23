package com.darelisme.sweetspot

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Outbound WebSocket client owned by [SweetSpotService]. The TV always dials
 * out to the hosted relay; nothing is exposed inbound.
 *
 * Wire protocol (v1, shared with sweetspot-web):
 *  - first message after connect is `session.hello` {role:"device", room}
 *  - `state.get` from clients is answered with `state.snapshot` + replyTo
 *  - `ping` is answered with `pong`
 *  - reconnect uses exponential backoff with jitter; state is re-requested
 *    from the engine on every fresh session, never assumed
 */
class RelayClient(
    private val roomProvider: () -> String,
    private val snapshotProvider: () -> JSONObject,
) {

    companion object {
        private const val TAG = "SweetSpotRelay"
        private const val PROTOCOL_VERSION = 1

        /** Production relay. Overridable for development via Config. */
        const val RELAY_URL = Config.RELAY_URL

        private const val HEARTBEAT_INTERVAL_MS = 25_000L
        private const val BACKOFF_BASE_MS = 1_000L
        private const val BACKOFF_MAX_MS = 30_000L
        const val STATE_DISCONNECTED = "disconnected"
        const val STATE_CONNECTING = "connecting"
        const val STATE_CONNECTED = "connected"
        const val STATE_WAITING = "waiting"
    }

    interface Listener {
        fun onRelayState(state: String)
    }

    @Volatile
    var listener: Listener? = null

    private val client = OkHttpClient.Builder()
        .pingInterval(0, TimeUnit.MILLISECONDS) // app-level heartbeat only
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val executor = Executors.newSingleThreadExecutor()
    private val running = AtomicBoolean(false)

    @Volatile
    private var webSocket: WebSocket? = null

    @Volatile
    private var state = STATE_DISCONNECTED

    @Volatile
    private var reconnectAttempt = 0

    private var heartbeatScheduled = false
    private var reconnectScheduled = false
    private var messageCounter = 0

    fun start() {
        if (!running.compareAndSet(false, true)) return
        setState(STATE_CONNECTING)
        executor.execute { connect() }
    }

    fun stop() {
        running.set(false)
        webSocket?.close(1000, "shutdown")
        webSocket = null
        setState(STATE_DISCONNECTED)
    }

    fun currentState(): String = state

    /** Pushes an unprompted state snapshot to everyone in the room. */
    fun sendStateSnapshot() {
        sendRaw(reply(JSONObject().put("id", "push"), "state.snapshot", snapshotProvider()))
    }

    private fun setState(s: String) {
        state = s
        listener?.onRelayState(s)
    }

    private fun connect() {
        if (!running.get()) return
        setState(if (reconnectAttempt == 0) STATE_CONNECTING else STATE_CONNECTING)
        Log.i(TAG, "Connecting to relay ${Config.RELAY_URL} (attempt ${reconnectAttempt + 1})")

        val request = Request.Builder().url(Config.RELAY_URL).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {

            override fun onOpen(ws: WebSocket, response: Response) {
                Log.i(TAG, "Relay socket open")
                reconnectAttempt = 0
                send("session.hello", JSONObject().apply {
                    put("role", "device")
                    put("room", PairCodeManager.normalize(roomProvider()))
                })
            }

            override fun onMessage(ws: WebSocket, text: String) {
                handleMessage(text)
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "Relay failure: ${t.message}")
                scheduleReconnect()
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "Relay closed ($code)")
                scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        if (!running.get()) {
            setState(STATE_DISCONNECTED)
            return
        }
        setState(STATE_DISCONNECTED)
        synchronized(this) {
            if (reconnectScheduled) return
            reconnectScheduled = true
        }
        val delay = jitteredBackoff(reconnectAttempt)
        reconnectAttempt++
        Log.i(TAG, "Reconnecting in ${delay}ms")
        executor.execute {
            synchronized(this) { reconnectScheduled = false }
            sleep(delay)
            if (running.get()) connect()
        }
    }

    private fun handleMessage(text: String) {
        val env = try {
            JSONObject(text)
        } catch (_: Exception) {
            return
        }
        when (env.optString("type")) {
            "session.welcome" -> {
                setState(STATE_WAITING)
                startHeartbeat()
                // A dashboard may already be sitting in the room.
                val peers = env.optJSONObject("payload")?.optJSONObject("peers")
                if (peers != null && peers.optInt("clients", 0) > 0) {
                    setState(STATE_CONNECTED)
                }
                Log.i(TAG, "Joined relay room; clients=${peers?.optInt("clients", 0) ?: 0}")
            }
            "session.peerJoined" -> {
                if (env.optJSONObject("payload")?.optString("role") == "client") {
                    setState(STATE_CONNECTED)
                    Log.i(TAG, "Dashboard joined the room")
                }
            }
            "session.peerLeft" -> {
                if (env.optJSONObject("payload")?.optString("role") == "client") {
                    setState(STATE_WAITING)
                }
            }
            "session.error" -> {
                Log.w(TAG, "Relay error: ${env.optJSONObject("payload")}")
            }
            "ping" -> sendRaw(reply(env, "pong"))
            "state.get" -> sendRaw(reply(env, "state.snapshot", snapshotProvider()))
            else -> Unit // unknown types are ignored per protocol versioning rules
        }
    }

    private fun reply(requestEnv: JSONObject, type: String, payload: JSONObject = JSONObject()): String {
        val out = baseEnvelope(type)
        out.put("replyTo", requestEnv.optString("id"))
        out.put("payload", payload)
        return out.toString()
    }

    private fun send(type: String, payload: JSONObject) {
        val env = baseEnvelope(type)
        env.put("payload", payload)
        sendRaw(env.toString())
    }

    private fun baseEnvelope(type: String): JSONObject = JSONObject().apply {
        put("v", PROTOCOL_VERSION)
        put("id", nextId())
        put("type", type)
        put("ts", System.currentTimeMillis())
    }

    @Synchronized
    private fun nextId(): String = "dev_${System.currentTimeMillis().toString(36)}_${messageCounter++}"

    private fun sendRaw(text: String) {
        val ws = webSocket
        if (ws == null) {
            Log.w(TAG, "Cannot send, no socket ($text.take(80))")
            return
        }
        if (!ws.send(text)) {
            Log.w(TAG, "Socket refused frame; will reconnect")
            scheduleReconnect()
        }
    }

    private fun startHeartbeat() {
        synchronized(this) {
            if (heartbeatScheduled) return
            heartbeatScheduled = true
        }
        Thread({
            while (running.get()) {
                try {
                    Thread.sleep(HEARTBEAT_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    return@Thread
                }
                if (webSocket != null) {
                    send("ping", JSONObject())
                }
            }
        }, "sweetspot-relay-hb").also { it.isDaemon = true }.start()
    }

    private fun jitteredBackoff(attempt: Int): Long {
        val exp = minOf(
            BACKOFF_BASE_MS * (1L shl attempt.coerceAtMost(5)),
            BACKOFF_MAX_MS
        )
        val factor = 0.5 + Math.random() * 0.5
        return (exp * factor).toLong().coerceIn(500, BACKOFF_MAX_MS)
    }

    private fun sleep(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {}
    }
}
