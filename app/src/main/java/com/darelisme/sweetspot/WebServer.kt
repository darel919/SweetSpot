package com.darelisme.sweetspot

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
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject

/**
 * Minimal dependency-free local HTTP API server for SweetSpot device control.
 *
 * Responsibilities:
 *  - expose device/DSP state
 *  - accept control commands
 *  - expose calibration/profile operations
 *  - expose development diagnostics
 *
 * Design goals (per project constraints):
 *  - no external server framework
 *  - tiny fixed thread pool (low RAM, few threads)
 *  - binds to all interfaces so clients on the LAN can reach it
 *  - talks to the [AudioEngine] abstraction only, never to Equalizer directly
 *
 * It does not serve the SweetSpot browser dashboard. The dashboard is hosted
 * separately by sweetspot-web.
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
    fun applyPersistentBands(common: FloatArray, left: FloatArray? = null, right: FloatArray? = null): Boolean
    fun getPersistentProbeCurve(): String?
    fun getPersistentProbeCurveSummary(channel: Int = 0): DynamicsProcessingProbe.CurveSummary?

    /** Audio effect chain diagnostics (effect inventory + session-0 probes). */
    fun runEffectDiagnostics()
    fun getEffectInventory(): List<AudioEffectDiagnostics.EffectInventoryEntry>
    fun getSessionProbes(): List<AudioEffectDiagnostics.SessionProbe>

    /** Calibration is a 64-band read-only base curve managed by the wizard/API. */
    fun getCalibrationBands(): FloatArray?
    fun getRequestedCalibrationBands(): FloatArray?
    fun getEffectiveCalibrationBands(): FloatArray?
    fun getRequestedCalibrationBandsForChannel(channel: Int): FloatArray?
    fun getEffectiveCalibrationBandsForChannel(channel: Int): FloatArray?
    fun getCalibrationFrequenciesHz(): IntArray?
    fun isCalibrationActive(): Boolean
    fun wasLastCalibrationApplySuccessful(): Boolean
    fun getLastCalibrationApplyError(): String?
    fun isCalibrationLiveDspVerified(): Boolean
    fun getCalibrationLiveDspVerificationError(): String?
    fun setCalibrationBands(gains: FloatArray): Boolean
    fun resetCalibration(): Boolean
}

class WebServer(
    private val engine: AudioEngine,
    private val overlay: OverlayController? = null,
    private val serviceActions: ServiceActions? = null,
    private val port: Int = Config.WEB_PORT,
    private val eqAppliedNotifier: ((String) -> Unit)? = null,
    private val pairCodeProvider: () -> String,
    private val pairCodeRotateProvider: () -> String,
) {
    companion object {
        private const val TAG = "SweetSpotWeb"
        private const val MAX_REQUEST_LINE_CHARS = 4 * 1024
        private const val MAX_HEADER_LINE_CHARS = 8 * 1024
        private const val MAX_HEADER_CHARS = 32 * 1024
        private const val MAX_HEADER_COUNT = 32
        private const val MAX_REQUEST_BODY_CHARS = 64 * 1024
        private const val HTTP_WORKER_COUNT = 2
        private const val HTTP_QUEUE_CAPACITY = 16

        internal fun rootRedirectResponse(pairCodeProvider: () -> String): HttpResponse =
            redirectResponse(PairCodeManager.connectUrl(pairCodeProvider()))

        internal fun redirectResponse(location: String): HttpResponse =
            HttpResponse(
                statusCode = 302,
                reasonPhrase = "Found",
                headers = listOf("Location" to location),
            )

        internal fun serializeResponse(response: HttpResponse): ByteArray {
            response.headers.forEach { (name, value) ->
                require('\r' !in name && '\n' !in name && '\r' !in value && '\n' !in value) {
                    "HTTP header contains CR or LF"
                }
            }
            response.contentType?.let { contentType ->
                require('\r' !in contentType && '\n' !in contentType) {
                    "HTTP header contains CR or LF"
                }
            }

            val header = buildString {
                append("HTTP/1.1 ${response.statusCode} ${response.reasonPhrase}\r\n")
                response.headers.forEach { (name, value) ->
                    append("$name: $value\r\n")
                }
                response.contentType?.let { append("Content-Type: $it\r\n") }
                append("Content-Length: ${response.body.size}\r\n")
                append("Connection: close\r\n")
                append("Cache-Control: no-store\r\n")
                append("Access-Control-Allow-Origin: *\r\n")
                append("\r\n")
            }.toByteArray(StandardCharsets.UTF_8)

            return ByteArray(header.size + response.body.size).also { bytes ->
                header.copyInto(bytes)
                response.body.copyInto(bytes, destinationOffset = header.size)
            }
        }
    }

    internal data class HttpResponse(
        val statusCode: Int,
        val reasonPhrase: String,
        val contentType: String? = null,
        val body: ByteArray = byteArrayOf(),
        val headers: List<Pair<String, String>> = emptyList(),
    )

    @Volatile
    private var serverSocket: ServerSocket? = null
    private val running = AtomicBoolean(false)
    /**
     * Keep local-control bursts from accumulating an unbounded Runnable queue
     * on the always-running TV service. CallerRunsPolicy applies backpressure
     * on the accept thread while keeping the request observable to the client.
     */
    private val executor = ThreadPoolExecutor(
        HTTP_WORKER_COUNT,
        HTTP_WORKER_COUNT,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(HTTP_QUEUE_CAPACITY),
        ThreadPoolExecutor.CallerRunsPolicy()
    )
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

    /**
     * Handles one request. POST bodies without Content-Length consume only
     * already-available input so the worker does not block waiting for a body.
     */
    private fun handle(client: Socket) {
        try {
            client.soTimeout = 5000
            val input = BufferedReader(InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8))
            val requestLine = readBoundedLine(input, MAX_REQUEST_LINE_CHARS) ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) {
                sendError(client, 400, "Bad Request")
                return
            }
            val method = parts[0]
            val path = parts[1]

            val headers = mutableMapOf<String, String>()
            var headerChars = 0
            var headerCount = 0
            while (true) {
                val line = readBoundedLine(input, MAX_HEADER_LINE_CHARS) ?: break
                if (line.isEmpty()) break
                headerCount++
                headerChars += line.length
                if (headerCount > MAX_HEADER_COUNT || headerChars > MAX_HEADER_CHARS) {
                    throw RequestTooLargeException()
                }
                val idx = line.indexOf(':')
                if (idx > 0) {
                    headers[line.substring(0, idx).trim().lowercase()] =
                        line.substring(idx + 1).trim()
                }
            }

            var body = ""
            val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
            if (contentLength > 0 && method == "POST") {
                if (contentLength > MAX_REQUEST_BODY_CHARS) {
                    sendError(client, 413, "Request body too large")
                    return
                }
                val buf = CharArray(contentLength)
                var read = 0
                while (read < contentLength) {
                    val n = input.read(buf, read, contentLength - read)
                    if (n < 0) break
                    read += n
                }
                body = String(buf, 0, read)
            } else if (method == "POST") {
                val sb = StringBuilder()
                while (input.ready() && sb.length < MAX_REQUEST_BODY_CHARS) {
                    val ch = input.read()
                    if (ch == -1) break
                    sb.append(ch.toChar())
                }
                if (sb.length >= MAX_REQUEST_BODY_CHARS) {
                    sendError(client, 413, "Request body too large")
                    return
                }
                body = sb.toString()
            }

            route(client, method, path, body)
        } catch (_: RequestTooLargeException) {
            try { sendError(client, 413, "Request headers or body too large") } catch (_: Exception) {}
        } catch (e: Exception) {
            Log.e(TAG, "handle error", e)
        } finally {
            try { client.close() } catch (_: Exception) {}
        }
    }

    private fun readBoundedLine(input: BufferedReader, maxChars: Int): String? {
        val line = StringBuilder(minOf(maxChars, 128))
        var sawCharacter = false
        while (true) {
            val codePoint = input.read()
            if (codePoint < 0) return if (sawCharacter) line.toString() else null
            sawCharacter = true
            if (codePoint == '\n'.code) {
                if (line.isNotEmpty() && line.last() == '\r') line.setLength(line.length - 1)
                return line.toString()
            }
            if (line.length >= maxChars) throw RequestTooLargeException()
            line.append(codePoint.toChar())
        }
    }

    private class RequestTooLargeException : RuntimeException()

    private fun route(client: Socket, method: String, path: String, body: String) {
        when {
            method == "GET" && path == "/" ->
                sendResponse(client, rootRedirectResponse(pairCodeProvider))

            method == "GET" && path == "/api/health" ->
                sendJson(client, """{"ok":true,"service":"SweetSpot","apiVersion":1}""")

            method == "GET" && path == "/api/state" ->
                sendJson(client, stateJson())

            method == "GET" && path == "/api/profiles" ->
                sendJson(client, """{"profiles":[${engine.listProfiles().joinToString(",") { """"$it"""" }}]}""")

            method == "POST" && path == "/api/preset" -> {
                val preset = parseIntField(body, "preset") ?: 1
                val eqName = engine.getCapabilities().presets[preset]
                val ok = engine.applyPreset(preset)
                if (ok) eqName?.let { eqAppliedNotifier?.invoke(it) }
                sendJson(client, stateJson(ok, if (ok) null else "Live DSP rejected preset"))
            }

            method == "POST" && path == "/api/saveprofile" -> {
                val name = parseStringField(body, "name")
                if (!name.isNullOrBlank()) engine.saveCurrentProfile(name)
                sendJson(client, stateJson())
            }

            method == "POST" && path == "/api/loadprofile" -> {
                val name = parseStringField(body, "name")
                val ok = !name.isNullOrBlank() && name in engine.listProfiles() && engine.loadProfile(name)
                if (ok) {
                    eqAppliedNotifier?.invoke(name)
                }
                sendJson(client, stateJson(ok, if (ok) null else "Live DSP rejected profile load"))
            }

            method == "POST" && path == "/api/deleteprofile" -> {
                val name = parseStringField(body, "name")
                if (!name.isNullOrBlank()) engine.deleteProfile(name)
                sendJson(client, stateJson())
            }

            method == "POST" && path == "/api/bypass" -> {
                val ok = engine.setEnabled(false)
                sendJson(client, stateJson(ok, if (ok) null else "Live DSP rejected bypass"))
            }

            method == "POST" && path == "/api/enable" -> {
                val ok = engine.setEnabled(true)
                sendJson(client, stateJson(ok, if (ok) null else "Live DSP rejected enable"))
            }

            method == "POST" && path == "/api/bands" -> {
                val levels = parseIntArrayField(body, "levels")
                val capabilities = engine.getCapabilities()
                val previous = engine.getBandLevels()
                val previousPreset = engine.getActivePreset()
                var ok = levels != null && levels.size == capabilities.bandCount
                var error: String? = if (!ok) "Expected ${capabilities.bandCount} user EQ bands" else null
                if (ok && levels != null) {
                    levels.forEachIndexed { i, v ->
                        if (ok && !engine.setBandLevel(i, v)) {
                            ok = false
                            error = "Live DSP rejected user EQ band $i"
                        }
                    }
                }
                if (!ok) {
                    previous.forEachIndexed { i, v -> engine.setBandLevel(i, v) }
                    if (previousPreset > 0) engine.applyPreset(previousPreset)
                }
                sendJson(client, stateJson(ok, error))
            }

            method == "POST" && path == "/api/showui" -> {
                overlay?.show()
                sendJson(client, stateJson())
            }

            method == "POST" && path == "/api/hideui" -> {
                overlay?.hide()
                sendJson(client, stateJson())
            }

            method == "POST" && path == "/api/probe" -> {
                serviceActions?.runProbe()
                sendJson(client, """{"status":"started"}""")
            }

            method == "GET" && path == "/api/probe/status" ->
                sendJson(client, probeStatusJson())

            method == "POST" && path == "/api/probe/persist" -> {
                val bands = parseIntField(body, "bands") ?: 128
                if (bands != DynamicsProcessingEq.INTERNAL_BANDS) {
                    sendJson(client, """{"status":"rejected","error":"The diagnostic overlay requires exactly 64 bands","bands":$bands}""")
                } else {
                    serviceActions?.runPersistentProbe(bands)
                    sendJson(client, """{"status":"persistent_started","bands":$bands}""")
                }
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

            method == "POST" && path == "/api/probe/apply-bands" -> {
                val common = parseFloatArrayField(body, "bandsDb")
                val left = parseFloatArrayField(body, "leftBandsDb")
                val right = parseFloatArrayField(body, "rightBandsDb")
                val ok = common != null && serviceActions?.applyPersistentBands(common, left, right) == true
                val status = when {
                    serviceActions?.isPersistentProbeActive() != true -> "no-instance"
                    ok -> "applied"
                    else -> "rejected"
                }
                sendJson(client, JSONObject().apply {
                    put("ok", ok)
                    put("status", status)
                    put("curve", "custom")
                }.toString())
            }

            method == "GET" && path == "/api/probe/persistent" ->
                sendJson(client, persistentStatusJson())

            method == "POST" && path == "/api/effects/diagnose" -> {
                serviceActions?.runEffectDiagnostics()
                sendJson(client, """{"status":"started"}""")
            }

            method == "GET" && path == "/api/effects/diagnostics" ->
                sendJson(client, effectDiagnosticsJson())

            method == "GET" && path == "/api/eq/calibration" ->
                sendJson(client, calibrationJson())

            method == "POST" && path == "/api/eq/calibration" -> {
                val gains = parseFloatArrayField(body, "gains")
                val ok = gains != null && (serviceActions?.setCalibrationBands(gains) ?: false)
                val error = if (ok) null else serviceActions?.getLastCalibrationApplyError() ?: "Calibration candidate was rejected"
                sendJson(client, calibrationJson(ok, error))
            }

            method == "POST" && path == "/api/eq/calibration/reset" -> {
                val ok = serviceActions?.resetCalibration() ?: false
                val error = if (ok) null else serviceActions?.getLastCalibrationApplyError() ?: "Calibration reset was rejected"
                sendJson(client, calibrationJson(ok, error))
            }

            method == "GET" && path == "/api/deviceinfo" ->
                sendJson(client, deviceInfoJson())

            method == "GET" && path == "/api/paircode" -> {
                val code = pairCodeProvider()
                sendJson(client, """{"pairCode":"$code","url":"${PairCodeManager.connectUrl(code)}"}""")
            }

            method == "POST" && path == "/api/paircode/rotate" -> {
                val code = pairCodeRotateProvider()
                sendJson(client, """{"pairCode":"$code","rotated":true}""")
            }

            else -> sendError(client, 404, "Not Found")
        }
    }
    private fun stateJson(ok: Boolean? = null, error: String? = null): String {
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
        val outcome = if (ok == null) "" else "\"ok\":$ok,${if (error == null) "" else "\"error\":${JSONObject.quote(error)},"}"
        return """{
  $outcome
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
            put("persistent", JSONObject(persistentStatusJson()))
        }.toString()
    }

    private fun persistentStatusJson(): String {
        val active = serviceActions?.isPersistentProbeActive() ?: false
        val bands = serviceActions?.getPersistentProbeBands() ?: 0
        val curve = serviceActions?.getPersistentProbeCurve()
        val sum = serviceActions?.getPersistentProbeCurveSummary()
        val leftSum = serviceActions?.getPersistentProbeCurveSummary(0)
        val rightSum = serviceActions?.getPersistentProbeCurveSummary(1)
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
            if (leftSum != null) put("leftCurveSummary", JSONObject().apply {
                put("bandsTotal", leftSum.bandsTotal)
                put("bandsCut", leftSum.bandsCut)
                put("bandsFlat", leftSum.bandsFlat)
            })
            if (rightSum != null) put("rightCurveSummary", JSONObject().apply {
                put("bandsTotal", rightSum.bandsTotal)
                put("bandsCut", rightSum.bandsCut)
                put("bandsFlat", rightSum.bandsFlat)
            })
        }.toString()
    }

    private fun effectDiagnosticsJson(): String {
        val inv = serviceActions?.getEffectInventory()
        val probes = serviceActions?.getSessionProbes()
        if (inv == null || probes == null) return """{"error":"not_run_yet"}"""
        return AudioEffectDiagnostics.payloadJson(inv, probes).put("available", true).toString()
    }

    /** Samples app and audioserver CPU in one approximately 400 ms window. */
    private fun deviceInfoJson(): String {
        val rt = Runtime.getRuntime()
        val memInfo = Debug.MemoryInfo()
        Debug.getMemoryInfo(memInfo)
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
        val reason = if (code == 200) "OK" else "Error"
        sendResponse(
            client,
            HttpResponse(
                statusCode = code,
                reasonPhrase = reason,
                contentType = contentType,
                body = data,
            )
        )
    }

    private fun sendResponse(client: Socket, response: HttpResponse) {
        val out: OutputStream = client.getOutputStream()
        out.write(serializeResponse(response))
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

    private fun calibrationJson(ok: Boolean? = null, error: String? = null): String {
        val bands = serviceActions?.getCalibrationBands()
        val requestedBands = serviceActions?.getRequestedCalibrationBands()
        val effectiveBands = serviceActions?.getEffectiveCalibrationBands()
        val freqs = serviceActions?.getCalibrationFrequenciesHz()
        val active = serviceActions?.isCalibrationActive() ?: false
        return if (bands != null && freqs != null) {
            JSONObject().apply {
                if (ok != null) put("ok", ok)
                if (error != null) put("error", error)
                put("active", active)
                put("bands", JSONArray().apply { bands.forEach { put(it) } })
                put("requestedBands", JSONArray().apply { (requestedBands ?: bands).forEach { put(it) } })
                put("effectiveBands", JSONArray().apply { (effectiveBands ?: bands).forEach { put(it) } })
                put("frequenciesHz", JSONArray().apply { freqs.forEach { put(it) } })
                put("applicationVerified", serviceActions.isCalibrationLiveDspVerified())
                put("liveDspStatus", if (serviceActions.isCalibrationLiveDspVerified()) "verified" else "degraded")
                (serviceActions.getCalibrationLiveDspVerificationError()
                    ?: serviceActions.getLastCalibrationApplyError())?.let { put("applicationError", it) }
            }.toString()
        } else """{"active":false,"bands":[],"frequenciesHz":[]}"""
    }

    private fun parseFloatArrayField(json: String, key: String): FloatArray? {
        val regex = """"$key"\s*:\s*\[([-\d.eE\s,]+)\]""".toRegex()
        val m = regex.find(json) ?: return null
        return m.groupValues[1].split(',').mapNotNull { it.trim().toFloatOrNull() }.toFloatArray()
    }
}
