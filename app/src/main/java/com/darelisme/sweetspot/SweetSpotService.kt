package com.darelisme.sweetspot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.audiofx.DynamicsProcessing
import android.os.IBinder
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Long-lived owner of the audio DSP objects and the control web server.
 *
 * This service is the single owner of the [AudioEngine] (which wraps the global
 * [android.media.audiofx.Equalizer] on session 0) and the embedded [WebServer].
 * It survives [MainActivity] leaving the foreground and keeps the effect and the
 * server alive for the lifetime of the service.
 *
 * Commands are delivered as Intents (see [ACTION_PRESET], [ACTION_BYPASS]).
 * The service itself is not exported; external callers (e.g. ADB during
 * development) go through [SweetSpotCommandReceiver].
 */
class SweetSpotService : Service(), ServiceActions {

    companion object {
        private const val TAG = "SweetSpot"

        const val ACTION_START = "com.darelisme.sweetspot.START"
        const val ACTION_PRESET = "com.darelisme.sweetspot.PRESET"
        const val ACTION_BYPASS = "com.darelisme.sweetspot.BYPASS"
        const val ACTION_PROBE = "com.darelisme.sweetspot.PROBE_DYNAMICS"
        const val ACTION_PROBE_PERSIST = "com.darelisme.sweetspot.PROBE_PERSIST"
        const val ACTION_PROBE_RELEASE = "com.darelisme.sweetspot.PROBE_RELEASE"
        const val EXTRA_PRESET = "preset"
        const val EXTRA_SHOW_UI = "showUi"
        const val EXTRA_PROBE_BANDS = "probeBands"

        private const val CHANNEL_ID = "sweetspot"
        private const val NOTIFICATION_ID = 1
    }

    private var engine: AudioEngine? = null
    private var webServer: WebServer? = null
    private var overlay: OverlayController? = null
    private var relay: MailboxClient? = null
    private val pairCodes = PairCodeManager()
    private lateinit var profileStore: ProfileStore

    // Serializes probe runs so a burst of broadcasts cannot overlap probes.
    private val probeExecutor = Executors.newSingleThreadExecutor()

    // Long-lived DynamicsProcessing instance for memory/CPU/reliability checks.
    // Null unless a persistent probe is currently active.
    private var persistentDp: DynamicsProcessing? = null

    // Last capacity-probe results, surfaced to the web UI.
    private var lastProbeResults: List<DynamicsProcessingProbe.ProbeResult>? = null
    private val probeRunning = AtomicBoolean(false)
    private var persistentBands: Int = 0
    private var persistentCurveName: String? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Service onCreate")
        profileStore = ProfileStore(this)
        createNotificationChannel()
        engine = DynamicsProcessingEq(profileStore).also { it.initialize() }
        overlay = OverlayController(this).also {
            it.updatePairInfo(pairCodes.current())
            it.show()
        }
        webServer = WebServer(engine!!, overlay, this).also {
            it.pairCodeProvider = { pairCodes.current() }
            it.pairCodeRotateProvider = {
                pairCodes.rotate().also { code -> overlay?.updatePairInfo(code) }
            }
            it.start()
        }
        relay = MailboxClient(
            roomProvider = { pairCodes.current() },
            snapshotProvider = { stateSnapshotJson() },
            effectsDiagnosticsProvider = { runEffectDiagnosticsBlocking() }
        ).also { client ->
            client.listener = object : MailboxClient.Listener {
                override fun onDeviceOnline(online: Boolean) {
                    overlay?.updateRelayState(
                        if (online) OverlayController.RELAY_WAITING else OverlayController.RELAY_CONNECTING
                    )
                }

                override fun onClientPresence(present: Boolean) {
                    overlay?.updateRelayState(
                        if (present) OverlayController.RELAY_CONNECTED else OverlayController.RELAY_WAITING
                    )
                }
            }
            client.start()
        }
        startForeground(NOTIFICATION_ID, buildNotification())
        Log.i(TAG, "Service started in foreground (engine + web server + overlay + relay)")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PRESET -> {
                val preset = intent.getIntExtra(EXTRA_PRESET, 1)
                engine?.applyPreset(preset)
            }
            ACTION_BYPASS -> engine?.setEnabled(false)
            ACTION_PROBE -> runProbe()
            ACTION_PROBE_PERSIST -> {
                val bands = intent.getIntExtra(EXTRA_PROBE_BANDS, 128)
                runPersistentProbe(bands)
            }
            ACTION_PROBE_RELEASE -> releasePersistentProbe()
            ACTION_START -> {
                // Already initialized in onCreate; ensure still alive.
                if (engine == null) engine = DynamicsProcessingEq(profileStore).also { it.initialize() }
                val showUi = intent.getBooleanExtra(EXTRA_SHOW_UI, true)
                if (showUi) overlay?.show() else overlay?.hide()
            }
            else -> Log.d(TAG, "onStartCommand: no/unknown action (intent=$intent)")
        }
        // Restart if the system kills the service.
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Runs the temporary DynamicsProcessing band-capacity probe off the main
     * thread. This is strictly diagnostic and never touches the production
     * [EqualizerEngine] or any saved profiles. Results are captured for the web UI.
     */
    override fun runProbe() {
        Log.i(TAG, "DynamicsProcessing probe requested")
        suspendProduction()
        probeExecutor.submit {
            try {
                probeRunning.set(true)
                lastProbeResults = DynamicsProcessingProbe().run()
            } catch (e: Throwable) {
                Log.e(TAG, "Probe execution error", e)
            } finally {
                probeRunning.set(false)
                resumeProduction()
            }
        }
    }

    override fun onDestroy() {
        Log.i(TAG, "Service onDestroy — hiding overlay, stopping web server, releasing engine, closing relay")
        stopForeground(STOP_FOREGROUND_REMOVE)
        relay?.stop()
        relay = null
        webServer?.stop()
        webServer = null
        overlay?.hide()
        overlay = null
        engine?.release()
        engine = null
        // Release any long-lived probe instance still held.
        DynamicsProcessingProbe().releaseInstance(persistentDp)
        persistentDp = null
        probeExecutor.shutdownNow()
        super.onDestroy()
    }

    /**
     * Creates a long-lived, enabled [DynamicsProcessing] instance for [bands]
     * bands on session 0 and keeps it alive (does NOT release it). This lets
     * the developer measure memory/CPU and run reliability checks with a real
     * enabled effect in place. Release it with [ACTION_PROBE_RELEASE] or the
     * web endpoint.
     */
    override fun runPersistentProbe(bands: Int) {
        Log.i(TAG, "Persistent DynamicsProcessing probe requested: $bands bands")
        suspendProduction()
        probeExecutor.submit {
            try {
                // Replace any existing persistent instance first.
                persistentDp?.let {
                    DynamicsProcessingProbe().releaseInstance(it)
                    Log.i(TAG, "Released previous persistent DynamicsProcessing instance")
                }
                persistentCurveName = null
                // Prefer stereo so both channels of the global mix are affected;
                // fall back to mono if the 2-channel config is rejected.
                var usedChannels = 2
                val dp = try {
                    DynamicsProcessingProbe().createEnabled(bands, 2)
                } catch (e: Throwable) {
                    Log.w(TAG, "2-channel persistent instance failed ($bands bands); falling back to 1 channel", e)
                    usedChannels = 1
                    DynamicsProcessingProbe().createEnabled(bands, 1)
                }
                persistentDp = dp
                persistentBands = bands
                Log.i(TAG, "=== Persistent DynamicsProcessing ACTIVE ===")
                Log.i(TAG, "Bands: $bands | Channels: $usedChannels | Session: 0 (global output mix) | Enabled: ${dp.enabled}")
                Log.i(TAG, "Instance is intentionally left ENABLED for memory/CPU/reliability checks.")
                Log.i(TAG, "Apply a test curve via web (/api/probe/apply-curve) or release via /api/probe/release.")
            } catch (e: Throwable) {
                Log.e(TAG, "Persistent probe failed for $bands bands", e)
            }
        }
    }

    /** Releases the long-lived DynamicsProcessing instance, if any. */
    override fun releasePersistentProbe() {
        probeExecutor.submit {
            try {
                persistentDp?.let {
                    DynamicsProcessingProbe().releaseInstance(it)
                    Log.i(TAG, "Persistent DynamicsProcessing instance released")
                } ?: Log.i(TAG, "No persistent DynamicsProcessing instance to release")
                persistentDp = null
                persistentBands = 0
                persistentCurveName = null
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to release persistent instance", e)
            } finally {
                resumeProduction()
            }
        }
    }

    // --- ServiceActions (web-facing) ---
    override fun getLastProbeResults(): List<DynamicsProcessingProbe.ProbeResult>? = lastProbeResults
    override fun isProbeRunning(): Boolean = probeRunning.get()
    override fun isPersistentProbeActive(): Boolean = persistentDp != null
    override fun getPersistentProbeBands(): Int = persistentBands

    override fun applyPersistentCurve(curve: String): Boolean {
        val dp = persistentDp ?: return false
        val n = persistentBands
        return try {
            when (curve) {
                "hollow" -> DynamicsProcessingProbe().applyHollowCurve(dp, n)
                "flat" -> DynamicsProcessingProbe().applyFlatCurve(dp, n)
                else -> return false
            }
            persistentCurveName = curve
            true
        } catch (e: Throwable) {
            Log.e(TAG, "applyPersistentCurve($curve) failed", e)
            false
        }
    }

    override fun getPersistentProbeCurve(): String? = persistentCurveName

    // --- Effect chain diagnostics (matrixing capability check) ---

    @Volatile private var effectInventory: List<AudioEffectDiagnostics.EffectInventoryEntry>? = null
    @Volatile private var sessionProbes: List<AudioEffectDiagnostics.SessionProbe>? = null

    /** Runs diagnostics synchronously; called from the mailbox poll thread. */
    fun runEffectDiagnosticsBlocking(): JSONObject {
        suspendProduction()
        try {
            val (inv, probes) = AudioEffectDiagnostics().runAll()
            effectInventory = inv
            sessionProbes = probes
            return AudioEffectDiagnostics.payloadJson(inv, probes)
        } finally {
            resumeProduction()
        }
    }

    override fun runEffectDiagnostics() {
        Log.i(TAG, "Audio effect diagnostics requested")
        suspendProduction()
        probeExecutor.submit {
            try {
                val (inv, probes) = AudioEffectDiagnostics().runAll()
                effectInventory = inv
                sessionProbes = probes
            } catch (e: Throwable) {
                Log.e(TAG, "Effect diagnostics error", e)
            } finally {
                resumeProduction()
            }
        }
    }

    override fun getEffectInventory(): List<AudioEffectDiagnostics.EffectInventoryEntry> =
        effectInventory ?: emptyList()

    override fun getSessionProbes(): List<AudioEffectDiagnostics.SessionProbe> =
        sessionProbes ?: emptyList()

    override fun getPersistentProbeCurveSummary(): DynamicsProcessingProbe.CurveSummary? {
        val dp = persistentDp ?: return null
        return try {
            DynamicsProcessingProbe().readCurveSummary(dp, persistentBands)
        } catch (_: Throwable) { null }
    }

    // --- Calibration (read-only base curve; wizard/API only) ---

    private fun dpEq() = engine as? DynamicsProcessingEq

    override fun getCalibrationBands(): FloatArray? = dpEq()?.getCalibrationBands()
    override fun getCalibrationFrequenciesHz(): IntArray? = dpEq()?.getCalibrationFrequenciesHz()
    override fun isCalibrationActive(): Boolean = dpEq()?.isCalibrationActive() ?: false
    override fun setCalibrationBands(gains: FloatArray): Boolean = dpEq()?.setCalibrationBands(gains) ?: false
    override fun resetCalibration(): Boolean { dpEq()?.resetCalibration(); return true }

    /** Release the production engine so a diagnostic DynamicsProcessing can own session 0. */
    private fun suspendProduction() {
        engine?.release()
    }

    /** Re-create and restore the production engine after diagnostics. */
    private fun resumeProduction() {
        engine?.initialize()
    }

    /**
     * Canonical state snapshot for the hosted dashboard (protocol v1).
     * Mirrors shared/types/protocol.ts StateSnapshot in sweetspot-web.
     */
    private fun stateSnapshotJson(): JSONObject {
        val engine = engine ?: DynamicsProcessingEq(profileStore)
        val caps = engine.getCapabilities()
        val calBands = dpEq()?.getCalibrationBands() ?: FloatArray(0)
        val calFreqs = dpEq()?.getCalibrationFrequenciesHz() ?: IntArray(0)
        val userLevels = engine.getBandLevels()
        return JSONObject().apply {
            put("device", JSONObject().apply {
                put("id", DeviceIdentity.get(this@SweetSpotService))
                put("name", DeviceIdentity.getName(this@SweetSpotService))
                put("appVersion", "0.1.0")
            })
            put("engine", JSONObject().apply {
                put("enabled", engine.isEnabled())
                put("hasControl", engine.hasControl())
                put("activePreset", engine.getActivePreset())
                put("presetName", caps.presets[engine.getActivePreset()] ?: "Custom")
            })
            put("userEq", JSONObject().apply {
                put("bandsDb", JSONArray(userLevels.map { it / 100.0 }))
                put("frequenciesHz", JSONArray(caps.centerFrequenciesHz.toList()))
                put("minDb", caps.bandLevelRange[0] / 100.0)
                put("maxDb", caps.bandLevelRange[1] / 100.0)
            })
            put("calibration", JSONObject().apply {
                put("active", serviceActionsIsCalibrationActive())
                put("bandsDb", JSONArray(calBands.toList()))
                put("frequenciesHz", JSONArray(calFreqs.toList()))
            })
            put("profiles", JSONArray(engine.listProfiles().map { name ->
                JSONObject().put("id", name).put("name", name)
            }))
            put("capabilities", JSONObject().apply {
                put("channels", 2)
                put("calibrationBandCount", if (calBands.isNotEmpty()) calBands.size else 64)
                put("userBandCount", caps.bandCount)
                put("supportsSweep", false)
            })
        }
    }

    private fun serviceActionsIsCalibrationActive(): Boolean = try {
        (engine as? DynamicsProcessingEq)?.isCalibrationActive() ?: false
    } catch (_: Exception) { false }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "SweetSpot",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Persistent audio tuning service"
            setShowBadge(false)
        }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("SweetSpot")
            .setContentText("Audio tuning active")
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .build()
    }
}
