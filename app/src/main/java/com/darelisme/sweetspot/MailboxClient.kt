package com.darelisme.sweetspot

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.MediaType.Companion.toMediaTypeOrNull
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
) {

    companion object {
        private const val TAG = "SweetSpotMailbox"
        private const val DEVICE_TTL_MS = 15_000L
        private const val POLL_WAIT_SECONDS = 9
        private const val ERROR_BACKOFF_MS = 3_000L
    }

    interface Listener {
        fun onDeviceOnline(online: Boolean)
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
                    val body = res.body?.string() ?: "{}"
                    val commands = JSONObject(body).optJSONArray("commands") ?: JSONArray()
                    for (i in 0 until commands.length()) {
                        handleCommand(commands.getJSONObject(i))
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

    private fun handleCommand(env: JSONObject) {
        when (env.optString("type")) {
            "ping" -> postToDevice(env, "pong")
            "state.get" -> postToDevice(env, "state.snapshot", snapshotProvider())
            else -> Log.d(TAG, "Ignoring command type ${env.optString("type")}")
        }
    }

    private fun postToDevice(requestEnv: JSONObject, type: String, payload: JSONObject = JSONObject()) {
        val out = JSONObject().apply {
            put("v", 1)
            put("id", "dev_${System.currentTimeMillis().toString(36)}")
            put("type", type)
            put("ts", System.currentTimeMillis())
            put("replyTo", requestEnv.optString("id"))
            put("payload", payload)
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
