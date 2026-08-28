package com.darelisme.sweetspot.diagnostics

import android.media.audiofx.Virtualizer
import android.os.Debug
import android.system.Os
import android.system.OsConstants
import android.util.Log
import com.darelisme.sweetspot.audio.diagnostics.AudioEffectDiagnostics
import com.darelisme.sweetspot.audio.diagnostics.DynamicsProcessingProbe
import com.darelisme.sweetspot.audio.engine.AudioOperationGate
import com.darelisme.sweetspot.audio.engine.DynamicsProcessingEq
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.pow

internal interface SweetSpotDiagnosticsAudioPort {
    fun currentEq(): DynamicsProcessingEq?
    fun isMeasurementActive(): Boolean
    fun suspendProduction()
    fun resumeProduction()
}

interface SweetSpotDiagnostics {
    val lastProbeResults: List<DynamicsProcessingProbe.ProbeResult>?
    val probeRunning: Boolean
    val persistentProbeBands: Int
    val persistentProbeCurveName: String?
    val persistentProbeError: String?

    fun runProbe(bands: Int? = null, onComplete: () -> Unit = {}): Boolean
    fun runPersistentProbe(bands: Int)
    fun releasePersistentProbe()
    fun isPersistentProbeActive(): Boolean
    fun getPersistentProbeCurve(): String?
    fun getPersistentProbeCurveSummary(channel: Int = 0): DynamicsProcessingProbe.CurveSummary?
    fun applyPersistentCurve(curve: String): Boolean
    fun applyPersistentBands(common: FloatArray, left: FloatArray? = null, right: FloatArray? = null): Boolean
    fun setVirtualizer(on: Boolean)
    fun runEffectDiagnosticsBlocking(): JSONObject
    fun runEffectDiagnostics()
    fun getEffectInventory(): List<AudioEffectDiagnostics.EffectInventoryEntry>
    fun getSessionProbes(): List<AudioEffectDiagnostics.SessionProbe>
    fun deviceInfoJson(): JSONObject
    fun shutdown()
}

internal class SweetSpotDiagnosticsCoordinator(
    private val operationGate: AudioOperationGate,
    private val audioPort: SweetSpotDiagnosticsAudioPort,
) : SweetSpotDiagnostics {
    companion object {
        private const val TAG = "SweetSpot"
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val probeRunningState = AtomicBoolean(false)

    @Volatile
    override var lastProbeResults: List<DynamicsProcessingProbe.ProbeResult>? = null
        private set

    @Volatile
    private var persistentProbeLeaseHeld = false

    @Volatile
    override var persistentProbeError: String? = null
        private set

    @Volatile
    private var persistentVirtualizer: Virtualizer? = null

    @Volatile
    private var persistentBandsState = 0

    @Volatile
    private var persistentCurveNameState: String? = null

    @Volatile
    private var effectInventory: List<AudioEffectDiagnostics.EffectInventoryEntry>? = null

    @Volatile
    private var sessionProbes: List<AudioEffectDiagnostics.SessionProbe>? = null

    override val probeRunning: Boolean
        get() = probeRunningState.get()

    override val persistentProbeBands: Int
        get() = persistentBandsState

    override val persistentProbeCurveName: String?
        get() = persistentCurveNameState

    override fun runProbe(bands: Int?, onComplete: () -> Unit): Boolean {
        Log.i(TAG, "DynamicsProcessing probe requested")
        if (!tryAcquireDiagnosticOperation()) {
            Log.w(TAG, "Rejecting overlapping or calibration-active DynamicsProcessing probe")
            return false
        }
        executor.submit {
            try {
                audioPort.suspendProduction()
                probeRunningState.set(true)
                lastProbeResults = if (bands == null) {
                    DynamicsProcessingProbe().run()
                } else {
                    DynamicsProcessingProbe().runFor(bands)
                }
            } catch (error: Throwable) {
                Log.e(TAG, "Probe execution error", error)
            } finally {
                probeRunningState.set(false)
                audioPort.resumeProduction()
                operationGate.releaseTransient()
            }
            onComplete()
        }
        return true
    }

    override fun runPersistentProbe(bands: Int) {
        Log.i(TAG, "Persistent DynamicsProcessing probe requested: $bands bands")
        if (!tryAcquireDiagnosticOperation()) {
            Log.w(TAG, "Rejecting persistent probe while another audio operation is active")
            return
        }
        executor.submit {
            var keepLease = false
            try {
                persistentProbeError = null
                persistentCurveNameState = null
                val eq = audioPort.currentEq()
                    ?: throw IllegalStateException("Production DynamicsProcessing is not initialized")
                if (!eq.clearDiagnosticProbe()) {
                    throw IllegalStateException("Could not restore production EQ before starting the diagnostic overlay")
                }
                if (bands != DynamicsProcessingEq.INTERNAL_BANDS) {
                    Log.w(TAG, "Diagnostic transfer probe requires ${DynamicsProcessingEq.INTERNAL_BANDS} bands; requested $bands")
                    persistentBandsState = 0
                    persistentProbeError = "Diagnostic transfer probe requires exactly ${DynamicsProcessingEq.INTERNAL_BANDS} bands"
                    return@submit
                }
                if (eq.getChannelCount() < 1) {
                    throw IllegalStateException("Production DynamicsProcessing has no channels")
                }
                persistentBandsState = DynamicsProcessingEq.INTERNAL_BANDS
                if (!eq.applyDiagnosticProbe(FloatArray(DynamicsProcessingEq.INTERNAL_BANDS))) {
                    throw IllegalStateException("Production DynamicsProcessing rejected the flat diagnostic overlay")
                }
                persistentCurveNameState = "flat"
                if (!operationGate.promoteToPersistent()) {
                    throw IllegalStateException("Persistent diagnostic probe could not retain the audio-operation lease")
                }
                persistentProbeLeaseHeld = true
                keepLease = true
                Log.i(TAG, "=== Persistent DynamicsProcessing ACTIVE ===")
                Log.i(TAG, "Bands: $persistentBandsState | Channels: ${eq.getChannelCount()} | Session: 0 (production owner)")
                Log.i(TAG, "Diagnostic overlay is ready; it is not persisted and must be released after the experiment.")
            } catch (error: Throwable) {
                Log.e(TAG, "Persistent probe failed for $bands bands", error)
                persistentBandsState = 0
                persistentCurveNameState = null
                persistentProbeError = error.message ?: "Persistent diagnostic probe failed"
            } finally {
                if (!keepLease) {
                    persistentProbeLeaseHeld = false
                    operationGate.releaseTransient()
                }
            }
        }
    }

    override fun releasePersistentProbe() {
        executor.submit { releasePersistentProbeInternal() }
    }

    private fun releasePersistentProbeInternal() {
        if (!persistentProbeLeaseHeld && audioPort.currentEq()?.isDiagnosticProbeActive() != true) return
        try {
            val eq = audioPort.currentEq()
                ?: throw IllegalStateException("Production DynamicsProcessing is not initialized")
            if (!eq.clearDiagnosticProbe() || eq.isDiagnosticProbeActive() || !eq.isLiveDspVerified()) {
                throw IllegalStateException("Production DynamicsProcessing restoration could not be verified")
            }
            persistentProbeLeaseHeld = false
            persistentBandsState = 0
            persistentCurveNameState = null
            persistentProbeError = null
            operationGate.releasePersistent()
            Log.i(TAG, "Diagnostic DynamicsProcessing probe released")
        } catch (error: Throwable) {
            persistentProbeError = error.message ?: "Persistent diagnostic probe release failed"
            Log.e(TAG, "Failed to release persistent instance", error)
        }
    }

    private fun releasePersistentProbeBlocking() {
        if (!operationGate.isHeld() && audioPort.currentEq()?.isDiagnosticProbeActive() != true) return
        val done = CountDownLatch(1)
        try {
            executor.submit {
                try {
                    releasePersistentProbeInternal()
                } finally {
                    done.countDown()
                }
            }
            done.await(2, TimeUnit.SECONDS)
        } catch (error: Throwable) {
            Log.e(TAG, "Failed to schedule persistent probe release during shutdown", error)
        }
    }

    override fun isPersistentProbeActive(): Boolean =
        persistentProbeLeaseHeld || audioPort.currentEq()?.isDiagnosticProbeActive() == true

    override fun getPersistentProbeCurve(): String? =
        if (isPersistentProbeActive()) persistentCurveNameState else null

    override fun getPersistentProbeCurveSummary(channel: Int): DynamicsProcessingProbe.CurveSummary? {
        if (!isPersistentProbeActive() || persistentBandsState != DynamicsProcessingEq.INTERNAL_BANDS) return null
        return audioPort.currentEq()?.getDiagnosticProbeCurveSummary(channel)
    }

    override fun applyPersistentCurve(curve: String): Boolean {
        val eq = audioPort.currentEq() ?: return false
        if (!eq.isDiagnosticProbeActive() || persistentBandsState != DynamicsProcessingEq.INTERNAL_BANDS) return false
        val n = DynamicsProcessingEq.INTERNAL_BANDS
        return try {
            val common = FloatArray(n) { index ->
                val freq = DynamicsProcessingEq.F_MIN.toFloat() *
                    (DynamicsProcessingEq.F_MAX.toFloat() / DynamicsProcessingEq.F_MIN.toFloat()).pow((index + 1).toFloat() / n)
                when (curve) {
                    "hollow" -> if (freq >= 300f && freq < 3000f) -15f else 0f
                    "flat" -> 0f
                    else -> return false
                }
            }
            if (!eq.applyDiagnosticProbe(common)) return false
            persistentCurveNameState = curve
            true
        } catch (error: Throwable) {
            Log.e(TAG, "applyPersistentCurve($curve) failed", error)
            false
        }
    }

    override fun applyPersistentBands(common: FloatArray, left: FloatArray?, right: FloatArray?): Boolean {
        val eq = audioPort.currentEq() ?: return false
        val n = DynamicsProcessingEq.INTERNAL_BANDS
        if (!eq.isDiagnosticProbeActive() || persistentBandsState != n) return false
        if (common.size != n || (left == null) != (right == null)) return false
        if (common.any { !it.isFinite() || it < DynamicsProcessingProbe.MIN_PROBE_GAIN_DB || it > DynamicsProcessingProbe.MAX_PROBE_GAIN_DB }) return false
        if (left != null) {
            val rightCurve = right ?: return false
            if (left.size != n || rightCurve.size != n) return false
            if (left.any { !it.isFinite() || it < DynamicsProcessingProbe.MIN_PROBE_GAIN_DB || it > DynamicsProcessingProbe.MAX_PROBE_GAIN_DB } ||
                rightCurve.any { !it.isFinite() || it < DynamicsProcessingProbe.MIN_PROBE_GAIN_DB || it > DynamicsProcessingProbe.MAX_PROBE_GAIN_DB }) return false
        }
        return try {
            val applied = eq.applyDiagnosticProbe(common, left, right)
            if (applied) persistentCurveNameState = "custom"
            applied
        } catch (error: Throwable) {
            Log.e(TAG, "applyPersistentBands failed", error)
            false
        }
    }

    override fun setVirtualizer(on: Boolean) {
        try {
            val existing = persistentVirtualizer
            val virtualizer = existing ?: Virtualizer(1000, 0).also {
                if (it.strengthSupported) it.setStrength(1000)
                persistentVirtualizer = it
            }
            virtualizer.enabled = on
            Log.i(TAG, "Persistent Virtualizer $on (control=${virtualizer.hasControl()}, enabled=${virtualizer.enabled}, strength=${virtualizer.roundedStrength})")
        } catch (error: Throwable) {
            Log.e(TAG, "Virtualizer set($on) failed", error)
        }
    }

    override fun runEffectDiagnosticsBlocking(): JSONObject {
        if (!tryAcquireDiagnosticOperation()) {
            return JSONObject().put("error", "Diagnostics are unavailable while calibration is active")
        }
        try {
            audioPort.suspendProduction()
            val (inventory, probes) = AudioEffectDiagnostics().runAll()
            effectInventory = inventory
            sessionProbes = probes
            return AudioEffectDiagnostics.payloadJson(inventory, probes)
        } finally {
            audioPort.resumeProduction()
            operationGate.releaseTransient()
        }
    }

    override fun runEffectDiagnostics() {
        Log.i(TAG, "Audio effect diagnostics requested")
        if (!tryAcquireDiagnosticOperation()) {
            Log.w(TAG, "Rejecting diagnostics while another audio operation is active")
            return
        }
        executor.submit {
            try {
                audioPort.suspendProduction()
                val (inventory, probes) = AudioEffectDiagnostics().runAll()
                effectInventory = inventory
                sessionProbes = probes
            } catch (error: Throwable) {
                Log.e(TAG, "Effect diagnostics error", error)
            } finally {
                audioPort.resumeProduction()
                operationGate.releaseTransient()
            }
        }
    }

    override fun getEffectInventory(): List<AudioEffectDiagnostics.EffectInventoryEntry> =
        effectInventory ?: emptyList()

    override fun getSessionProbes(): List<AudioEffectDiagnostics.SessionProbe> =
        sessionProbes ?: emptyList()

    override fun deviceInfoJson(): JSONObject {
        val runtime = Runtime.getRuntime()
        val memoryInfo = Debug.MemoryInfo()
        Debug.getMemoryInfo(memoryInfo)
        val audioServerPid = findProcessPid("audioserver") ?: findProcessPid("audioserver64")
        val appPid = android.os.Process.myPid()
        val appStartTicks = cpuTicks(appPid)
        val audioStartTicks = audioServerPid?.let(::cpuTicks)
        val start = System.nanoTime()
        try {
            Thread.sleep(400)
        } catch (_: InterruptedException) {
        }
        val wallSeconds = (System.nanoTime() - start) / 1e9
        val appCpu = cpuPercent(appStartTicks, cpuTicks(appPid), wallSeconds)
        val audioServerCpu = if (audioServerPid != null && audioStartTicks != null) {
            cpuPercent(audioStartTicks, cpuTicks(audioServerPid), wallSeconds)
        } else {
            0.0
        }
        return JSONObject().apply {
            put("javaHeapMax", runtime.maxMemory())
            put("javaHeapTotal", runtime.totalMemory())
            put("javaHeapFree", runtime.freeMemory())
            put("nativeHeapAllocated", Debug.getNativeHeapAllocatedSize())
            put("nativeHeapSize", Debug.getNativeHeapSize())
            put("pssTotalKb", memoryInfo.totalPss)
            put("privateDirtyKb", memoryInfo.totalPrivateDirty)
            put("cpuPercent", appCpu)
            put("audioserverCpuPercent", audioServerCpu)
            put("audioserverPid", audioServerPid ?: JSONObject.NULL)
            put("persistentProbeActive", isPersistentProbeActive())
            put("persistentProbeBands", if (isPersistentProbeActive()) persistentBandsState else 0)
        }
    }

    override fun shutdown() {
        releasePersistentProbeBlocking()
        try {
            persistentVirtualizer?.release()
        } catch (_: Throwable) {
        }
        persistentVirtualizer = null
        persistentProbeLeaseHeld = false
        persistentBandsState = 0
        persistentCurveNameState = null
        persistentProbeError = null
        executor.shutdownNow()
    }

    private fun tryAcquireDiagnosticOperation(): Boolean {
        if (!operationGate.tryAcquireTransient()) return false
        if (audioPort.isMeasurementActive() || audioPort.currentEq()?.isDiagnosticProbeActive() == true) {
            operationGate.releaseTransient()
            return false
        }
        return true
    }

    private fun cpuTicks(pid: Int): Long = try {
        val stat = File("/proc/$pid/stat").readText()
        val parts = stat.substring(stat.lastIndexOf(')') + 1).trim().split("\\s+".toRegex())
        (parts.getOrNull(12)?.toLongOrNull() ?: 0L) + (parts.getOrNull(13)?.toLongOrNull() ?: 0L)
    } catch (_: Throwable) {
        0L
    }

    private fun cpuPercent(startTicks: Long, endTicks: Long, wallSeconds: Double): Double {
        val clockTicks = try {
            Os.sysconf(OsConstants._SC_CLK_TCK).toDouble()
        } catch (_: Throwable) {
            100.0
        }
        return if (wallSeconds > 0) ((endTicks - startTicks) / clockTicks / wallSeconds) * 100.0 else 0.0
    }

    private fun findProcessPid(name: String): Int? = try {
        File("/proc").list()?.firstOrNull { entry ->
            val pid = entry.toIntOrNull() ?: return@firstOrNull false
            try {
                File("/proc/$pid/stat").readText().let { stat ->
                    val start = stat.indexOf('(')
                    val end = stat.lastIndexOf(')')
                    start in 0 until end && stat.substring(start + 1, end) == name
                }
            } catch (_: Throwable) {
                false
            }
        }?.toIntOrNull()
    } catch (_: Throwable) {
        null
    }
}
