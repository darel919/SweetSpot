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

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Service onCreate")
        profileStore = ProfileStore(this)
        createNotificationChannel()
        engine = EqualizerEngine(profileStore).also { it.initialize() }
        overlay = OverlayController(this)
        webServer = WebServer(this, engine!!, overlay, this).also { it.start() }
        startForeground(NOTIFICATION_ID, buildNotification())
        Log.i(TAG, "Service started in foreground (engine + web server + overlay)")
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
                if (engine == null) engine = EqualizerEngine(profileStore).also { it.initialize() }
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
        probeExecutor.submit {
            try {
                probeRunning.set(true)
                lastProbeResults = DynamicsProcessingProbe().run()
            } catch (e: Throwable) {
                Log.e(TAG, "Probe execution error", e)
            } finally {
                probeRunning.set(false)
            }
        }
    }

    override fun onDestroy() {
        Log.i(TAG, "Service onDestroy — hiding overlay, stopping web server, releasing engine")
        stopForeground(STOP_FOREGROUND_REMOVE)
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
        probeExecutor.submit {
            try {
                // Replace any existing persistent instance first.
                persistentDp?.let {
                    DynamicsProcessingProbe().releaseInstance(it)
                    Log.i(TAG, "Released previous persistent DynamicsProcessing instance")
                }
                val dp = DynamicsProcessingProbe().createEnabled(bands)
                persistentDp = dp
                persistentBands = bands
                Log.i(TAG, "=== Persistent DynamicsProcessing ACTIVE ===")
                Log.i(TAG, "Bands: $bands | Session: 0 (global output mix) | Enabled: ${dp.enabled}")
                Log.i(TAG, "Instance is intentionally left ENABLED for memory/CPU/reliability checks.")
                Log.i(TAG, "Release via web (/api/probe/release) or broadcast PROBE_RELEASE.")
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
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to release persistent instance", e)
            }
        }
    }

    // --- ServiceActions (web-facing) ---
    override fun getLastProbeResults(): List<DynamicsProcessingProbe.ProbeResult>? = lastProbeResults
    override fun isProbeRunning(): Boolean = probeRunning.get()
    override fun isPersistentProbeActive(): Boolean = persistentDp != null
    override fun getPersistentProbeBands(): Int = persistentBands

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
