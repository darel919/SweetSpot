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
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.HorizontalScrollView
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
    fun setPairingVisible(visible: Boolean) {}
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

    private enum class Page { HOME, PAIRING, CALIBRATION, EQ_PROFILE }

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
    private var pairingVisibilityReported = false

    fun show() = mainHandler.post {
        page = Page.HOME
        forcePairingQr = false
        if (shown) refreshContent() else showInternal()
        reportPairingVisibility()
    }

    fun showPairing() = mainHandler.post {
        page = Page.PAIRING
        forcePairingQr = true
        if (shown) refreshContent() else showInternal()
        reportPairingVisibility()
    }

    fun hide() = mainHandler.post { hideInternal() }

    fun refresh() = mainHandler.post {
        if (shown) refreshContent()
        reportPairingVisibility()
    }

    fun isShown(): Boolean = shown

    fun updatePairInfo(code: String, url: String? = null) = mainHandler.post {
        pairCode = code
        pairingUrl = url
        if (shown) refreshContent()
        reportPairingVisibility()
    }

    fun updateConnectionState(state: String) = mainHandler.post {
        connectionState = state
        if (state == CONNECTION_CONNECTED && shown && connectedAtMs == 0L) {
            connectedAtMs = SystemClock.elapsedRealtime()
            if (page == Page.PAIRING || forcePairingQr) scheduleConnectDismiss()
        }
        if (shown) refreshContent()
        reportPairingVisibility()
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
            reportPairingVisibility()
        }
    }

    private fun hideInternal() {
        mainHandler.removeCallbacks(dismissRunnable)
        if (!shown) {
            reportPairingVisibility()
            return
        }
        overlayView?.let { view ->
            try { windowManager.removeView(view) } catch (_: Exception) {}
        }
        overlayView = null
        shown = false
        page = Page.HOME
        forcePairingQr = false
        reportPairingVisibility()
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
            reportPairingVisibility()
        } catch (_: Exception) {
            overlayView = null
            shown = false
            reportPairingVisibility()
        }
    }

    private fun pairingUiVisible(): Boolean = shown && (
        page == Page.PAIRING
            || forcePairingQr
            || page == Page.HOME && shouldShowPairingQr(pairCode, connectionState)
        )

    private fun reportPairingVisibility() {
        val visible = pairingUiVisible()
        if (visible == pairingVisibilityReported) return
        pairingVisibilityReported = visible
        actions.setPairingVisible(visible)
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
            Page.EQ_PROFILE -> buildEqProfileMenu(container)
        }
        return scroll
    }

    private fun buildHome(container: LinearLayout) {
        val state = stateProvider()
        val code = pairCode.orEmpty()
        if (forcePairingQr || shouldShowPairingQr(code, connectionState)) buildPairingSection(container, compact = true)

        addCheckbox(container, "DSP", state.dspEnabled, actions::setDspEnabled)
        if (state.calibrationAvailable) {
            addCheckbox(container, "Calibration profile", state.calibrationEnabled, actions::setCalibrationEnabled)
        }

        addButton(container, "EQ profile") {
            page = Page.EQ_PROFILE
            refreshContent()
        }
        if (state.presets.isNotEmpty()) {
            addText(container, "Quick EQ", 17f, 0xFFB8B8BC.toInt(), Gravity.START, top = 16, bottom = 4)
            addQuickProfileSwitcher(container, state.presets)
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
            addCheckbox(container, "Calibration profile", state.calibrationEnabled, actions::setCalibrationEnabled)
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

    private fun buildEqProfileMenu(container: LinearLayout) {
        val options = stateProvider().presets
        addText(container, "EQ profile", 22f, 0xFFFFFFFF.toInt(), Gravity.CENTER, bottom = 8)
        addText(container, "Choose a built-in or saved EQ profile.", 16f, 0xFFE8E8EA.toInt(), Gravity.CENTER, bottom = 12)
        if (options.isEmpty()) {
            addText(container, "No EQ profiles are available.", 16f, 0xFFB8B8BC.toInt(), Gravity.CENTER, bottom = 12)
        } else {
            options.forEach { option ->
                addButton(container, option.name) {
                    actions.applyPreset(option)
                    page = Page.HOME
                    refreshContent()
                }
            }
        }
        addButton(container, "Back to controls") { page = Page.HOME; refreshContent() }
        addButton(container, "Hide to background") { hideInternal() }
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

    private fun addCheckbox(
        parent: LinearLayout,
        label: String,
        checked: Boolean,
        action: (Boolean) -> Unit,
    ) {
        val checkbox = CheckBox(context).apply {
            text = label
            textSize = 16f
            isFocusable = true
            isClickable = true
            isChecked = checked
            setOnCheckedChangeListener { _, next ->
                action(next)
                refreshContent()
            }
        }
        parent.addView(checkbox, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(6) })
        if (!focusAssigned) {
            focusAssigned = true
            checkbox.post { checkbox.requestFocus() }
        }
    }

    private fun addQuickProfileSwitcher(parent: LinearLayout, options: List<OverlayPresetOption>) {
        val scroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        }
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        options.forEach { option ->
            val button = Button(context).apply {
                text = option.name
                textSize = 14f
                isFocusable = true
                isClickable = true
                setOnClickListener {
                    actions.applyPreset(option)
                    refreshContent()
                }
                setOnFocusChangeListener { view, hasFocus ->
                    if (hasFocus) {
                        view.post { scroll.smoothScrollTo((view.left - dp(16)).coerceAtLeast(0), 0) }
                    }
                }
            }
            row.addView(button, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { marginEnd = dp(6) })
        }
        scroll.addView(row, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        parent.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(6) })
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
