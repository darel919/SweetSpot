package com.darelisme.sweetspot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import java.util.concurrent.Executors

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
class SweetSpotService : Service() {

    companion object {
        private const val TAG = "SweetSpot"

        const val ACTION_START = "com.darelisme.sweetspot.START"
        const val ACTION_PRESET = "com.darelisme.sweetspot.PRESET"
        const val ACTION_BYPASS = "com.darelisme.sweetspot.BYPASS"
        const val ACTION_PROBE = "com.darelisme.sweetspot.PROBE_DYNAMICS"
        const val EXTRA_PRESET = "preset"
        const val EXTRA_SHOW_UI = "showUi"

        private const val CHANNEL_ID = "sweetspot"
        private const val NOTIFICATION_ID = 1
    }

    private var engine: AudioEngine? = null
    private var webServer: WebServer? = null
    private var overlay: OverlayController? = null
    private lateinit var profileStore: ProfileStore

    // Serializes probe runs so a burst of broadcasts cannot overlap probes.
    private val probeExecutor = Executors.newSingleThreadExecutor()

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Service onCreate")
        profileStore = ProfileStore(this)
        createNotificationChannel()
        engine = EqualizerEngine(profileStore).also { it.initialize() }
        overlay = OverlayController(this)
        webServer = WebServer(this, engine!!, overlay).also { it.start() }
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
            ACTION_PROBE -> runDynamicsProcessingProbe()
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
     * [EqualizerEngine] or any saved profiles.
     */
    private fun runDynamicsProcessingProbe() {
        Log.i(TAG, "DynamicsProcessing probe requested via broadcast")
        probeExecutor.submit {
            try {
                DynamicsProcessingProbe().run()
            } catch (e: Throwable) {
                Log.e(TAG, "Probe execution error", e)
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
        probeExecutor.shutdownNow()
        super.onDestroy()
    }

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
