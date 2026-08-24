package com.darelisme.sweetspot

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject

/**
 * Device-side mailbox client. The TV registers its pair-code room and
 * long-polls for commands from dashboards; snapshots go back via POST.
 *
 * Plain HTTP only: no persistent connection, no reconnect state machine.
 * Presence = this loop polling at least every [DEVICE_TTL_MS].
 */
class MailboxClient(
    private val roomProvider: () -> String,
    private val snapshotProvider: () -> JSONObject,
    /** Runs effect-chain diagnostics on a background thread; called for 'diagnostics.effects'. */
    private val effectsDiagnosticsProvider: (() -> JSONObject)? = null,
    /** Dispatches control commands; replies are posted back to the room. Nullable = legacy. */
    private val commandHandler: CommandHandler? = null,
) {

    /** Handles dashboard control commands. All methods run on the mailbox poll thread. */
    interface CommandHandler {
        /** Handle a non-query command; return true if a state.snapshot should be posted back. */
        fun onCommand(type: String, payload: JSONObject, replyTo: (String, JSONObject) -> Unit)
    }

    companion object {
        private const val TAG = "SweetSpotMailbox"
        private const val DEVICE_TTL_MS = 15_000L
        private const val POLL_WAIT_SECONDS = 9
        private const val ERROR_BACKOFF_MS = 3_000L
        private const val MAX_COMMAND_RESPONSE_BYTES = 1 * 1024 * 1024
    }

    interface Listener {
        fun onDeviceOnline(online: Boolean)

        /** True while a dashboard is actively posting to the room. */
        fun onClientPresence(present: Boolean)
    }

    @Volatile
    var listener: Listener? = null

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout((POLL_WAIT_SECONDS + 6) * 1000L, TimeUnit.MILLISECONDS)
        .build()

    private val executor = Executors.newSingleThreadExecutor()
    private val running = AtomicBoolean(false)

    fun start() {
        if (!running.compareAndSet(false, true)) return
        executor.execute { pollLoop() }
    }

    fun stop() {
        running.set(false)
        executor.shutdownNow()
    }

    private fun pollLoop() {
        var wasOnline = false
        var wasClientPresent = false
        while (running.get()) {
            val room = roomProvider()
            try {
                register(room)

                // Long-poll: returns immediately when a command is queued, else after wait.
                val req = okhttp3.Request.Builder()
                    .url("${Config.MAILBOX_URL}/api/room/$room/commands?wait=$POLL_WAIT_SECONDS")
                    .get()
                    .build()

                client.newCall(req).execute().use { res ->
                    if (!res.isSuccessful) throw IllegalStateException("HTTP ${res.code}")
                    val body = readBodyBounded(res.body)
                    val json = JSONObject(body)
                    val commands = json.optJSONArray("commands") ?: JSONArray()
                    for (i in 0 until commands.length()) {
                        handleCommand(commands.getJSONObject(i))
                    }
                    // The DO stamps clientSeenAt on every /client POST, so this
                    // reflects real dashboard activity, not just our own polls.
                    val clientPresent = json.optBoolean("clientOnline", false)
                    if (clientPresent != wasClientPresent) {
                        wasClientPresent = clientPresent
                        listener?.onClientPresence(clientPresent)
                    }
                }

                if (!wasOnline) {
                    wasOnline = true
                    listener?.onDeviceOnline(true)
                    Log.i(TAG, "Registered in room (device online)")
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

    private fun register(room: String) {
        val req = okhttp3.Request.Builder()
            .url("${Config.MAILBOX_URL}/api/room/$room/register")
            .post(okhttp3.RequestBody.create(null, "{}"))
            .build()
        client.newCall(req).execute().use { res ->
            if (!res.isSuccessful) throw IllegalStateException("register HTTP ${res.code}")
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
            put("id", "dev_${System.currentTimeMillis().toString(36)}")
            put("type", type)
            put("ts", System.currentTimeMillis())
            put("replyTo", requestEnv.optString("id"))
            put("payload", payload)
            if (extra != null) {
                for (key in extra.keys()) payload.put(key, extra.get(key))
            }
        }
        val room = PairCodeManager.normalize(roomProvider())
        val req = okhttp3.Request.Builder()
            .url("${Config.MAILBOX_URL}/api/room/$room/device")
            .post(okhttp3.RequestBody.create("application/json".toMediaTypeOrNull(), out.toString()))
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
