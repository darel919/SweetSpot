package com.darelisme.sweetspot.service

import com.darelisme.sweetspot.BuildConfig
import com.darelisme.sweetspot.R
import com.darelisme.sweetspot.audio.diagnostics.AudioEffectDiagnostics
import com.darelisme.sweetspot.audio.diagnostics.DynamicsProcessingProbe
import com.darelisme.sweetspot.audio.engine.AudioEngine
import com.darelisme.sweetspot.audio.engine.AudioOperationGate
import com.darelisme.sweetspot.audio.engine.DynamicsProcessingEq
import com.darelisme.sweetspot.audio.engine.ProfileStore
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.darelisme.sweetspot.calibration.analysis.AndroidResponseV1Analyzer
import com.darelisme.sweetspot.calibration.capture.CalibrationCaptureStore
import com.darelisme.sweetspot.calibration.transport.CalibrationCaptureStreamReceiver
import com.darelisme.sweetspot.calibration.CalibrationEngine
import com.darelisme.sweetspot.calibration.CalibrationEngineListener
import com.darelisme.sweetspot.calibration.CalibrationEngineResult
import com.darelisme.sweetspot.calibration.model.CalibrationJob
import com.darelisme.sweetspot.calibration.model.CalibrationJobId
import com.darelisme.sweetspot.calibration.model.CalibrationJobJson
import com.darelisme.sweetspot.calibration.model.CalibrationPhase
import com.darelisme.sweetspot.calibration.model.CaptureAttempt
import com.darelisme.sweetspot.calibration.model.CaptureId
import com.darelisme.sweetspot.calibration.dsp.TvCalibrationDsp
import com.darelisme.sweetspot.calibration.persistence.CalibrationJobStore
import com.darelisme.sweetspot.calibration.playback.TvCalibrationPlayback
import com.darelisme.sweetspot.calibration.model.CalibrationCandidateTransaction
import com.darelisme.sweetspot.calibration.model.CalibrationValidationStatus
import com.darelisme.sweetspot.calibration.playback.MeasurementController
import com.darelisme.sweetspot.diagnostics.SweetSpotDiagnostics
import com.darelisme.sweetspot.diagnostics.SweetSpotDiagnosticsAudioPort
import com.darelisme.sweetspot.diagnostics.SweetSpotDiagnosticsCoordinator
import com.darelisme.sweetspot.calibration.playback.MeasurementSweep
import com.darelisme.sweetspot.pairing.DeviceIdentity
import com.darelisme.sweetspot.pairing.PairingSessionManager
import com.darelisme.sweetspot.server.WebServer
import com.darelisme.sweetspot.server.ServiceActions
import com.darelisme.sweetspot.transport.PeerTransport
import com.darelisme.sweetspot.transport.PeerTransportDiagnostics
import com.darelisme.sweetspot.transport.PeerTransportState
import com.darelisme.sweetspot.transport.webrtc.WebRtcPeerTransport
import com.darelisme.sweetspot.ui.OverlayController
import com.darelisme.sweetspot.ui.OverlayActions
import com.darelisme.sweetspot.ui.OverlayPresetOption
import com.darelisme.sweetspot.ui.OverlayState
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * Long-lived owner of the audio DSP objects and the control web server.
 *
 * This service is the single owner of the [AudioEngine] (which applies global
 * DSP through [DynamicsProcessingEq]) and the embedded [WebServer].
 * It survives [MainActivity] leaving the foreground and keeps the effect and the
 * server alive for the lifetime of the service.
 *
 * Commands are delivered as Intents (see [ACTION_PRESET], [ACTION_BYPASS]).
 * The service itself is not exported; external callers (e.g. ADB during
 * development) go through [SweetSpotCommandReceiver].
 */
class SweetSpotService : Service(), ServiceActions, SweetSpotPeerCommandHost {

    companion object {
        private const val TAG = "SweetSpot"

        const val ACTION_START = "com.darelisme.sweetspot.START"
        const val ACTION_PRESET = "com.darelisme.sweetspot.PRESET"
        const val ACTION_BYPASS = "com.darelisme.sweetspot.BYPASS"
        const val ACTION_PROBE = "com.darelisme.sweetspot.PROBE_DYNAMICS"
        const val ACTION_PROBE_PERSIST = "com.darelisme.sweetspot.PROBE_PERSIST"
        const val ACTION_PROBE_RELEASE = "com.darelisme.sweetspot.PROBE_RELEASE"
        const val ACTION_CALIBRATION_UI_READY = "com.darelisme.sweetspot.CALIBRATION_UI_READY"
        const val ACTION_CALIBRATION_UI_CLOSED = "com.darelisme.sweetspot.CALIBRATION_UI_CLOSED"
        const val ACTION_CALIBRATION_CANCEL = "com.darelisme.sweetspot.CALIBRATION_CANCEL"
        const val ACTION_CALIBRATION_CONTINUE = "com.darelisme.sweetspot.CALIBRATION_CONTINUE"
        const val EXTRA_PRESET = "preset"
        const val EXTRA_SHOW_UI = "showUi"
        const val EXTRA_PROBE_BANDS = "probeBands"
        const val EXTRA_SESSION_ID = "sessionId"

        private const val CHANNEL_ID = "sweetspot"
        private const val NOTIFICATION_ID = 1
        private const val CLIENT_DISCONNECT_GRACE_MS = 10_000L
        private const val STATE_REVISION_PREFS = "state_revision"
        private const val STATE_REVISION_KEY = "revision"
    }

    private var engine: AudioEngine? = null
    private var webServer: WebServer? = null
    private var overlay: OverlayController? = null
    private var peerTransport: PeerTransport? = null
    private var measurementController: MeasurementController? = null
    private var calibrationEngine: CalibrationEngine? = null
    private var calibrationPlayback: TvCalibrationPlayback? = null
    private var runtimeStarted = false
    private val runtimeLock = Any()
    private val stateRevision = AtomicLong(0)
    private val stateRevisionLock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val pairCodes = PairingSessionManager()
    private val pairingRotation = Runnable { rotatePairingSession() }
    @Volatile
    private var pairingRotationPending = false
    @Volatile
    private var peerClientPresent = false
    @Volatile
    private var latestPeerDiagnostics = PeerTransportDiagnostics()
    @Volatile
    private var pendingDisconnectedPeerGeneration: String? = null
    private var captureStreamReceiver: CalibrationCaptureStreamReceiver? = null
    private val clientDisconnectGrace = Runnable {
        pendingDisconnectedPeerGeneration?.let { pairCodes.markPeerDisconnected(it) }
        pendingDisconnectedPeerGeneration = null
        measurementController?.clientPresenceChanged(false)
        rotatePairingSession(force = true)
    }
    private lateinit var profileStore: ProfileStore

    private val audioOperationGate = AudioOperationGate()
    private val diagnostics = SweetSpotDiagnosticsCoordinator(
        operationGate = audioOperationGate,
        audioPort = object : SweetSpotDiagnosticsAudioPort {
            override fun currentEq(): DynamicsProcessingEq? = engine as? DynamicsProcessingEq

            override fun isMeasurementActive(): Boolean = measurementController?.isActive() == true

            override fun suspendProduction() = this@SweetSpotService.suspendProduction()

            override fun resumeProduction() = this@SweetSpotService.resumeProduction()
        },
    )
    @Volatile
    private var measurementRestorationState: String = "none"
    @Volatile
    private var measurementRestorationSessionId: String? = null
    @Volatile
    private var measurementRestorationError: String? = null

    override val commandContext: Context get() = this
    override val commandAudioEngine: AudioEngine? get() = engine
    override val commandCalibrationEngine: CalibrationEngine? get() = calibrationEngine
    override val commandCaptureStreamReceiver: CalibrationCaptureStreamReceiver? get() = captureStreamReceiver
    override val commandMeasurementController: MeasurementController? get() = measurementController
    override val commandDiagnostics: SweetSpotDiagnostics get() = diagnostics

    /**
     * Enters the foreground before slow runtime initialization. Android allows
     * only a short window between the foreground-service start and this call.
     */
    override fun onCreate() {
        super.onCreate()
        stateRevision.set(getSharedPreferences(STATE_REVISION_PREFS, MODE_PRIVATE).getLong(STATE_REVISION_KEY, 0L))
        Log.i(TAG, "Service onCreate")
        profileStore = ProfileStore(this)
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        Log.i(TAG, "Foreground service shell ready")
    }

    @Synchronized
    private fun ensureRuntimeStarted() {
        if (runtimeStarted) return
        pairCodes.ensureActive()

        var createdEngine: AudioEngine? = null
        var createdOverlay: OverlayController? = null
        var createdWebServer: WebServer? = null
        var createdPeerTransport: PeerTransport? = null
        var createdCaptureStreamReceiver: CalibrationCaptureStreamReceiver? = null
        var createdMeasurementController: MeasurementController? = null
        var createdCalibrationPlayback: TvCalibrationPlayback? = null
        var createdCalibrationEngine: CalibrationEngine? = null
        try {
            createdEngine = DynamicsProcessingEq(profileStore).also { it.initialize() }
            val calibrationSweep = MeasurementSweep(sampleRate = 48_000)
            val calibrationAnalyzer = AndroidResponseV1Analyzer()
            val calibrationJobStore = CalibrationJobStore(
                File(filesDir, "calibration/jobs"),
                expectedAnalyzerRevision = calibrationAnalyzer.revision,
                expectedSweepRevision = com.darelisme.sweetspot.calibration.model.SweepRevision(calibrationSweep.sweepRevision),
            )
            val calibrationCaptureStore = CalibrationCaptureStore(File(filesDir, "calibration/captures"))
            createdCaptureStreamReceiver = CalibrationCaptureStreamReceiver(
                File(filesDir, "calibration/stream-captures"),
            ).also { it.cleanup() }
            createdCalibrationPlayback = TvCalibrationPlayback(
                this,
                createdEngine,
                audioOperationGate,
                calibrationSweep,
            )
            val newCalibrationEngine = CalibrationEngine(
                jobStore = calibrationJobStore,
                captureStore = calibrationCaptureStore,
                analyzer = calibrationAnalyzer,
                sweep = calibrationSweep,
                playback = createdCalibrationPlayback,
                dsp = TvCalibrationDsp(createdEngine),
                listener = object : CalibrationEngineListener {
                    override fun onJobChanged(job: CalibrationJob) {
                        peerTransport?.publish("calibration.job.state", CalibrationJobJson.view(job))
                    }

                    override fun onCaptureFinished(jobId: CalibrationJobId, captureId: CaptureId) {
                        peerTransport?.publish(
                            "calibration.capture.finished",
                            JSONObject().put("jobId", jobId.value).put("captureId", captureId.value),
                        )
                    }
                },
            )
            createdCalibrationEngine = newCalibrationEngine
            when (val recovery = newCalibrationEngine.resumeJob()) {
                is CalibrationEngineResult.Rejected -> if (recovery.code != "no_job") {
                    Log.w(TAG, "Calibration job recovery deferred: ${recovery.message}")
                }
                is CalibrationEngineResult.Updated -> Unit
            }
            createdOverlay = OverlayController(
                context = this,
                stateProvider = ::overlayState,
                actions = overlayActions(),
            ).also {
                val session = pairCodes.currentSession()
                it.updatePairInfo(session.code, PairingSessionManager.connectUrl(session))
            }
            createdWebServer = WebServer(
                createdEngine,
                createdOverlay,
                this,
                eqAppliedNotifier = ::showEqAppliedToast,
                authTokenProvider = { DeviceIdentity.getLanApiToken(this) },
                pairingSessionProvider = { pairCodes.currentSession() },
                pairCodeRotateProvider = { rotatePairingSession(force = true) },
            )
            createdPeerTransport = WebRtcPeerTransport(
                context = this,
                pairingSessionProvider = { pairCodes.currentSession() },
                commandHandler = peerCommandHandler(),
                onSessionConnected = pairCodes::markPeerConnected,
                onSessionDisconnected = { generation -> pairCodes.markPeerDisconnected(generation) },
            ).also { transport ->
                transport.listener = object : PeerTransport.Listener {
                    override fun onStateChanged(state: PeerTransportState, diagnostics: PeerTransportDiagnostics) {
                        latestPeerDiagnostics = diagnostics
                        if (state == PeerTransportState.RECONNECTING || state == PeerTransportState.FAILED) {
                            captureStreamReceiver?.cancel()
                        }
                        val overlayState = when (state) {
                            PeerTransportState.DIRECT -> OverlayController.CONNECTION_CONNECTED
                            PeerTransportState.PAIRING -> OverlayController.CONNECTION_WAITING
                            PeerTransportState.SIGNALING,
                            PeerTransportState.CONNECTING,
                            PeerTransportState.RECONNECTING,
                            -> OverlayController.CONNECTION_CONNECTING
                            PeerTransportState.FAILED,
                            PeerTransportState.IDLE,
                            PeerTransportState.CLOSED,
                            -> OverlayController.CONNECTION_DISCONNECTED
                        }
                        overlay?.updateConnectionState(overlayState)
                    }

                        override fun onPeerPresence(present: Boolean, sessionId: String?) {
                            peerClientPresent = present
                            if (present) {
                                pendingDisconnectedPeerGeneration = null
                                mainHandler.removeCallbacks(clientDisconnectGrace)
                                measurementController?.clientPresenceChanged(true)
                            } else {
                                pendingDisconnectedPeerGeneration = sessionId
                                mainHandler.removeCallbacks(clientDisconnectGrace)
                                mainHandler.postDelayed(clientDisconnectGrace, CLIENT_DISCONNECT_GRACE_MS)
                            }
                    }

                    override fun onError(message: String) {
                        captureStreamReceiver?.cancel()
                        Log.w(TAG, "Direct transport: $message")
                    }
                }
            }
            createdMeasurementController = MeasurementController(
                this,
                createdEngine,
                ::rollbackCalibrationCandidate,
                ::stateSnapshotJson,
                ::isCalibrationStateVerified,
                ::calibrationRollbackTargetActive,
                acquireAudioOperation = { audioOperationGate.tryAcquireTransient() },
                releaseAudioOperation = { audioOperationGate.releaseTransient() },
                onMeasurementRestorationState = { state, sessionId, error ->
                    measurementRestorationState = state
                    measurementRestorationSessionId = sessionId
                    measurementRestorationError = error
                },
            )

            engine = createdEngine
            calibrationPlayback = createdCalibrationPlayback
            this@SweetSpotService.calibrationEngine = createdCalibrationEngine
            overlay = createdOverlay
            webServer = createdWebServer
            peerTransport = createdPeerTransport
            captureStreamReceiver = createdCaptureStreamReceiver
            measurementController = createdMeasurementController
            synchronized(runtimeLock) {
                runtimeStarted = true
            }

            Log.i(TAG, "Service runtime started (buildId=${BuildConfig.SWEETSPOT_BUILD_ID})")

            createdWebServer.start()
            createdPeerTransport.start()
            schedulePairingRotation()
            Log.i(TAG, "Service runtime started (engine + web server + overlay + direct transport)")
        } catch (error: Throwable) {
            measurementController = null
            peerTransport = null
            captureStreamReceiver = null
            webServer = null
            overlay = null
            engine = null
            runtimeStarted = false
            try { createdMeasurementController?.shutdown() } catch (_: Throwable) {}
            try { createdCalibrationEngine?.close() } catch (_: Throwable) {}
            try { createdCalibrationPlayback?.close() } catch (_: Throwable) {}
            try { createdPeerTransport?.stop() } catch (_: Throwable) {}
            try { createdCaptureStreamReceiver?.cleanup() } catch (_: Throwable) {}
            try { createdWebServer?.stop() } catch (_: Throwable) {}
            try { createdOverlay?.hide() } catch (_: Throwable) {}
            try { createdEngine?.release() } catch (_: Throwable) {}
            audioOperationGate.forceRelease()
            throw error
        }
    }

    @Synchronized
    private fun rotatePairingSession(force: Boolean = false): PairingSessionManager.RotationResult {
        val now = System.currentTimeMillis()
        val due = pairingRotationPending || pairCodes.isExpired(now) ||
            now >= pairCodes.currentSession().expiresAt - PairingSessionManager.ROTATION_MARGIN_MS
        if (!force && !due) {
            schedulePairingRotation()
            return PairingSessionManager.RotationResult(pairCodes.currentSession(), rotated = false)
        }
        val decision = PairingSessionManager.rotationDecision(
            clientConnected = peerClientPresent,
            calibrationCritical = measurementController?.isActive() == true || audioOperationGate.isHeld(),
            peerSessionActive = pairCodes.hasActivePeer(),
        )
        if (decision == PairingSessionManager.RotationDecision.DEFER) {
            pairingRotationPending = true
            schedulePairingRotation()
            return PairingSessionManager.RotationResult(pairCodes.currentSession(), rotated = false)
        }
        pairingRotationPending = false
        val session = pairCodes.rotate()
        peerTransport?.reconnectForPairingRotation()
        schedulePairingRotation()
        overlay?.updatePairInfo(session.code, PairingSessionManager.connectUrl(session))
        return PairingSessionManager.RotationResult(session, rotated = true)
    }

    private fun schedulePairingRotation() {
        mainHandler.removeCallbacks(pairingRotation)
        val session = pairCodes.currentSession()
        val delay = if (pairingRotationPending) {
            1_000L
        } else {
            (session.expiresAt - System.currentTimeMillis() - PairingSessionManager.ROTATION_MARGIN_MS)
                .coerceAtLeast(1_000L)
        }
        mainHandler.postDelayed(pairingRotation, delay)
    }

    private fun startReason(intent: Intent?): SweetSpotStartReason = when {
        intent == null -> SweetSpotStartReason.STICKY_RESTART
        intent.getStringExtra(EXTRA_START_REASON) == SweetSpotStartReason.USER_LAUNCH.name ->
            SweetSpotStartReason.USER_LAUNCH
        intent.getStringExtra(EXTRA_START_REASON) == SweetSpotStartReason.BOOT_COMPLETED.name ->
            SweetSpotStartReason.BOOT_COMPLETED
        else -> SweetSpotStartReason.EXPLICIT_COMMAND
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val decision = SweetSpotStartupPolicy.decide(
            enabled = profileStore.isEnabled(),
            reason = startReason(intent),
            requestedShowOverlay = intent?.getBooleanExtra(EXTRA_SHOW_UI, false) ?: false,
            startOnBoot = profileStore.isStartOnBootEnabled(),
        )
        if (!decision.shouldStart) {
            Log.i(TAG, "Skipping automatic start because SweetSpot is disabled")
            stopSelfResult(startId)
            return START_STICKY
        }

        ensureRuntimeStarted()
        when (intent?.action) {
            ACTION_PRESET -> {
                val preset = intent.getIntExtra(EXTRA_PRESET, 1)
                applyPresetWithFeedback(preset)
            }
            ACTION_BYPASS -> engine?.setEnabled(false)
            ACTION_PROBE -> diagnostics.runProbe()
            ACTION_PROBE_PERSIST -> {
                val bands = intent.getIntExtra(EXTRA_PROBE_BANDS, DynamicsProcessingEq.INTERNAL_BANDS)
                diagnostics.runPersistentProbe(bands)
            }
            ACTION_PROBE_RELEASE -> diagnostics.releasePersistentProbe()
            ACTION_CALIBRATION_UI_READY ->
                measurementController?.activityReady(intent.getStringExtra(EXTRA_SESSION_ID).orEmpty())
            ACTION_CALIBRATION_UI_CLOSED ->
                measurementController?.activityClosed(intent.getStringExtra(EXTRA_SESSION_ID).orEmpty())
            ACTION_CALIBRATION_CANCEL ->
                measurementController?.cancelFromActivity(intent.getStringExtra(EXTRA_SESSION_ID).orEmpty())
            ACTION_CALIBRATION_CONTINUE ->
                measurementController?.continueFromActivity(intent.getStringExtra(EXTRA_SESSION_ID).orEmpty())
            ACTION_START -> {
                if (decision.showOverlay) overlay?.show() else overlay?.hide()
            }
            null -> overlay?.hide()
            else -> Log.d(TAG, "onStartCommand: no/unknown action (intent=$intent)")
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun runProbe() {
        diagnostics.runProbe()
    }

    override fun runPersistentProbe(bands: Int) {
        diagnostics.runPersistentProbe(bands)
    }

    override fun releasePersistentProbe() {
        diagnostics.releasePersistentProbe()
    }

    override fun getLastProbeResults(): List<DynamicsProcessingProbe.ProbeResult>? = diagnostics.lastProbeResults

    override fun isProbeRunning(): Boolean = diagnostics.probeRunning

    override fun isPersistentProbeActive(): Boolean = diagnostics.isPersistentProbeActive()

    override fun getPersistentProbeBands(): Int =
        if (diagnostics.isPersistentProbeActive()) diagnostics.persistentProbeBands else 0

    override fun getPersistentProbeError(): String? = diagnostics.persistentProbeError

    override fun onDestroy() {
        Log.i(TAG, "Service onDestroy — hiding overlay, stopping web server, releasing engine, closing direct transport")
        synchronized(runtimeLock) {
            runtimeStarted = false
        }
        mainHandler.removeCallbacks(pairingRotation)
        mainHandler.removeCallbacks(clientDisconnectGrace)
        val transport = peerTransport
        peerTransport = null
        transport?.listener = null
        val receiver = captureStreamReceiver
        captureStreamReceiver = null
        transport?.stop()
        receiver?.cancel()
        receiver?.cleanup()
        measurementController?.shutdown()
        measurementController = null
        calibrationEngine?.close()
        calibrationEngine = null
        calibrationPlayback?.close()
        calibrationPlayback = null
        diagnostics.shutdown()
        stopForeground(STOP_FOREGROUND_REMOVE)
        webServer?.stop()
        webServer = null
        overlay?.hide()
        overlay = null
        synchronized(runtimeLock) {
            engine?.release()
            engine = null
        }
        audioOperationGate.forceRelease()
        super.onDestroy()
    }

    /** Dispatches dashboard commands to the service-owned command boundary. */
    private fun peerCommandHandler(): PeerTransport.CommandHandler = SweetSpotPeerCommandHandler(this)


    override fun replyCalibrationJobResult(
        result: CalibrationEngineResult?,
        replyTo: (String, JSONObject) -> Unit,
    ) {
        when (result) {
            is CalibrationEngineResult.Updated -> replyTo("calibration.job.state", CalibrationJobJson.view(result.job))
            is CalibrationEngineResult.Rejected -> {
                val job = result.job
                if (job != null) {
                    replyTo("calibration.job.state", CalibrationJobJson.view(job))
                } else {
                    replyTo("state.snapshot", stateSnapshotJson().put("ok", false).put("error", result.message))
                }
            }
            null -> replyTo("state.snapshot", stateSnapshotJson().put("ok", false).put("error", "Calibration engine is unavailable"))
        }
    }

    override fun publishCalibrationCaptureResult(
        result: CalibrationEngineResult,
        capture: CalibrationCaptureStreamReceiver.Completed,
    ) {
        val metadata = try { JSONObject(capture.metadataJson) } catch (_: Throwable) { return }
        val job = result.job
        val lastValidationOutcome = job?.validationHistory?.lastOrNull()?.outcome
        val accepted = when (result) {
            is CalibrationEngineResult.Rejected -> false
            is CalibrationEngineResult.Updated -> {
                val captureId = metadata.optString("captureId")
                val channel = metadata.optString("channel")
                if (channel == "both") {
                    lastValidationOutcome != null &&
                        lastValidationOutcome != com.darelisme.sweetspot.calibration.model.ValidationOutcome.INCONCLUSIVE_CAPTURE
                } else {
                    job?.ledger?.attempts?.any {
                        it.request.captureId.value == captureId && it is CaptureAttempt.Accepted
                    } == true
                }
            }
        }
        peerTransport?.publish(
            "calibration.capture.uploaded",
            JSONObject().apply {
                put("jobId", metadata.optString("jobId"))
                put("captureId", metadata.optString("captureId"))
                put("contentSha256", metadata.optString("contentSha256"))
                put("sampleCount", metadata.optLong("sampleCount"))
                put("byteCount", capture.byteCount)
                put("status", if (accepted) "accepted" else "rejected")
                if (!accepted) {
                    val reason = when (result) {
                        is CalibrationEngineResult.Rejected -> result.message
                        is CalibrationEngineResult.Updated -> job?.lastError?.message
                    }
                    reason?.takeIf { it.isNotBlank() }?.let { put("reason", it) }
                }
            },
        )
        job?.let { peerTransport?.publish("calibration.job.state", CalibrationJobJson.view(it)) }
    }

    override fun publishCalibrationCaptureRejection(captureId: String, reason: String) {
        val job = calibrationEngine?.currentJob() ?: return
        val actionCaptureId = when (val action = job.nextAction) {
            is com.darelisme.sweetspot.calibration.model.CalibrationAction.Capture -> action.request.captureId.value
            is com.darelisme.sweetspot.calibration.model.CalibrationAction.Validate -> action.captureId.value
            else -> null
        }
        if (actionCaptureId != captureId) return
        peerTransport?.publish(
            "calibration.capture.rejected",
            JSONObject()
                .put("jobId", job.id.value)
                .put("captureId", captureId)
                .put("status", "rejected")
                .put("reason", reason),
        )
    }

    override fun deviceInfoJson(): JSONObject = diagnostics.deviceInfoJson()

    override fun transportDiagnosticsJson(): JSONObject {
        val diagnostics = latestPeerDiagnostics
        return JSONObject().apply {
            put("state", diagnostics.state.name.lowercase(java.util.Locale.ROOT))
            put("sessionId", diagnostics.sessionId?.takeLast(8) ?: JSONObject.NULL)
            put("iceConnectionState", diagnostics.iceConnectionState ?: JSONObject.NULL)
            put("iceGatheringState", diagnostics.iceGatheringState ?: JSONObject.NULL)
            put("peerConnectionState", diagnostics.peerConnectionState ?: JSONObject.NULL)
            put("selectedCandidateType", diagnostics.selectedCandidateType ?: JSONObject.NULL)
            put("selectedCandidateProtocol", diagnostics.selectedCandidateProtocol ?: JSONObject.NULL)
            put("rttMs", diagnostics.rttMs ?: JSONObject.NULL)
            put("bytesSent", diagnostics.bytesSent)
            put("bytesReceived", diagnostics.bytesReceived)
            put("captureBufferedBytes", diagnostics.captureBufferedBytes)
            put("reconnectCount", diagnostics.reconnectCount)
            put("signalingRoundTripMs", JSONObject.NULL)
            put("lastControlMessageAt", diagnostics.lastControlMessageAt ?: JSONObject.NULL)
            put("lastPeerTrafficAt", diagnostics.lastPeerTrafficAt ?: JSONObject.NULL)
            put("lastError", diagnostics.lastError ?: JSONObject.NULL)
        }
    }

    override fun applyPersistentCurve(curve: String): Boolean = diagnostics.applyPersistentCurve(curve)

    override fun applyPersistentBands(common: FloatArray, left: FloatArray?, right: FloatArray?): Boolean =
        diagnostics.applyPersistentBands(common, left, right)

    override fun getPersistentProbeCurve(): String? = diagnostics.getPersistentProbeCurve()

    override fun getPersistentProbeCurveSummary(channel: Int): DynamicsProcessingProbe.CurveSummary? =
        diagnostics.getPersistentProbeCurveSummary(channel)

    override fun dpEq() = engine as? DynamicsProcessingEq

    private fun overlayState(): OverlayState {
        val currentEngine = engine
        val capabilities = currentEngine?.getCapabilities()
        val presetOptions = buildList {
            capabilities?.presets?.entries?.sortedBy { it.key }?.forEach { entry ->
                add(OverlayPresetOption(name = entry.value, presetId = entry.key))
            }
            currentEngine?.listProfiles()?.forEach { name ->
                add(OverlayPresetOption(name = name, profileName = name))
            }
        }
        val job = calibrationEngine?.currentJob()
        val calibrationMessage = when (job?.phase) {
            CalibrationPhase.CenterPreflight -> "Center setup is being checked before you move the phone."
            CalibrationPhase.MeasuringRequired -> "Follow the phone instructions for the center, left, and right positions."
            CalibrationPhase.Usable,
            CalibrationPhase.Refining -> "A usable correction exists. Optional positions can improve confidence."
            CalibrationPhase.CandidatePending,
            CalibrationPhase.Validating -> "Return the phone to center when the web dashboard asks for validation."
            CalibrationPhase.Reoptimizing -> "The TV is trying a gentler correction from the saved room measurements."
            CalibrationPhase.Restoring -> "The TV is restoring the last verified audio state."
            CalibrationPhase.Complete -> "Calibration is complete and verified."
            is CalibrationPhase.Failed -> job.phase.reason
            CalibrationPhase.Cancelled -> "Calibration was cancelled."
            null -> null
        }
        return OverlayState(
            dspEnabled = currentEngine?.isEnabled() ?: profileStore.isEnabled(),
            calibrationAvailable = dpEq()?.hasCalibrationProfile() == true,
            calibrationEnabled = dpEq()?.isCalibrationActive() == true,
            startOnBoot = profileStore.isStartOnBootEnabled(),
            presets = presetOptions,
            calibrationMessage = calibrationMessage,
        )
    }

    private fun overlayActions(): OverlayActions = object : OverlayActions {
        override fun setDspEnabled(enabled: Boolean) {
            if (engine?.setEnabled(enabled) != true) showCalibrationErrorToast("The global DSP state could not be changed")
            overlay?.refresh()
        }

        override fun setCalibrationEnabled(enabled: Boolean) {
            if (dpEq()?.setCalibrationEnabled(enabled) != true) {
                showCalibrationErrorToast("The room correction could not be changed")
            }
            overlay?.refresh()
        }

        override fun applyPreset(option: OverlayPresetOption) {
            val applied = when {
                option.presetId != null -> applyPresetWithFeedback(option.presetId)
                option.profileName != null -> loadProfileWithFeedback(option.profileName)
                else -> false
            }
            if (!applied) showCalibrationErrorToast("The selected EQ could not be applied")
            overlay?.refresh()
        }

        override fun setStartOnBoot(enabled: Boolean) {
            if (!profileStore.saveStartOnBootEnabled(enabled)) {
                showCalibrationErrorToast("The start-on-boot setting could not be saved")
            }
            overlay?.refresh()
        }

        override fun startCalibration() {
            // The paired web client owns microphone capture and starts the TV job.
            overlay?.showPairing()
        }
    }

    override fun rollbackCalibrationCandidate(candidateId: String): Boolean {
        val eq = dpEq() ?: return false
        if (!eq.rollbackCalibrationCandidate(candidateId)) return false
        return eq.getCalibrationTransaction() == null && eq.isLiveDspVerified()
    }

    private fun calibrationRollbackTargetActive(candidateId: String): Boolean? =
        dpEq()?.getCalibrationTransaction()
            ?.takeIf { it.candidateId == candidateId }
            ?.previousActive

    private fun isCalibrationStateVerified(): Boolean {
        val eq = dpEq() ?: return false
        return eq.getCalibrationTransaction() == null && eq.isLiveDspVerified()
    }

    override fun getCalibrationBands(): FloatArray? = dpEq()?.getCalibrationBands()
    override fun getRequestedCalibrationBands(): FloatArray? = dpEq()?.getRequestedCalibrationBands()
    override fun getEffectiveCalibrationBands(): FloatArray? = dpEq()?.getEffectiveCalibrationBands()
    override fun getRequestedCalibrationBandsForChannel(channel: Int): FloatArray? = dpEq()?.getRequestedCalibrationBandsForChannel(channel)
    override fun getEffectiveCalibrationBandsForChannel(channel: Int): FloatArray? = dpEq()?.getEffectiveCalibrationBandsForChannel(channel)
    override fun getCalibrationFrequenciesHz(): IntArray? = dpEq()?.getCalibrationFrequenciesHz()
    override fun isCalibrationActive(): Boolean = dpEq()?.isCalibrationActive() ?: false
    override fun wasLastCalibrationApplySuccessful(): Boolean = dpEq()?.wasLastCalibrationApplySuccessful() ?: false
    override fun getLastCalibrationApplyError(): String? = dpEq()?.getLastCalibrationApplyError()
    override fun isCalibrationLiveDspVerified(): Boolean = dpEq()?.isLiveDspVerified() ?: false
    override fun getCalibrationLiveDspVerificationError(): String? = dpEq()?.getLiveDspVerificationError()
    override fun setCalibrationBands(gains: FloatArray): Boolean = dpEq()?.applyCalibrationCandidate(gains) ?: false
    private fun setCalibrationBandsByChannel(left: FloatArray, right: FloatArray): Boolean =
        dpEq()?.setCalibrationBandsByChannel(left, right) ?: false
    override fun resetCalibration(): Boolean = dpEq()?.resetCalibration() ?: false

    /** Release the production engine so a diagnostic DynamicsProcessing can own session 0. */
    private fun suspendProduction() {
        synchronized(runtimeLock) {
            if (runtimeStarted) engine?.release()
        }
    }

    /** Re-create and restore the production engine after diagnostics. */
    private fun resumeProduction() {
        synchronized(runtimeLock) {
            if (runtimeStarted) engine?.initialize()
        }
    }

    override fun applyPresetWithFeedback(preset: Int): Boolean {
        val engine = engine ?: return false
        val eqName = engine.getCapabilities().presets[preset] ?: return false
        if (!engine.applyPreset(preset)) return false
        showEqAppliedToast(eqName)
        return true
    }

    override fun loadProfileWithFeedback(name: String): Boolean {
        val engine = engine ?: return false
        if (name !in engine.listProfiles()) return false
        if (!engine.loadProfile(name)) return false
        showEqAppliedToast(name)
        return true
    }

    private fun showEqAppliedToast(eqName: String) {
        mainHandler.post {
            Toast.makeText(this, "Sweetspot applied $eqName EQ.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun showCalibrationErrorToast(message: String) {
        mainHandler.post {
            Toast.makeText(this, "Calibration could not continue: $message", Toast.LENGTH_LONG).show()
        }
    }

    override fun runEffectDiagnostics() {
        diagnostics.runEffectDiagnostics()
    }

    override fun getEffectInventory(): List<AudioEffectDiagnostics.EffectInventoryEntry> =
        diagnostics.getEffectInventory()

    override fun getSessionProbes(): List<AudioEffectDiagnostics.SessionProbe> =
        diagnostics.getSessionProbes()

    /**
     * Canonical state snapshot for the hosted dashboard (protocol v1).
     * Mirrors shared/types/protocol.ts StateSnapshot in sweetspot-web.
     */
    private fun nextStateRevision(): Long = synchronized(stateRevisionLock) {
        val next = stateRevision.incrementAndGet()
        getSharedPreferences(STATE_REVISION_PREFS, MODE_PRIVATE)
            .edit()
            .putLong(STATE_REVISION_KEY, next)
            .commit()
        next
    }

    override fun stateSnapshotJson(): JSONObject {
        val engine = engine ?: DynamicsProcessingEq(profileStore)
        val caps = engine.getCapabilities()
        val calBands = dpEq()?.getEffectiveCalibrationBands() ?: FloatArray(0)
        val requestedCalBands = dpEq()?.getRequestedCalibrationBands() ?: FloatArray(0)
        val calFreqs = dpEq()?.getCalibrationFrequenciesHz() ?: IntArray(0)
        val calLeft = dpEq()?.getEffectiveCalibrationBandsForChannel(0)
        val calRight = dpEq()?.getEffectiveCalibrationBandsForChannel(1)
        val requestedCalLeft = dpEq()?.getRequestedCalibrationBandsForChannel(0)
        val requestedCalRight = dpEq()?.getRequestedCalibrationBandsForChannel(1)
        val independentCalibration = dpEq()?.supportsIndependentCalibration() ?: false
        val channelCount = (dpEq()?.getChannelCount() ?: 1).coerceAtLeast(1)
        val headroomDb = dpEq()?.getInputGainDb() ?: 0f
        val headroomVerified = dpEq()?.isHeadroomVerified() ?: false
        val calibrationTransaction = dpEq()?.getCalibrationTransaction()
        val userLevels = engine.getBandLevels()
        return JSONObject().apply {
            put("stateRevision", nextStateRevision())
            put("device", JSONObject().apply {
                put("id", DeviceIdentity.get(this@SweetSpotService))
                put("name", DeviceIdentity.getName(this@SweetSpotService))
                put("appVersion", "0.1.0")
                put("buildId", BuildConfig.SWEETSPOT_BUILD_ID)
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
                put("requestedBandsDb", JSONArray(requestedCalBands.toList()))
                put("effectiveBandsDb", JSONArray(calBands.toList()))
                if (independentCalibration && calLeft != null && calRight != null) {
                    put("leftBandsDb", JSONArray(calLeft.toList()))
                    put("rightBandsDb", JSONArray(calRight.toList()))
                    put("requestedLeftBandsDb", JSONArray(requestedCalLeft?.toList() ?: emptyList<Float>()))
                    put("requestedRightBandsDb", JSONArray(requestedCalRight?.toList() ?: emptyList<Float>()))
                    put("effectiveLeftBandsDb", JSONArray(calLeft.toList()))
                    put("effectiveRightBandsDb", JSONArray(calRight.toList()))
                    put("independent", true)
                } else {
                    put("independent", false)
                }
                put("headroomDb", headroomDb)
                put("inputAttenuationDb", maxOf(0f, -headroomDb))
                put("headroomVerified", headroomVerified)
                val eq = dpEq()
                val liveDspError = measurementRestorationError ?: eq?.getLiveDspVerificationError()
                val restorationPending = measurementRestorationState == "restoring"
                put("applicationVerified", !restorationPending && eq != null && liveDspError == null)
                put("liveDspStatus", if (!restorationPending && eq != null && liveDspError == null) "verified" else "degraded")
                (liveDspError ?: eq?.getLastCalibrationApplyError())?.let { put("applicationError", it) }
                put(
                    "transaction",
                    if (restorationPending) {
                        JSONObject()
                            .put("state", "restoring")
                            .put("sessionId", measurementRestorationSessionId ?: JSONObject.NULL)
                    } else {
                        calibrationTransactionJson(calibrationTransaction)
                    },
                )
            })
            put("calibrationJob", calibrationEngine?.currentJob()?.let(CalibrationJobJson::view) ?: JSONObject.NULL)
            put("profiles", JSONArray(engine.listProfiles().map { name ->
                JSONObject().put("id", name).put("name", name)
            }))
            put("capabilities", JSONObject().apply {
                put("channels", channelCount)
                put("calibrationBandCount", if (calBands.isNotEmpty()) calBands.size else 64)
                put("userBandCount", caps.bandCount)
                put("supportsSweep", true)
                put("supportsIndependentCalibration", independentCalibration)
                put("supportsCalibratedCorrection", DynamicsProcessingEq.BAND_TRANSFER_CHARACTERIZED)
                put("supportsHeadroomCompensation", headroomVerified)
                put("presets", JSONArray(caps.presets.entries.sortedBy { it.key }.map { entry ->
                    JSONObject().put("id", entry.key).put("name", entry.value)
                }))
            })
        }
    }

    private fun serviceActionsIsCalibrationActive(): Boolean = try {
        (engine as? DynamicsProcessingEq)?.isCalibrationActive() ?: false
    } catch (_: Exception) { false }

    /** Serializes the persisted candidate state and its pre-candidate live calibration state. */
    private fun calibrationTransactionJson(transaction: CalibrationCandidateTransaction?): JSONObject {
        if (transaction == null) return JSONObject().put("state", "none")
        val validationStatus = if (transaction.validationStatus == CalibrationValidationStatus.APPLYING) {
            "pending"
        } else {
            transaction.validationStatus.name.lowercase()
        }
        return JSONObject().apply {
            put("state", "candidate_pending")
            put("candidateId", transaction.candidateId)
            put("previousActive", transaction.previousActive)
            put("validationStatus", validationStatus)
            put("beforeDb", transaction.beforeDb ?: JSONObject.NULL)
            put("afterDb", transaction.afterDb ?: JSONObject.NULL)
            put("reason", transaction.reason ?: JSONObject.NULL)
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "SweetSpot",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Persistent audio tuning service"
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
            enableLights(false)
        }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("SweetSpot")
            .setContentText("Audio tuning active")
            .setSmallIcon(R.drawable.ic_notification)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .build()
    }
}
