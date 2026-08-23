package com.darelisme.sweetspot

import android.content.Context
import android.os.Debug
import android.system.Os
import android.system.OsConstants
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject

/**
 * Minimal, dependency-free embedded HTTP server for LAN control.
 *
 * Design goals (per project constraints):
 *  - no external server framework
 *  - tiny fixed thread pool (low RAM, few threads)
 *  - binds to all interfaces so phones on the LAN can reach it
 *  - serves static assets from [Context.getAssets] and a small JSON REST API
 *  - talks to the [AudioEngine] abstraction only, never to Equalizer directly
 *
 * One request per connection, `Connection: close` (no keep-alive parsing needed).
 */

/**
 * Hooks the web server uses to trigger service-level diagnostics
 * (DynamicsProcessing capacity probe + persistent instance). Implemented by
 * [SweetSpotService]; keeps [WebServer] decoupled from the concrete service.
 */
interface ServiceActions {
    fun runProbe()
    fun runPersistentProbe(bands: Int)
    fun releasePersistentProbe()
    fun getLastProbeResults(): List<DynamicsProcessingProbe.ProbeResult>?
    fun isProbeRunning(): Boolean
    fun isPersistentProbeActive(): Boolean
    fun getPersistentProbeBands(): Int
    fun applyPersistentCurve(curve: String): Boolean
    fun getPersistentProbeCurve(): String?
    fun getPersistentProbeCurveSummary(): DynamicsProcessingProbe.CurveSummary?

    // Calibration (128-band read-only base curve; wizard/API only).
    fun getCalibrationBands(): FloatArray?
    fun getCalibrationFrequenciesHz(): IntArray?
    fun isCalibrationActive(): Boolean
    fun setCalibrationBands(gains: FloatArray): Boolean
    fun resetCalibration(): Boolean
}

class WebServer(
    private val context: Context,
    private val engine: AudioEngine,
    private val overlay: OverlayController? = null,
    private val serviceActions: ServiceActions? = null,
    private val port: Int = Config.WEB_PORT
) {
    companion object {
        private const val TAG = "SweetSpotWeb"
        private const val WWW_ROOT = "www"
    }

    @Volatile
    private var serverSocket: ServerSocket? = null
    private val running = AtomicBoolean(false)
    private val executor = Executors.newFixedThreadPool(2)
    private var acceptThread: Thread? = null

    fun start() {
        if (running.get()) return
        running.set(true)
        acceptThread = Thread({
            try {
                val ss = ServerSocket(port, 0, InetAddress.getByName("0.0.0.0"))
                serverSocket = ss
                Log.i(TAG, "Listening on 0.0.0.0:$port")
                while (running.get()) {
                    try {
                        val client = ss.accept()
                        executor.execute { handle(client) }
                    } catch (e: SocketException) {
                        if (running.get()) Log.w(TAG, "accept interrupted: ${e.message}")
                    } catch (e: Exception) {
                        if (running.get()) Log.e(TAG, "accept error", e)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start server on port $port (is it already in use?)", e)
            } finally {
                try { serverSocket?.close() } catch (_: Exception) {}
            }
        }, "sweetspot-http").also {
            it.isDaemon = true
            it.start()
        }
    }

    fun stop() {
        running.set(false)
        try { serverSocket?.close() } catch (_: Exception) {}
        executor.shutdownNow()
        Log.i(TAG, "Server stopped")
    }

    private fun handle(client: Socket) {
        try {
            client.soTimeout = 5000
            val input = BufferedReader(InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8))
            val requestLine = input.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) {
                sendError(client, 400, "Bad Request")
                return
            }
            val method = parts[0]
            val path = parts[1]

            // Read headers until blank line.
            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = input.readLine() ?: break
                if (line.isEmpty()) break
                val idx = line.indexOf(':')
                if (idx > 0) {
                    headers[line.substring(0, idx).trim().lowercase()] =
                        line.substring(idx + 1).trim()
                }
            }

            var body = ""
            val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
            if (contentLength > 0 && method == "POST") {
                val buf = CharArray(contentLength)
                var read = 0
                while (read < contentLength) {
                    val n = input.read(buf, read, contentLength - read)
                    if (n < 0) break
                    read += n
                }
                body = String(buf, 0, read)
            } else if (method == "POST") {
                // Fallback: some clients omit Content-Length (e.g. chunked).
                // Read whatever is immediately available without blocking.
                val sb = StringBuilder()
                while (input.ready()) {
                    val ch = input.read()
                    if (ch == -1) break
                    sb.append(ch.toChar())
                }
                body = sb.toString()
            }

            route(client, method, path, body)
        } catch (e: Exception) {
            Log.e(TAG, "handle error", e)
        } finally {
            try { client.close() } catch (_: Exception) {}
        }
    }

    private fun route(client: Socket, method: String, path: String, body: String) {
        when {
            method == "GET" && (path == "/" || path == "/index.html") ->
                serveAsset(client, "$WWW_ROOT/index.html", "text/html; charset=utf-8")

            method == "GET" && path == "/style.css" ->
                serveAsset(client, "$WWW_ROOT/style.css", "text/css; charset=utf-8")

            method == "GET" && path == "/app.js" ->
                serveAsset(client, "$WWW_ROOT/app.js", "application/javascript; charset=utf-8")

            method == "GET" && path == "/api/state" ->
                sendJson(client, stateJson())

            method == "GET" && path == "/api/profiles" ->
                sendJson(client, """{"profiles":[${engine.listProfiles().joinToString(",") { """"$it"""" }}]}""")

            method == "POST" && path == "/api/preset" -> {
                val preset = parseIntField(body, "preset") ?: 1
                engine.applyPreset(preset)
                sendJson(client, stateJson())
            }

            method == "POST" && path == "/api/saveprofile" -> {
                val name = parseStringField(body, "name")
                if (!name.isNullOrBlank()) engine.saveCurrentProfile(name)
                sendJson(client, stateJson())
            }

            method == "POST" && path == "/api/loadprofile" -> {
                val name = parseStringField(body, "name")
                if (!name.isNullOrBlank()) engine.loadProfile(name)
                sendJson(client, stateJson())
            }

            method == "POST" && path == "/api/deleteprofile" -> {
                val name = parseStringField(body, "name")
                if (!name.isNullOrBlank()) engine.deleteProfile(name)
                sendJson(client, stateJson())
            }

            method == "POST" && path == "/api/bypass" -> {
                engine.setEnabled(false)
                sendJson(client, stateJson())
            }

            method == "POST" && path == "/api/enable" -> {
                engine.setEnabled(true)
                sendJson(client, stateJson())
            }

            method == "POST" && path == "/api/bands" -> {
                val levels = parseIntArrayField(body, "levels")
                if (levels != null) {
                    levels.forEachIndexed { i, v -> engine.setBandLevel(i, v) }
                }
                sendJson(client, stateJson())
            }

            method == "POST" && path == "/api/showui" -> {
                overlay?.show()
                sendJson(client, stateJson())
            }

            method == "POST" && path == "/api/hideui" -> {
                overlay?.hide()
                sendJson(client, stateJson())
            }

            // --- DynamicsProcessing diagnostics (web-driven, no adb needed) ---
            method == "POST" && path == "/api/probe" -> {
                serviceActions?.runProbe()
                sendJson(client, """{"status":"started"}""")
            }

            method == "GET" && path == "/api/probe/status" ->
                sendJson(client, probeStatusJson())

            method == "POST" && path == "/api/probe/persist" -> {
                val bands = parseIntField(body, "bands") ?: 128
                serviceActions?.runPersistentProbe(bands)
                sendJson(client, """{"status":"persistent_started","bands":$bands}""")
            }

            method == "POST" && path == "/api/probe/release" -> {
                serviceActions?.releasePersistentProbe()
                sendJson(client, """{"status":"released"}""")
            }

            method == "POST" && path == "/api/probe/apply-curve" -> {
                val curve = parseStringField(body, "curve") ?: "hollow"
                val ok = serviceActions?.applyPersistentCurve(curve) ?: false
                val msg = if (ok) {
                    "applied"
                } else if (serviceActions?.isPersistentProbeActive() != true) {
                    "no-instance"
                } else {
                    "unknown-curve"
                }
                sendJson(client, """{"status":"$msg","curve":"$curve"}""")
            }

            method == "GET" && path == "/api/probe/persistent" ->
                sendJson(client, persistentStatusJson())

            // --- Calibration (read-only base curve; wizard/API only) ---
            method == "GET" && path == "/api/eq/calibration" ->
                sendJson(client, calibrationJson())

            method == "POST" && path == "/api/eq/calibration" -> {
                val gains = parseFloatArrayField(body, "gains")
                val ok = gains != null && (serviceActions?.setCalibrationBands(gains) ?: false)
                sendJson(client, if (ok) calibrationJson() else """{"error":"invalid","expected":128}""")
            }

            method == "POST" && path == "/api/eq/calibration/reset" -> {
                serviceActions?.resetCalibration()
                sendJson(client, calibrationJson())
            }

            method == "GET" && path == "/api/deviceinfo" ->
                sendJson(client, deviceInfoJson())

            else -> sendError(client, 404, "Not Found")
        }
    }

    private fun serveAsset(client: Socket, assetPath: String, contentType: String) {
        try {
            val data = context.assets.open(assetPath).use { it.readBytes() }
            sendResponse(client, 200, contentType, data)
        } catch (e: Exception) {
            sendError(client, 404, "Not Found")
        }
    }

    private fun stateJson(): String {
        val caps = engine.getCapabilities()
        val levels = engine.getBandLevels()
        val ip = NetworkUtils.getLanIpAddress() ?: "unknown"
        val bands = levels.joinToString(",") { it.toString() }
        val centers = caps.centerFrequenciesHz.joinToString(",")
        val range = caps.bandLevelRange
        val presetMap = caps.presets
        val presetName = presetMap[engine.getActivePreset()] ?: "Custom"
        val presetsJson = presetMap.entries.sortedBy { it.key }
            .joinToString(",") { """{"id": ${it.key}, "name": "${it.value}"}""" }
        val profilesJson = engine.listProfiles().joinToString(",") { """"$it"""" }
        val calBands = serviceActions?.getCalibrationBands()
        val calFreqs = serviceActions?.getCalibrationFrequenciesHz()
        val calActive = serviceActions?.isCalibrationActive() ?: false
        val calJson = if (calBands != null && calFreqs != null) {
            """{"active":$calActive,"bands":[${calBands.joinToString(",")}],"frequenciesHz":[${calFreqs.joinToString(",")}]}"""
        } else "null"
        return """{
  "enabled": ${engine.isEnabled()},
  "hasControl": ${engine.hasControl()},
  "activePreset": ${engine.getActivePreset()},
  "presetName": "$presetName",
  "ip": "$ip",
  "port": $port,
  "bands": [$bands],
  "centerFrequenciesHz": [$centers],
  "bandLevelRange": [${range[0]}, ${range[1]}],
  "overlayVisible": ${overlay?.isShown() ?: false},
  "presets": [$presetsJson],
  "profiles": [$profilesJson],
  "calibration": $calJson
}"""
    }

    private fun probeStatusJson(): String {
        val running = serviceActions?.isProbeRunning() ?: false
        val results = serviceActions?.getLastProbeResults()
        val arr = JSONArray()
        var highest = -1
        results?.forEach { r ->
            val pass = r.constructed && r.hasControl && r.enabled && r.actualBands == r.requested
            if (pass) highest = maxOf(highest, r.requested)
            arr.put(JSONObject().apply {
                put("requested", r.requested)
                put("constructed", r.constructed)
                put("hasControl", r.hasControl)
                put("enabled", r.enabled)
                put("actualBands", r.actualBands)
                put("pass", pass)
                put("exception", r.exception ?: JSONObject.NULL)
            })
        }
        return JSONObject().apply {
            put("running", running)
            put("available", results != null)
            put("results", arr)
            put("highest", highest)
            put("recommended", highest)
        }.toString()
    }

    private fun persistentStatusJson(): String {
        val active = serviceActions?.isPersistentProbeActive() ?: false
        val bands = serviceActions?.getPersistentProbeBands() ?: 0
        val curve = serviceActions?.getPersistentProbeCurve()
        val sum = serviceActions?.getPersistentProbeCurveSummary()
        return JSONObject().apply {
            put("active", active)
            put("bands", bands)
            put("curve", curve ?: JSONObject.NULL)
            if (sum != null) {
                put("curveSummary", JSONObject().apply {
                    put("bandsTotal", sum.bandsTotal)
                    put("bandsCut", sum.bandsCut)
                    put("bandsFlat", sum.bandsFlat)
                })
            }
        }.toString()
    }

    private fun deviceInfoJson(): String {
        val rt = Runtime.getRuntime()
        val memInfo = Debug.MemoryInfo()
        Debug.getMemoryInfo(memInfo)
        // Sample app + audioserver CPU in a single ~400ms window.
        val appT0 = procCpuTicks()
        val asPid = resolveAudioserverPid()
        val asT0 = asPid?.let { procCpuTicksForPid(it) } ?: 0L
        val start = System.nanoTime()
        try { Thread.sleep(400) } catch (_: InterruptedException) {}
        val appT1 = procCpuTicks()
        val asT1 = asPid?.let { procCpuTicksForPid(it) } ?: 0L
        val end = System.nanoTime()
        val clk = try {
            Os.sysconf(OsConstants._SC_CLK_TCK).toDouble()
        } catch (_: Throwable) { 100.0 }
        val wallSecs = (end - start) / 1e9
        val appCpu = if (wallSecs > 0) ((appT1 - appT0).toDouble() / clk / wallSecs) * 100.0 else 0.0
        val asCpu = if (wallSecs > 0 && asPid != null) ((asT1 - asT0).toDouble() / clk / wallSecs) * 100.0 else 0.0
        return JSONObject().apply {
            put("javaHeapMax", rt.maxMemory())
            put("javaHeapTotal", rt.totalMemory())
            put("javaHeapFree", rt.freeMemory())
            put("nativeHeapAllocated", Debug.getNativeHeapAllocatedSize())
            put("nativeHeapSize", Debug.getNativeHeapSize())
            put("pssTotalKb", memInfo.totalPss)
            put("privateDirtyKb", memInfo.totalPrivateDirty)
            put("sharedDirtyKb", memInfo.totalSharedDirty)
            put("cpuPercent", appCpu)
            put("audioserverCpuPercent", asCpu)
            put("audioserverPid", asPid ?: JSONObject.NULL)
            put("persistentProbeActive", serviceActions?.isPersistentProbeActive() ?: false)
            put("persistentProbeBands", serviceActions?.getPersistentProbeBands() ?: 0)
        }.toString()
    }

    @Volatile
    private var cachedAudioserverPid: Int? = null

    /** Finds a process PID by its comm (command name) via /proc/<pid>/stat. */
    private fun findProcessPid(name: String): Int? {
        return try {
            val entries = File("/proc").list() ?: return null
            for (entry in entries) {
                val pid = entry.toIntOrNull() ?: continue
                val statFile = File("/proc/$pid/stat")
                if (!statFile.exists()) continue
                val stat = statFile.readText()
                val s = stat.indexOf('(')
                val e = stat.lastIndexOf(')')
                if (s >= 0 && e > s) {
                    val comm = stat.substring(s + 1, e)
                    if (comm == name) return pid
                }
            }
            null
        } catch (_: Throwable) { null }
    }

    /** Resolves (and caches) the audioserver PID; re-resolves if it disappeared. */
    private fun resolveAudioserverPid(): Int? {
        val cached = cachedAudioserverPid
        if (cached != null && File("/proc/$cached/stat").exists()) return cached
        val pid = findProcessPid("audioserver") ?: findProcessPid("audioserver64")
        cachedAudioserverPid = pid
        return pid
    }

    private fun procCpuTicks(): Long = procCpuTicksForPid(android.os.Process.myPid())

    private fun procCpuTicksForPid(pid: Int): Long {
        return try {
            val stat = File("/proc/$pid/stat").readText()
            val idx = stat.lastIndexOf(')')
            val parts = stat.substring(idx + 1).trim().split("\\s+".toRegex())
            val utime = parts.getOrNull(13)?.toLongOrNull() ?: 0L
            val stime = parts.getOrNull(14)?.toLongOrNull() ?: 0L
            utime + stime
        } catch (_: Throwable) { 0L }
    }

    private fun sendJson(client: Socket, json: String) {
        sendResponse(
            client, 200, "application/json; charset=utf-8",
            json.toByteArray(StandardCharsets.UTF_8)
        )
    }

    private fun sendResponse(client: Socket, code: Int, contentType: String, data: ByteArray) {
        val out: OutputStream = client.getOutputStream()
        val reason = if (code == 200) "OK" else "Error"
        val header = "HTTP/1.1 $code $reason\r\n" +
            "Content-Type: $contentType\r\n" +
            "Content-Length: ${data.size}\r\n" +
            "Connection: close\r\n" +
            "Cache-Control: no-store\r\n" +
            "Access-Control-Allow-Origin: *\r\n" +
            "\r\n"
        out.write(header.toByteArray(StandardCharsets.UTF_8))
        out.write(data)
        out.flush()
    }

    private fun sendError(client: Socket, code: Int, msg: String) {
        sendResponse(client, code, "text/plain; charset=utf-8", msg.toByteArray(StandardCharsets.UTF_8))
    }

    private fun parseIntField(json: String, key: String): Int? {
        val regex = """"$key"\s*:\s*(-?\d+)""".toRegex()
        return regex.find(json)?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun parseStringField(json: String, key: String): String? {
        val regex = """"$key"\s*:\s*"([^"]*)"""".toRegex()
        return regex.find(json)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
    }

    private fun parseIntArrayField(json: String, key: String): IntArray? {
        val regex = """"$key"\s*:\s*\[([-\d\s,]+)\]""".toRegex()
        val m = regex.find(json) ?: return null
        return m.groupValues[1].split(',').mapNotNull { it.trim().toIntOrNull() }.toIntArray()
    }

    private fun calibrationJson(): String {
        val bands = serviceActions?.getCalibrationBands()
        val freqs = serviceActions?.getCalibrationFrequenciesHz()
        val active = serviceActions?.isCalibrationActive() ?: false
        return if (bands != null && freqs != null) {
            JSONObject().apply {
                put("active", active)
                put("bands", JSONArray().apply { bands.forEach { put(it) } })
                put("frequenciesHz", JSONArray().apply { freqs.forEach { put(it) } })
            }.toString()
        } else """{"active":false,"bands":[],"frequenciesHz":[]}"""
    }

    private fun parseFloatArrayField(json: String, key: String): FloatArray? {
        val regex = """"$key"\s*:\s*\[([-\d.eE\s,]+)\]""".toRegex()
        val m = regex.find(json) ?: return null
        return m.groupValues[1].split(',').mapNotNull { it.trim().toFloatOrNull() }.toFloatArray()
    }
}
