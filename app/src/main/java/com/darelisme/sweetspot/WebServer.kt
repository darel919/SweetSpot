package com.darelisme.sweetspot

import android.os.Debug
import android.system.Os
import android.system.OsConstants
import android.util.Log
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
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
    private val authTokenProvider: () -> String,
    private val pairCodeProvider: () -> String,
    private val pairCodeRotateProvider: () -> String,
) {
    companion object {
        private const val TAG = "SweetSpotWeb"
        private const val MAX_REQUEST_LINE_CHARS = 4 * 1024
        private const val MAX_HEADER_LINE_CHARS = 8 * 1024
        private const val MAX_HEADER_CHARS = 32 * 1024
        private const val MAX_HEADER_COUNT = 32
        private const val MAX_REQUEST_BODY_BYTES = 64 * 1024
        private const val HTTP_WORKER_COUNT = 2
        private const val HTTP_QUEUE_CAPACITY = 16

        internal fun rootRedirectResponse(pairCodeProvider: () -> String): HttpResponse =
            redirectResponse(PairCodeManager.connectUrl(pairCodeProvider()))

        internal fun isAuthorized(headers: Map<String, String>, expectedToken: String): Boolean {
            if (expectedToken.isBlank()) return false
            val authorization = headers["authorization"] ?: return false
            val prefix = "Bearer "
            if (!authorization.startsWith(prefix)) return false
            return MessageDigest.isEqual(
                authorization.removePrefix(prefix).toByteArray(StandardCharsets.UTF_8),
                expectedToken.toByteArray(StandardCharsets.UTF_8),
            )
        }

        internal fun redirectResponse(location: String): HttpResponse =
            HttpResponse(
                statusCode = 302,
                reasonPhrase = "Found",
                headers = listOf("Location" to location),
            )

        internal fun serializeResponse(response: HttpResponse): ByteArray {
            require('\r' !in response.reasonPhrase && '\n' !in response.reasonPhrase) {
                "HTTP reason phrase contains CR or LF"
            }
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
        val ss = ServerSocket(port, 0, InetAddress.getByName("0.0.0.0"))
        serverSocket = ss
        running.set(true)
        acceptThread = Thread({
            try {
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
            val input = client.getInputStream()
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
                val line = readBoundedLine(input, MAX_HEADER_LINE_CHARS)
                    ?: run {
                        sendError(client, 400, "Incomplete request headers")
                        return
                    }
                if (line.isEmpty()) break
                headerCount++
                headerChars += line.length
                if (headerCount > MAX_HEADER_COUNT || headerChars > MAX_HEADER_CHARS) {
                    throw RequestTooLargeException()
                }
                val idx = line.indexOf(':')
                if (idx <= 0) {
                    sendError(client, 400, "Malformed request header")
                    return
                }
                headers[line.substring(0, idx).trim().lowercase()] =
                    line.substring(idx + 1).trim()
            }

            if (path != "/api/health" && !isAuthorized(headers, authTokenProvider())) {
                sendError(client, 401, "Unauthorized")
                return
            }

            var body = ""
            if (method == "POST") {
                if (headers["transfer-encoding"] != null) {
                    sendError(client, 411, "Chunked transfer encoding is not supported")
                    return
                }
                val contentLengthHeader = headers["content-length"]
                val contentLength = contentLengthHeader?.toLongOrNull()
                if (contentLength == null || contentLength < 0) {
                    sendError(client, 411, "Content-Length is required")
                    return
                }
                if (contentLength > MAX_REQUEST_BODY_BYTES) {
                    sendError(client, 413, "Request body too large")
                    return
                }
                val bytes = readExactly(input, contentLength.toInt())
                    ?: run {
                        sendError(client, 400, "Incomplete request body")
                        return
                    }
                body = bytes.toString(StandardCharsets.UTF_8)
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

    private fun readBoundedLine(input: InputStream, maxBytes: Int): String? {
        val line = ByteArray(maxBytes)
        var length = 0
        while (true) {
            val codePoint = input.read()
            if (codePoint < 0) return if (length > 0) String(line, 0, length, StandardCharsets.ISO_8859_1) else null
            if (codePoint == '\n'.code) {
                val contentLength = if (length > 0 && line[length - 1].toInt() == '\r'.code) length - 1 else length
                return String(line, 0, contentLength, StandardCharsets.ISO_8859_1)
            }
            if (length >= maxBytes) throw RequestTooLargeException()
            line[length++] = codePoint.toByte()
        }
    }

    private fun readExactly(input: InputStream, length: Int): ByteArray? {
        val bytes = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = input.read(bytes, offset, length - offset)
            if (read < 0) return null
            offset += read
        }
        return bytes
    }

    private class RequestTooLargeException : RuntimeException()

    private fun route(client: Socket, method: String, path: String, body: String) {
        when {
            method == "GET" && path == "/" ->
                sendResponse(client, rootRedirectResponse(pairCodeProvider))

            method == "GET" && path == "/api/health" ->
                sendJson(client, JSONObject().put("ok", true).put("service", "SweetSpot").put("apiVersion", 1).toString())

            method == "GET" && path == "/api/state" ->
                sendJson(client, stateJson())

            method == "GET" && path == "/api/profiles" ->
                sendJson(client, JSONObject().apply {
                    put("profiles", JSONArray().apply { engine.listProfiles().forEach { put(it) } })
                }.toString())

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
                sendJson(client, JSONObject().put("status", "started").toString())
            }

            method == "GET" && path == "/api/probe/status" ->
                sendJson(client, probeStatusJson())

            method == "POST" && path == "/api/probe/persist" -> {
                val bands = parseIntField(body, "bands") ?: 128
                if (bands != DynamicsProcessingEq.INTERNAL_BANDS) {
                    sendJson(client, JSONObject().apply {
                        put("status", "rejected")
                        put("error", "The diagnostic overlay requires exactly 64 bands")
                        put("bands", bands)
                    }.toString())
                } else {
                    serviceActions?.runPersistentProbe(bands)
                    sendJson(client, JSONObject().put("status", "persistent_started").put("bands", bands).toString())
                }
            }

            method == "POST" && path == "/api/probe/release" -> {
                serviceActions?.releasePersistentProbe()
                sendJson(client, JSONObject().put("status", "released").toString())
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
                sendJson(client, JSONObject().put("status", msg).put("curve", curve).toString())
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
                sendJson(client, JSONObject().put("status", "started").toString())
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
                sendJson(client, JSONObject().apply {
                    put("pairCode", code)
                    put("url", PairCodeManager.connectUrl(code))
                }.toString())
            }

            method == "POST" && path == "/api/paircode/rotate" -> {
                val code = pairCodeRotateProvider()
                sendJson(client, JSONObject().apply {
                    put("pairCode", code)
                    put("rotated", true)
                }.toString())
            }

            else -> sendError(client, 404, "Not Found")
        }
    }
    private fun stateJson(ok: Boolean? = null, error: String? = null): String {
        val caps = engine.getCapabilities()
        val levels = engine.getBandLevels()
        val ip = NetworkUtils.getLanIpAddress() ?: "unknown"
        val range = caps.bandLevelRange
        val presetMap = caps.presets
        val presetName = presetMap[engine.getActivePreset()] ?: "Custom"
        val calBands = serviceActions?.getCalibrationBands()
        val calFreqs = serviceActions?.getCalibrationFrequenciesHz()
        val calActive = serviceActions?.isCalibrationActive() ?: false
        return JSONObject().apply {
            if (ok != null) put("ok", ok)
            if (error != null) put("error", error)
            put("enabled", engine.isEnabled())
            put("hasControl", engine.hasControl())
            put("activePreset", engine.getActivePreset())
            put("presetName", presetName)
            put("ip", ip)
            put("port", port)
            put("bands", JSONArray().apply { levels.forEach { put(it) } })
            put("centerFrequenciesHz", JSONArray().apply { caps.centerFrequenciesHz.forEach { put(it) } })
            put("bandLevelRange", JSONArray().apply { range.forEach { put(it) } })
            put("overlayVisible", overlay?.isShown() ?: false)
            put("presets", JSONArray().apply {
                presetMap.entries.sortedBy { it.key }.forEach { (id, name) ->
                    put(JSONObject().put("id", id).put("name", name))
                }
            })
            put("profiles", JSONArray().apply { engine.listProfiles().forEach { put(it) } })
            if (calBands != null && calFreqs != null) {
                put("calibration", JSONObject().apply {
                    put("active", calActive)
                    put("bands", JSONArray().apply { calBands.forEach { put(it) } })
                    put("frequenciesHz", JSONArray().apply { calFreqs.forEach { put(it) } })
                })
            } else {
                put("calibration", JSONObject.NULL)
            }
        }.toString()
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
        if (inv == null || probes == null) return JSONObject().put("error", "not_run_yet").toString()
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
        return try { JSONObject(json).optInt(key, Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE } } catch (_: Exception) { null }
    }

    private fun parseStringField(json: String, key: String): String? {
        return try { JSONObject(json).optString(key).takeIf { it.isNotBlank() } } catch (_: Exception) { null }
    }

    private fun parseIntArrayField(json: String, key: String): IntArray? {
        return try {
            val array = JSONObject(json).optJSONArray(key) ?: return null
            IntArray(array.length()) { index -> array.optInt(index, Int.MIN_VALUE) }
                .takeIf { values -> values.all { it != Int.MIN_VALUE } }
        } catch (_: Exception) { null }
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
        } else JSONObject().put("active", false).put("bands", JSONArray()).put("frequenciesHz", JSONArray()).toString()
    }

    private fun parseFloatArrayField(json: String, key: String): FloatArray? {
        return try {
            val array = JSONObject(json).optJSONArray(key) ?: return null
            FloatArray(array.length()) { index -> array.optDouble(index, Double.NaN).toFloat() }
                .takeIf { values -> values.all { it.isFinite() } }
        } catch (_: Exception) { null }
    }
}
