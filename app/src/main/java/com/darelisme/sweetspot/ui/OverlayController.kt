package com.darelisme.sweetspot.ui

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.BitmapDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.roundToInt

data class OverlayPresetOption(
    val name: String,
    val presetId: Int? = null,
    val profileName: String? = null,
) {
    init { require((presetId == null) xor (profileName == null)) }
}

data class OverlayState(
    val dspEnabled: Boolean = false,
    val calibrationAvailable: Boolean = false,
    val calibrationEnabled: Boolean = false,
    val startOnBoot: Boolean = true,
    val presets: List<OverlayPresetOption> = emptyList(),
    val calibrationMessage: String? = null,
)

interface OverlayActions {
    fun setDspEnabled(enabled: Boolean) {}
    fun setCalibrationEnabled(enabled: Boolean) {}
    fun applyPreset(option: OverlayPresetOption) {}
    fun setStartOnBoot(enabled: Boolean) {}
    fun startCalibration() {}
}

/** Lightweight system overlay for pairing and the small set of TV controls. */
class OverlayController(
    private val context: Context,
    private val stateProvider: () -> OverlayState = { OverlayState() },
    private val actions: OverlayActions = object : OverlayActions {},
) {

    companion object {
        private const val QR_SIZE_PX = 260
        private const val DISMISS_AFTER_CONNECT_MS = 5_000L
        private const val INACTIVITY_DISMISS_MS = 30_000L

        const val CONNECTION_DISCONNECTED = "disconnected"
        const val CONNECTION_CONNECTING = "connecting"
        const val CONNECTION_WAITING = "waiting"
        const val CONNECTION_CONNECTED = "connected"
    }

    private enum class Page { HOME, PAIRING, CALIBRATION }

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val density: Float get() = context.resources.displayMetrics.density

    @Volatile private var overlayView: View? = null
    @Volatile private var shown = false
    @Volatile private var pairCode: String? = null
    @Volatile private var pairingUrl: String? = null
    @Volatile private var connectionState: String = CONNECTION_DISCONNECTED
    private var page = Page.HOME
    private var forcePairingQr = false
    private var shownAtMs: Long = 0
    private var connectedAtMs: Long = 0
    private var focusAssigned = false

    fun show() = mainHandler.post {
        page = Page.HOME
        forcePairingQr = false
        if (shown) refreshContent() else showInternal()
    }

    fun showPairing() = mainHandler.post {
        page = Page.PAIRING
        forcePairingQr = true
        if (shown) refreshContent() else showInternal()
    }

    fun hide() = mainHandler.post { hideInternal() }

    fun refresh() = mainHandler.post { if (shown) refreshContent() }

    fun isShown(): Boolean = shown

    fun updatePairInfo(code: String, url: String? = null) = mainHandler.post {
        pairCode = code
        pairingUrl = url
        if (shown) refreshContent()
    }

    fun updateConnectionState(state: String) = mainHandler.post {
        connectionState = state
        if (state == CONNECTION_CONNECTED && shown && connectedAtMs == 0L) {
            connectedAtMs = SystemClock.elapsedRealtime()
            if (page == Page.PAIRING || forcePairingQr) scheduleConnectDismiss()
        }
        if (shown) refreshContent()
    }

    private val dismissRunnable = Runnable { hideInternal() }

    private fun scheduleInactivityDismiss() {
        mainHandler.removeCallbacks(dismissRunnable)
        val remaining = INACTIVITY_DISMISS_MS - (SystemClock.elapsedRealtime() - shownAtMs)
        if (remaining <= 0) hideInternal() else mainHandler.postDelayed(dismissRunnable, remaining)
    }

    private fun scheduleConnectDismiss() {
        mainHandler.removeCallbacks(dismissRunnable)
        val remaining = DISMISS_AFTER_CONNECT_MS - (SystemClock.elapsedRealtime() - connectedAtMs)
        if (remaining <= 0) hideInternal() else mainHandler.postDelayed(dismissRunnable, remaining)
    }

    private fun showInternal() {
        if (shown) return
        if (!Settings.canDrawOverlays(context)) return
        val view = buildView()
        val screenWidth = context.resources.displayMetrics.widthPixels
        val maxWidth = (screenWidth * 0.9f).toInt().coerceAtLeast(1)
        val minWidth = dp(360).coerceAtMost(maxWidth)
        val width = (screenWidth * 0.42f).toInt().coerceIn(minWidth, maxWidth)
        val params = WindowManager.LayoutParams(
            width,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL }
        try {
            windowManager.addView(view, params)
            overlayView = view
            shown = true
            shownAtMs = SystemClock.elapsedRealtime()
            connectedAtMs = 0
            scheduleInactivityDismiss()
        } catch (_: Exception) {
            overlayView = null
            shown = false
        }
    }

    private fun hideInternal() {
        mainHandler.removeCallbacks(dismissRunnable)
        if (!shown) return
        overlayView?.let { view ->
            try { windowManager.removeView(view) } catch (_: Exception) {}
        }
        overlayView = null
        shown = false
        page = Page.HOME
        forcePairingQr = false
    }

    private fun refreshContent() {
        if (!shown) return
        val old = overlayView ?: return
        val params = old.layoutParams as? WindowManager.LayoutParams ?: return
        try {
            windowManager.removeView(old)
            val view = buildView()
            windowManager.addView(view, params)
            overlayView = view
            shownAtMs = SystemClock.elapsedRealtime()
            scheduleInactivityDismiss()
        } catch (_: Exception) {
            overlayView = null
            shown = false
        }
    }

    private fun statusText(): String = when (connectionState) {
        CONNECTION_CONNECTED -> "Dashboard connected directly"
        CONNECTION_WAITING -> "Scan the QR code to open the dashboard"
        CONNECTION_CONNECTING -> "Connecting directly to SweetSpot…"
        else -> "Offline. Check the TV network"
    }

    private fun buildView(): View {
        focusAssigned = false
        val scroll = ScrollView(context)
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xEE000000.toInt())
            setPadding(dp(28), dp(30), dp(28), dp(30))
        }
        scroll.addView(container)

        addText(container, "SweetSpot", 26f, 0xFFFFFFFF.toInt(), Gravity.CENTER)
        addText(container, statusText(), 17f, 0xFFE8E8EA.toInt(), Gravity.CENTER, bottom = 14)

        when (page) {
            Page.HOME -> buildHome(container)
            Page.PAIRING -> buildPairing(container)
            Page.CALIBRATION -> buildCalibration(container)
        }
        return scroll
    }

    private fun buildHome(container: LinearLayout) {
        val state = stateProvider()
        val code = pairCode.orEmpty()
        if (forcePairingQr || shouldShowPairingQr(code, connectionState)) buildPairingSection(container, compact = true)

        addButton(container, "DSP: ${if (state.dspEnabled) "ON" else "OFF"}") {
            actions.setDspEnabled(!state.dspEnabled)
            refreshContent()
        }
        if (state.calibrationAvailable) {
            addButton(container, "Calibration profile: ${if (state.calibrationEnabled) "ON" else "OFF"}") {
                actions.setCalibrationEnabled(!state.calibrationEnabled)
                refreshContent()
            }
        }

        addText(container, "EQ presets", 17f, 0xFFB8B8BC.toInt(), Gravity.START, top = 16, bottom = 4)
        state.presets.forEach { option ->
            addButton(container, option.name) {
                actions.applyPreset(option)
                refreshContent()
            }
        }
        addButton(container, "Calibration menu") { page = Page.CALIBRATION; refreshContent() }
        addButton(container, "Show QR link") { page = Page.PAIRING; forcePairingQr = true; refreshContent() }
        addButton(container, "Start on boot: ${if (state.startOnBoot) "ON" else "OFF"}") {
            actions.setStartOnBoot(!state.startOnBoot)
            refreshContent()
        }
        addButton(container, "Hide to background") { hideInternal() }
    }

    private fun buildPairing(container: LinearLayout) {
        buildPairingSection(container, compact = false)
        addButton(container, "Back to controls") { page = Page.HOME; forcePairingQr = false; refreshContent() }
        addButton(container, "Hide to background") { hideInternal() }
    }

    private fun buildCalibration(container: LinearLayout) {
        val state = stateProvider()
        addText(container, "Calibration", 22f, 0xFFFFFFFF.toInt(), Gravity.CENTER, bottom = 8)
        addText(
            container,
            state.calibrationMessage
                ?: "Connect a phone to the web dashboard with the QR code, then choose Auto Room Calibration. The TV plays, analyzes, and verifies every sweep.",
            16f,
            0xFFE8E8EA.toInt(),
            Gravity.CENTER,
            bottom = 12,
        )
        if (state.calibrationAvailable) {
            addButton(container, "Calibration profile: ${if (state.calibrationEnabled) "ON" else "OFF"}") {
                actions.setCalibrationEnabled(!state.calibrationEnabled)
                refreshContent()
            }
        }
        addButton(container, "Start calibration") {
            actions.startCalibration()
            page = Page.PAIRING
            forcePairingQr = true
            refreshContent()
        }
        addButton(container, "Show QR link") { page = Page.PAIRING; forcePairingQr = true; refreshContent() }
        addButton(container, "Back to controls") { page = Page.HOME; refreshContent() }
    }

    private fun buildPairingSection(container: LinearLayout, compact: Boolean) {
        val code = pairCode.orEmpty()
        if (code.isBlank()) {
            addText(container, "Pairing code is not ready yet.", 16f, 0xFFB8B8BC.toInt(), Gravity.CENTER, bottom = 10)
            return
        }
        val connectUrl = pairUrl(code)
        try {
            val qr = ImageView(context).apply {
                contentDescription = "Pairing QR code $code"
                background = BitmapDrawable(context.resources, QrCode.generate(connectUrl, if (compact) 210 else QR_SIZE_PX))
                setPadding(dp(8), dp(8), dp(8), dp(8))
            }
            container.addView(qr, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                if (compact) dp(230) else dp(280),
            ).apply { gravity = Gravity.CENTER; bottomMargin = dp(8) })
        } catch (_: Exception) {}
        addText(container, "Scan this QR code with Safari. The secure link is encoded in the QR.", 14f, 0xFFB8B8BC.toInt(), Gravity.CENTER, bottom = 4)
        addText(container, "Code: $code", 16f, 0xFFFFFFFF.toInt(), Gravity.CENTER, bottom = 10)
    }

    private fun addText(
        parent: LinearLayout,
        value: String,
        size: Float,
        color: Int,
        gravity: Int,
        top: Int = 0,
        bottom: Int = 0,
    ) {
        val text = TextView(context).apply {
            text = value
            textSize = size
            setTextColor(color)
            this.gravity = gravity
            setPadding(0, dp(top), 0, dp(bottom))
        }
        parent.addView(text, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ))
    }

    private fun addButton(parent: LinearLayout, label: String, action: () -> Unit) {
        val button = Button(context).apply {
            text = label
            textSize = 16f
            isFocusable = true
            isClickable = true
            setOnClickListener { action() }
        }
        parent.addView(button, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(6) })
        if (!focusAssigned) {
            focusAssigned = true
            button.post { button.requestFocus() }
        }
    }

    private fun dp(value: Int): Int = (value * density).roundToInt()

    private fun pairUrl(code: String): String = pairingUrl ?: code
}
