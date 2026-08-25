package com.darelisme.sweetspot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.audiofx.Virtualizer
import android.os.IBinder
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.system.Os
import android.system.OsConstants
import android.util.Log
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt
import kotlin.math.pow

internal fun parseStrictCalibrationArray(value: JSONArray?): FloatArray? {
    if (value == null || value.length() != DynamicsProcessingEq.INTERNAL_BANDS) return null
    return try {
        FloatArray(DynamicsProcessingEq.INTERNAL_BANDS) { index ->
            val parsed = value.getDouble(index)
            require(parsed.isFinite())
            parsed.toFloat().also { require(it.isFinite()) }
        }
    } catch (_: Throwable) {
        null
    }
}

internal fun parseStrictProbeArray(value: JSONArray?, expectedBands: Int): FloatArray? {
    if (value == null || expectedBands <= 0 || value.length() != expectedBands) return null
    return try {
        FloatArray(expectedBands) { index ->
            val parsed = value.getDouble(index)
            require(parsed.isFinite())
            parsed.toFloat().also {
                require(it.isFinite())
                require(it >= DynamicsProcessingProbe.MIN_PROBE_GAIN_DB)
                require(it <= DynamicsProcessingProbe.MAX_PROBE_GAIN_DB)
            }
        }
    } catch (_: Throwable) {
        null
    }
}

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
    }

    private var engine: AudioEngine? = null
    private var webServer: WebServer? = null
    private var overlay: OverlayController? = null
    private var relay: MailboxClient? = null
    private var measurementController: MeasurementController? = null
    private var runtimeStarted = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val pairCodes = PairCodeManager()
    private lateinit var profileStore: ProfileStore

    /** Serializes probe runs so a burst of broadcasts cannot overlap probes. */
    private val probeExecutor = Executors.newSingleThreadExecutor()

    /** Last capacity-probe results surfaced to the web UI. */
    private var lastProbeResults: List<DynamicsProcessingProbe.ProbeResult>? = null
    private val probeRunning = AtomicBoolean(false)
    private var persistentBands: Int = 0
    private var persistentCurveName: String? = null

    /** Persistent global-mix Virtualizer for spatial-widening A/B tests. */
    private var persistentVirtualizer: Virtualizer? = null

    /**
     * Enters the foreground before slow runtime initialization. Android allows
     * only a short window between the foreground-service start and this call.
     */
    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Service onCreate")
        profileStore = ProfileStore(this)
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        Log.i(TAG, "Foreground service shell ready")
    }

    @Synchronized
    private fun ensureRuntimeStarted() {
        if (runtimeStarted) return

        var createdEngine: AudioEngine? = null
        var createdOverlay: OverlayController? = null
        var createdWebServer: WebServer? = null
        var createdRelay: MailboxClient? = null
        var createdMeasurementController: MeasurementController? = null
        try {
            createdEngine = DynamicsProcessingEq(profileStore).also { it.initialize() }
            createdOverlay = OverlayController(this).also {
                it.updatePairInfo(pairCodes.current())
            }
            createdWebServer = WebServer(
                createdEngine,
                createdOverlay,
                this,
                eqAppliedNotifier = ::showEqAppliedToast,
                pairCodeProvider = { pairCodes.current() },
                pairCodeRotateProvider = {
                    pairCodes.rotate().also { code -> overlay?.updatePairInfo(code) }
                }
            )
            createdRelay = MailboxClient(
                roomProvider = { pairCodes.current() },
                snapshotProvider = { stateSnapshotJson() },
                effectsDiagnosticsProvider = { runEffectDiagnosticsBlocking() },
                commandHandler = mailboxCommandHandler()
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
                        measurementController?.clientPresenceChanged(present)
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
            )

            engine = createdEngine
            overlay = createdOverlay
            webServer = createdWebServer
            relay = createdRelay
            measurementController = createdMeasurementController
            runtimeStarted = true

            createdWebServer.start()
            createdRelay.start()
            Log.i(TAG, "Service runtime started (engine + web server + overlay + relay)")
        } catch (error: Throwable) {
            measurementController = null
            relay = null
            webServer = null
            overlay = null
            engine = null
            runtimeStarted = false
            try { createdMeasurementController?.shutdown() } catch (_: Throwable) {}
            try { createdRelay?.stop() } catch (_: Throwable) {}
            try { createdWebServer?.stop() } catch (_: Throwable) {}
            try { createdOverlay?.hide() } catch (_: Throwable) {}
            try { createdEngine?.release() } catch (_: Throwable) {}
            throw error
        }
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
            requestedShowOverlay = intent?.getBooleanExtra(EXTRA_SHOW_UI, false) ?: false
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
            ACTION_PROBE -> runProbe()
            ACTION_PROBE_PERSIST -> {
                val bands = intent.getIntExtra(EXTRA_PROBE_BANDS, DynamicsProcessingEq.INTERNAL_BANDS)
                runPersistentProbe(bands)
            }
            ACTION_PROBE_RELEASE -> releasePersistentProbe()
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
        runtimeStarted = false
        measurementController?.shutdown()
        measurementController = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        relay?.stop()
        relay = null
        webServer?.stop()
        webServer = null
        overlay?.hide()
        overlay = null
        engine?.release()
        engine = null
        try { persistentVirtualizer?.release() } catch (_: Throwable) {}
        persistentVirtualizer = null
        probeExecutor.shutdownNow()
        super.onDestroy()
    }

    /**
     * Enables the temporary diagnostic overlay on the production 64-band
     * DynamicsProcessing instance. A second effect on global session 0 loses
     * control on the TCL, so transfer/routing probes must use this owner.
     */
    override fun runPersistentProbe(bands: Int) {
        Log.i(TAG, "Persistent DynamicsProcessing probe requested: $bands bands")
        probeExecutor.submit {
            try {
                persistentCurveName = null
                val eq = dpEq() ?: throw IllegalStateException("Production DynamicsProcessing is not initialized")
                if (!eq.clearDiagnosticProbe()) throw IllegalStateException("Could not restore production EQ before starting the diagnostic overlay")
                if (bands != DynamicsProcessingEq.INTERNAL_BANDS) {
                    Log.w(TAG, "Diagnostic transfer probe requires ${DynamicsProcessingEq.INTERNAL_BANDS} bands; requested $bands")
                    persistentBands = 0
                    return@submit
                }
                if (eq.getChannelCount() < 1) throw IllegalStateException("Production DynamicsProcessing has no channels")
                persistentBands = DynamicsProcessingEq.INTERNAL_BANDS
                if (!eq.applyDiagnosticProbe(FloatArray(DynamicsProcessingEq.INTERNAL_BANDS))) {
                    throw IllegalStateException("Production DynamicsProcessing rejected the flat diagnostic overlay")
                }
                persistentCurveName = "flat"
                Log.i(TAG, "=== Persistent DynamicsProcessing ACTIVE ===")
                Log.i(TAG, "Bands: $persistentBands | Channels: ${eq.getChannelCount()} | Session: 0 (production owner)")
                Log.i(TAG, "Diagnostic overlay is ready; it is not persisted and must be released after the experiment.")
            } catch (e: Throwable) {
                Log.e(TAG, "Persistent probe failed for $bands bands", e)
                persistentBands = 0
                persistentCurveName = null
            }
        }
    }

    /** Removes the temporary diagnostic overlay and restores production EQ. */
    override fun releasePersistentProbe() {
        probeExecutor.submit {
            try {
                if (dpEq()?.clearDiagnosticProbe() != true) {
                    throw IllegalStateException("Production DynamicsProcessing rejected diagnostic overlay removal")
                }
                persistentBands = 0
                persistentCurveName = null
                Log.i(TAG, "Diagnostic DynamicsProcessing probe released")
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to release persistent instance", e)
            }
        }
    }

    override fun getLastProbeResults(): List<DynamicsProcessingProbe.ProbeResult>? = lastProbeResults
    override fun isProbeRunning(): Boolean = probeRunning.get()
    override fun isPersistentProbeActive(): Boolean = dpEq()?.isDiagnosticProbeActive() == true
    override fun getPersistentProbeBands(): Int = if (isPersistentProbeActive()) persistentBands else 0

    /**
     * Dispatches dashboard control commands arriving via the mailbox. Runs on
     * the mailbox worker thread, so probe work is submitted to [probeExecutor]
     * and answered with a follow-up probe.status message.
     */
    private fun mailboxCommandHandler() = object : MailboxClient.CommandHandler {
        override fun onCommand(type: String, payload: JSONObject, replyTo: (String, JSONObject) -> Unit) {
            val engine = this@SweetSpotService.engine
            var commandOk = true
            var commandError: String? = null
            when (type) {
                "engine.enable" -> {
                    commandOk = engine?.setEnabled(true) == true
                    if (!commandOk) commandError = "Live DSP rejected enable"
                }
                "engine.bypass" -> {
                    commandOk = engine?.setEnabled(false) == true
                    if (!commandOk) commandError = "Live DSP rejected bypass"
                }
                "engine.setBands" -> {
                    val arr = payload.optJSONArray("bandsDb")
                    val bandCount = engine?.getCapabilities()?.bandCount ?: 0
                    val previous = engine?.getBandLevels()
                    val previousPreset = engine?.getActivePreset() ?: 0
                    if (arr == null || arr.length() != bandCount) {
                        commandOk = false
                        commandError = "Expected $bandCount user EQ bands"
                    } else {
                        for (i in 0 until bandCount) {
                            val value = arr.optDouble(i, Double.NaN)
                            if (!value.isFinite()
                                || value < DynamicsProcessingEq.MIN_USER_LEVEL_MILLIBELS / 100f
                                || value > DynamicsProcessingEq.MAX_USER_LEVEL_MILLIBELS / 100f
                                || engine?.setBandLevel(i, (value * 100).roundToInt()) != true
                            ) {
                                commandOk = false
                                commandError = "Live DSP rejected user EQ band $i"
                                break
                            }
                        }
                    }
                    if (!commandOk && previous != null && previous.size == bandCount) {
                        for (i in previous.indices) {
                            if (engine.setBandLevel(i, previous[i]) != true) {
                                commandError = "$commandError; previous user EQ could not be fully restored"
                                break
                            }
                        }
                        if (previousPreset > 0 && engine.applyPreset(previousPreset) != true) {
                            commandError = "$commandError; previous EQ preset could not be fully restored"
                        }
                    }
                }
                "engine.applyPreset" -> {
                    commandOk = applyPresetWithFeedback(payload.optInt("preset", -1))
                    if (!commandOk) commandError = "Live DSP rejected preset"
                }
                "profile.save" -> payload.optString("name").takeIf { it.isNotBlank() }?.let { engine?.saveCurrentProfile(it) }
                "profile.load" -> {
                    commandOk = payload.optString("name").takeIf { it.isNotBlank() }?.let { loadProfileWithFeedback(it) } ?: false
                    if (!commandOk) commandError = "Live DSP rejected profile load"
                }
                "profile.delete" -> payload.optString("name").takeIf { it.isNotBlank() }?.let { engine?.deleteProfile(it) }
                "calibration.applyCandidate" -> {
                    val arr = payload.optJSONArray("bandsDb")
                    val leftArr = payload.optJSONArray("leftBandsDb")
                    val rightArr = payload.optJSONArray("rightBandsDb")
                    val left = parseStrictCalibrationArray(leftArr)
                    val right = parseStrictCalibrationArray(rightArr)
                    val common = parseStrictCalibrationArray(arr)
                    commandOk = if (leftArr != null || rightArr != null) {
                        val target = dpEq()
                        common != null && left != null && right != null && target?.applyCalibrationCandidate(common, left, right) == true
                    } else {
                        common != null && dpEq()?.applyCalibrationCandidate(common) == true
                    }
                    if (!commandOk) commandError = dpEq()?.getLastCalibrationApplyError() ?: "Calibration candidate was rejected"
                    if (!commandOk) showCalibrationErrorToast(commandError ?: "Calibration candidate was rejected")
                    replyTo("state.snapshot", stateSnapshotJson().put("ok", commandOk).apply { commandError?.let { put("error", it) } })
                    return
                }
                "calibration.export" -> {
                    val target = dpEq()
                    val packageValue = target?.exportCalibrationPackage(
                        CalibrationPackageSourceDevice(
                            id = DeviceIdentity.get(this@SweetSpotService),
                            name = DeviceIdentity.getName(this@SweetSpotService),
                            appVersion = "0.1.0",
                        ),
                    )
                    commandOk = packageValue != null
                    if (packageValue != null) {
                        replyTo("calibration.exported", CalibrationPackageCodec.serialize(packageValue))
                        return
                    }
                    commandError = target?.getLastCalibrationApplyError()
                        ?: target?.getLiveDspVerificationError()
                        ?: "No verified active calibration is available"
                    replyTo("state.snapshot", stateSnapshotJson().put("ok", false).put("error", commandError))
                    return
                }
                "calibration.import" -> {
                    val target = dpEq()
                    val expectedFrequencies = target?.getCalibrationFrequenciesHz() ?: IntArray(0)
                    val parsed = if (target == null) {
                        CalibrationPackageParseResult.Rejected("The TV audio engine is unavailable")
                    } else {
                        CalibrationPackageCodec.parseForImport(
                            payload = payload,
                            expectedFrequenciesHz = expectedFrequencies,
                            independentRoutingVerified = target.supportsIndependentCalibration(),
                        )
                    }
                    when (parsed) {
                        is CalibrationPackageParseResult.Accepted -> {
                            commandOk = target?.applyImportedCalibrationCandidate(parsed.value) == true
                            if (!commandOk) {
                                commandError = target?.getLastCalibrationApplyError()
                                    ?: "Imported calibration was rejected"
                            }
                        }
                        is CalibrationPackageParseResult.Rejected -> {
                            commandOk = false
                            commandError = parsed.error
                        }
                    }
                    if (!commandOk) showCalibrationErrorToast(commandError ?: "Imported calibration was rejected")
                    replyTo("state.snapshot", stateSnapshotJson().put("ok", commandOk).apply {
                        commandError?.let { put("error", it) }
                    })
                    return
                }
                "calibration.acceptCandidate" -> {
                    val candidateId = payload.optString("candidateId")
                    val transaction = dpEq()?.getCalibrationTransaction()
                    commandOk = candidateId.isNotBlank() && (dpEq()?.acceptCalibrationCandidate(candidateId) == true)
                    if (!commandOk) commandError = "Calibration candidate is not available for acceptance"
                    if (commandOk) {
                        measurementController?.validationFinalized(candidateId, "improved", transaction?.reason)
                    } else if (candidateId.isNotBlank()) {
                        measurementController?.validationFinalizationFailed(candidateId, commandError ?: "Calibration candidate acceptance failed")
                    }
                    replyTo("state.snapshot", stateSnapshotJson().put("ok", commandOk).apply { commandError?.let { put("error", it) } })
                    return
                }
                "calibration.rollbackCandidate" -> {
                    val candidateId = payload.optString("candidateId")
                    val transaction = dpEq()?.getCalibrationTransaction()
                    val result = transaction?.validationStatus?.rollbackOutcome() ?: "inconclusive"
                    commandOk = candidateId.isNotBlank() && rollbackCalibrationCandidate(candidateId)
                    if (!commandOk) commandError = dpEq()?.getLastCalibrationApplyError() ?: "Calibration candidate rollback failed"
                    if (commandOk) {
                        measurementController?.validationFinalized(candidateId, result, transaction?.reason)
                    } else if (candidateId.isNotBlank()) {
                        measurementController?.validationFinalizationFailed(candidateId, commandError ?: "Calibration candidate rollback failed")
                    }
                    replyTo("state.snapshot", stateSnapshotJson().put("ok", commandOk).apply { commandError?.let { put("error", it) } })
                    return
                }
                "calibration.validation.result" -> {
                    val candidateId = payload.optString("candidateId")
                    val status = when (payload.optString("status")) {
                        "passed" -> CalibrationValidationStatus.PASSED
                        "worse" -> CalibrationValidationStatus.WORSE
                        "inconclusive" -> CalibrationValidationStatus.INCONCLUSIVE
                        "failed" -> CalibrationValidationStatus.FAILED
                        else -> null
                    }
                    val before = if (payload.has("beforeDb")) payload.optDouble("beforeDb", Double.NaN).toFloat().takeIf { it.isFinite() } else null
                    val after = if (payload.has("afterDb")) payload.optDouble("afterDb", Double.NaN).toFloat().takeIf { it.isFinite() } else null
                    val reason = payload.optString("reason").takeIf { it.isNotBlank() }
                    commandOk = candidateId.isNotBlank() && status != null && dpEq()?.recordCalibrationValidation(
                        candidateId,
                        status,
                        before,
                        after,
                        reason,
                    ) == true
                    if (!commandOk) commandError = "Calibration validation result was rejected"
                    if (!commandOk && candidateId.isNotBlank()) {
                        measurementController?.validationFinalizationFailed(candidateId, commandError ?: "Calibration validation result was rejected")
                    }
                    replyTo("state.snapshot", stateSnapshotJson().put("ok", commandOk).apply { commandError?.let { put("error", it) } })
                    return
                }
                "calibration.reset" -> {
                    commandOk = resetCalibration()
                }
                "calibrationSession.begin" -> {
                    measurementController?.begin(
                        payload.optString("sessionId"),
                        payload.optString("channel", "both"),
                        payload.optString("phase", "measurement"),
                        payload.optString("candidateId").takeIf { it.isNotBlank() },
                        null,
                        emit = { eventType, eventPayload, _ -> replyTo(eventType, eventPayload) }
                    )
                    return
                }
                "calibrationSession.end" -> {
                    measurementController?.end(
                        payload.optString("sessionId"),
                        null,
                        emit = { eventType, eventPayload, _ -> replyTo(eventType, eventPayload) }
                    )
                    return
                }
                "calibrationSession.abort" -> {
                    val abort = parseCalibrationSessionAbortPayload(payload)
                    if (abort == null) {
                        replyTo(
                            "measurement.error",
                            JSONObject()
                                .put("sessionId", payload.optString("sessionId"))
                                .put("code", "invalid_session")
                                .put("message", "calibrationSession.abort requires a valid error code"),
                        )
                    } else {
                        measurementController?.cancel(
                            sessionId = abort.sessionId,
                            code = abort.code,
                            message = abort.message,
                            replyTo = null,
                            emit = { eventType, eventPayload, _ -> replyTo(eventType, eventPayload) },
                        )
                    }
                    return
                }
                "calibrationSession.loudness.start" -> {
                    measurementController?.startLoudness(
                        payload.optString("sessionId"),
                        null,
                        emit = { eventType, eventPayload, _ -> replyTo(eventType, eventPayload) }
                    )
                    return
                }
                "calibrationSession.loudness.stop" -> {
                    measurementController?.stopLoudness(
                        payload.optString("sessionId"),
                        null,
                        emit = { eventType, eventPayload, _ -> replyTo(eventType, eventPayload) }
                    )
                    return
                }
                "calibrationSession.progress" -> {
                    measurementController?.updateProgress(
                        payload.optString("sessionId"),
                        payload.optString("stage"),
                        payload.optInt("current", -1),
                        payload.optInt("total", -1),
                        payload.optInt("estimatedRemainingSeconds", -1).takeIf { payload.has("estimatedRemainingSeconds") },
                        payload.optString("message").takeIf { payload.has("message") }
                    )
                    return
                }
                "measurement.response" -> {
                    val response = MeasurementResponsePayload.parse(payload)
                    if (response == null) {
                        Log.w(TAG, "Rejected invalid measurement.response payload")
                        return
                    }
                    measurementController?.updateResponse(response)
                    return
                }
                "measurement.prepare" -> {
                    val context = MeasurementContext.fromJson(payload.optJSONObject("context"))
                    if (payload.has("context") && context == null) {
                        replyTo("measurement.error", JSONObject()
                            .put("sessionId", payload.optString("sessionId"))
                            .put("code", "invalid_session")
                            .put("message", "Invalid measurement context"))
                        return
                    }
                    measurementController?.prepare(
                        payload.optString("sessionId"),
                        payload.optString("channel", "both"),
                        context,
                        null,
                        emit = { eventType, eventPayload, _ -> replyTo(eventType, eventPayload) }
                    )
                    return
                }
                "measurement.playSweep" -> {
                    val context = MeasurementContext.fromJson(payload.optJSONObject("context"))
                    if (payload.has("context") && context == null) {
                        replyTo("measurement.error", JSONObject()
                            .put("sessionId", payload.optString("sessionId"))
                            .put("code", "invalid_session")
                            .put("message", "Invalid measurement context"))
                        return
                    }
                    measurementController?.playSweep(
                        payload.optString("sessionId"),
                        context,
                        null,
                        emit = { eventType, eventPayload, _ -> replyTo(eventType, eventPayload) }
                    )
                    return
                }
                "measurement.abort" -> {
                    measurementController?.cancel(
                        sessionId = payload.optString("sessionId"),
                        code = "calibration_aborted",
                        message = "Calibration cancelled",
                        replyTo = null,
                        emit = { eventType, eventPayload, _ -> replyTo(eventType, eventPayload) },
                    )
                    return
                }
                "measurement.diagnostics" -> {
                    val context = MeasurementContext.fromJson(payload.optJSONObject("context"))
                    if (context == null) return
                    measurementController?.updateDiagnostics(
                        payload.optString("sessionId"),
                        context,
                        payload.optInt("current", -1),
                        payload.optInt("total", -1),
                        payload.optJSONObject("diagnostics") ?: return
                    )
                    return
                }
                "probe.run" -> {
                    val bands = payload.optInt("bands", 128)
                    suspendProduction()
                    probeExecutor.submit {
                        try {
                            probeRunning.set(true)
                            lastProbeResults = DynamicsProcessingProbe().runFor(bands)
                        } catch (e: Throwable) {
                            Log.e(TAG, "Mailbox probe failed", e)
                        } finally {
                            probeRunning.set(false)
                            resumeProduction()
                        }
                        postProbeStatus(replyTo)
                    }
                    return
                }
                "probe.status" -> {
                    postProbeStatus(replyTo)
                    return
                }
                "probe.persistent.start" -> runPersistentProbe(payload.optInt("bands", 64))
                "probe.persistent.release" -> releasePersistentProbe()
                "virtualizer.on" -> setVirtualizer(true)
                "virtualizer.off" -> setVirtualizer(false)
                "probe.curve.apply" -> {
                    val commonArray = payload.optJSONArray("bandsDb")
                    val leftArray = payload.optJSONArray("leftBandsDb")
                    val rightArray = payload.optJSONArray("rightBandsDb")
                    commandOk = if (commonArray != null || leftArray != null || rightArray != null) {
                        val expectedBands = persistentBands
                        val common = parseStrictProbeArray(commonArray, expectedBands)
                        val left = parseStrictProbeArray(leftArray, expectedBands)
                        val right = parseStrictProbeArray(rightArray, expectedBands)
                        common != null
                            && ((leftArray == null && rightArray == null && applyPersistentBands(common))
                                || (leftArray != null && rightArray != null && left != null && right != null && applyPersistentBands(common, left, right)))
                    } else {
                        val curve = payload.optString("curve", "hollow")
                        applyPersistentCurve(curve)
                    }
                    if (!commandOk) commandError = "Persistent probe curve was rejected"
                }
                "diagnostics.deviceInfo" -> {
                    replyTo("diagnostics.deviceInfo", deviceInfoJson())
                    return
                }
                else -> {
                    Log.d(TAG, "mailbox: unknown command $type")
                    return
                }
            }
            replyTo("state.snapshot", stateSnapshotJson().put("ok", commandOk).apply { commandError?.let { put("error", it) } })
        }


        private fun postProbeStatus(replyTo: (String, JSONObject) -> Unit) {
            val results = lastProbeResults.orEmpty()
            var highest = -1
            val arr = JSONArray()
            for (r in results) {
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
            val persistent = if (isPersistentProbeActive()) JSONObject().apply {
                put("active", true)
                put("bands", persistentBands)
                put("curve", persistentCurveName ?: JSONObject.NULL)
                val sum = getPersistentProbeCurveSummary(0)
                if (sum != null) {
                    put("curveSummary", JSONObject().apply {
                        put("bandsTotal", sum.bandsTotal)
                        put("bandsCut", sum.bandsCut)
                        put("bandsFlat", sum.bandsFlat)
                    })
                }
                val right = getPersistentProbeCurveSummary(1)
                if (right != null) {
                    put("rightCurveSummary", JSONObject().apply {
                        put("bandsTotal", right.bandsTotal)
                        put("bandsCut", right.bandsCut)
                        put("bandsFlat", right.bandsFlat)
                    })
                }
            } else JSONObject().put("active", false).put("bands", 0)
            replyTo("probe.status", JSONObject().apply {
                put("running", probeRunning.get())
                put("available", lastProbeResults != null)
                put("results", arr)
                put("highest", highest)
                put("recommended", highest)
                put("persistent", persistent)
            })
        }
    }

    /** Mirrors WebServer.deviceInfoJson; runs on the mailbox worker thread (~400ms sample window). */
    private fun deviceInfoJson(): JSONObject {
        val rt = Runtime.getRuntime()
        val memInfo = Debug.MemoryInfo()
        Debug.getMemoryInfo(memInfo)
        val appCpu = cpuPercentFor(android.os.Process.myPid())
        val asPid = findProcessPid("audioserver") ?: findProcessPid("audioserver64")
        val asCpu = asPid?.let { cpuPercentFor(it) }
        return JSONObject().apply {
            put("javaHeapMax", rt.maxMemory())
            put("javaHeapTotal", rt.totalMemory())
            put("javaHeapFree", rt.freeMemory())
            put("nativeHeapAllocated", Debug.getNativeHeapAllocatedSize())
            put("nativeHeapSize", Debug.getNativeHeapSize())
            put("pssTotalKb", memInfo.totalPss)
            put("privateDirtyKb", memInfo.totalPrivateDirty)
            put("cpuPercent", appCpu)
            put("audioserverCpuPercent", asCpu ?: 0.0)
            put("audioserverPid", asPid ?: JSONObject.NULL)
            put("persistentProbeActive", isPersistentProbeActive())
            put("persistentProbeBands", getPersistentProbeBands())
        }
    }

    /** Samples utime+stime over a ~400ms window and returns percent of one core. */
    private fun cpuPercentFor(pid: Int): Double {
        fun ticks(): Long = try {
            val stat = File("/proc/$pid/stat").readText()
            val parts = stat.substring(stat.lastIndexOf(')') + 1).trim().split("\\s+".toRegex())
            (parts.getOrNull(12)?.toLongOrNull() ?: 0L) + (parts.getOrNull(13)?.toLongOrNull() ?: 0L)
        } catch (_: Throwable) { 0L }
        val t0 = ticks()
        val start = System.nanoTime()
        try { Thread.sleep(400) } catch (_: InterruptedException) {}
        val t1 = ticks()
        val wallSecs = (System.nanoTime() - start) / 1e9
        val clk = try {
            Os.sysconf(OsConstants._SC_CLK_TCK).toDouble()
        } catch (_: Throwable) { 100.0 }
        return if (wallSecs > 0) ((t1 - t0) / clk / wallSecs) * 100.0 else 0.0
    }

    /** Finds a process PID by its comm name via /proc; null when SELinux hides it. */
    private fun findProcessPid(name: String): Int? = try {
        File("/proc").list()?.firstOrNull { entry ->
            val pid = entry.toIntOrNull() ?: return@firstOrNull false
            try {
                File("/proc/$pid/stat").readText().let { stat ->
                    val s = stat.indexOf('(')
                    val e = stat.lastIndexOf(')')
                    s in 0 until e && stat.substring(s + 1, e) == name
                }
            } catch (_: Throwable) { false }
        }?.toIntOrNull()
    } catch (_: Throwable) { null }

    override fun applyPersistentCurve(curve: String): Boolean {
        val eq = dpEq() ?: return false
        if (!eq.isDiagnosticProbeActive() || persistentBands != DynamicsProcessingEq.INTERNAL_BANDS) return false
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
            val applied = eq.applyDiagnosticProbe(common)
            if (!applied) return false
            persistentCurveName = curve
            true
        } catch (e: Throwable) {
            Log.e(TAG, "applyPersistentCurve($curve) failed", e)
            false
        }
    }

    override fun applyPersistentBands(common: FloatArray, left: FloatArray?, right: FloatArray?): Boolean {
        val eq = dpEq() ?: return false
        val n = DynamicsProcessingEq.INTERNAL_BANDS
        if (!eq.isDiagnosticProbeActive() || persistentBands != n) return false
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
            if (applied) persistentCurveName = "custom"
            applied
        } catch (e: Throwable) {
            Log.e(TAG, "applyPersistentBands failed", e)
            false
        }
    }

    override fun getPersistentProbeCurve(): String? = if (isPersistentProbeActive()) persistentCurveName else null

    @Volatile private var effectInventory: List<AudioEffectDiagnostics.EffectInventoryEntry>? = null
    @Volatile private var sessionProbes: List<AudioEffectDiagnostics.SessionProbe>? = null

    /** Runs diagnostics synchronously; called from the mailbox worker thread. */
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

    override fun getPersistentProbeCurveSummary(channel: Int): DynamicsProcessingProbe.CurveSummary? {
        if (!isPersistentProbeActive() || persistentBands != DynamicsProcessingEq.INTERNAL_BANDS) return null
        return dpEq()?.getDiagnosticProbeCurveSummary(channel)
    }

    private fun dpEq() = engine as? DynamicsProcessingEq

    private fun rollbackCalibrationCandidate(candidateId: String): Boolean {
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
        engine?.release()
    }

    /** Re-create and restore the production engine after diagnostics. */
    private fun resumeProduction() {
        engine?.initialize()
    }

    private fun applyPresetWithFeedback(preset: Int): Boolean {
        val engine = engine ?: return false
        val eqName = engine.getCapabilities().presets[preset] ?: return false
        if (!engine.applyPreset(preset)) return false
        showEqAppliedToast(eqName)
        return true
    }

    private fun loadProfileWithFeedback(name: String): Boolean {
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

    private fun showCalibrationErrorToast(message: String) {
        mainHandler.post {
            Toast.makeText(this, "Calibration could not continue: $message", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Creates (once) and enables or disables the persistent session-0
     * Virtualizer at max strength. Called from the mailbox worker thread.
     */
    private fun setVirtualizer(on: Boolean) {
        try {
            val existing = persistentVirtualizer
            val v = if (existing != null) existing else {
                val created = Virtualizer(1000, 0)
                if (created.strengthSupported) created.setStrength(1000)
                persistentVirtualizer = created
                created
            }
            v.enabled = on
            Log.i(TAG, "Persistent Virtualizer $on (control=${v.hasControl()}, enabled=${v.enabled}, strength=${v.roundedStrength})")
        } catch (e: Throwable) {
            Log.e(TAG, "Virtualizer set($on) failed", e)
        }
    }

    /**
     * Canonical state snapshot for the hosted dashboard (protocol v1).
     * Mirrors shared/types/protocol.ts StateSnapshot in sweetspot-web.
     */
    private fun stateSnapshotJson(): JSONObject {
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
                val liveDspError = eq?.getLiveDspVerificationError()
                put("applicationVerified", eq != null && liveDspError == null)
                put("liveDspStatus", if (eq != null && liveDspError == null) "verified" else "degraded")
                (liveDspError ?: eq?.getLastCalibrationApplyError())?.let { put("applicationError", it) }
                put("transaction", calibrationTransactionJson(calibrationTransaction))
            })
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
