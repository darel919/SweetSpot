package com.darelisme.sweetspot.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.BitmapDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
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
import java.util.Locale

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
    val activePreset: OverlayPresetOption? = null,
    val calibrationMessage: String? = null,
)

enum class OverlayPresentation {
    USER_OPENED,
    AUTOMATIC,
}

interface OverlayActions {
    fun setDspEnabled(enabled: Boolean) {}
    fun setCalibrationEnabled(enabled: Boolean) {}
    fun applyPreset(option: OverlayPresetOption) {}
    fun setStartOnBoot(enabled: Boolean) {}
    fun setPairingVisible(visible: Boolean) {}
    fun startCalibration() {}
    fun stopSweetSpot() {}
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
        private val CHECKBOX_TEXT_COLORS = ColorStateList(
            arrayOf(
                intArrayOf(-android.R.attr.state_enabled),
                intArrayOf(),
            ),
            intArrayOf(0xFF77777C.toInt(), 0xFFFFFFFF.toInt()),
        )
        private val CHECKBOX_TINT_COLORS = ColorStateList(
            arrayOf(
                intArrayOf(-android.R.attr.state_enabled),
                intArrayOf(android.R.attr.state_checked),
                intArrayOf(),
            ),
            intArrayOf(0xFF5A5A60.toInt(), 0xFF6BE0D1.toInt(), 0xFFE8E8EA.toInt()),
        )

        const val CONNECTION_DISCONNECTED = "disconnected"
        const val CONNECTION_CONNECTING = "connecting"
        const val CONNECTION_WAITING = "waiting"
        const val CONNECTION_CONNECTED = "connected"
    }

    private enum class Page { HOME, PAIRING, CALIBRATION, EQ_PROFILE }

    private data class EqRowFocus(
        val eq: TextView,
        val presets: List<TextView>,
        val activePreset: TextView?,
    )

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
    private var presentationMode = OverlayPresentation.AUTOMATIC
    private var shownAtMs: Long = 0
    private var connectedAtMs: Long = 0
    private var focusAssigned = false
    private var pairingVisibilityReported = false

    fun show(presentation: OverlayPresentation = OverlayPresentation.AUTOMATIC) = mainHandler.post {
        if (!shown || presentation == OverlayPresentation.USER_OPENED) {
            presentationMode = presentation
        }
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
        } else if (state != CONNECTION_CONNECTED) {
            connectedAtMs = 0L
        }
        if (shown) refreshContent()
        reportPairingVisibility()
    }

    private val dismissRunnable = Runnable { hideInternal() }

    private fun scheduleDismiss() {
        mainHandler.removeCallbacks(dismissRunnable)
        if (!shown || presentationMode == OverlayPresentation.USER_OPENED) return
        val connectedPairingPage = connectionState == CONNECTION_CONNECTED &&
            connectedAtMs > 0L && (page == Page.PAIRING || forcePairingQr)
        val timeout = if (connectedPairingPage) DISMISS_AFTER_CONNECT_MS else INACTIVITY_DISMISS_MS
        val startedAt = if (connectedPairingPage) connectedAtMs else shownAtMs
        val remaining = timeout - (SystemClock.elapsedRealtime() - startedAt)
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
            connectedAtMs = if (connectionState == CONNECTION_CONNECTED && (page == Page.PAIRING || forcePairingQr)) {
                shownAtMs
            } else {
                0L
            }
            scheduleDismiss()
        } catch (_: Exception) {
            overlayView = null
            shown = false
            reportPairingVisibility()
        }
    }

    private fun hideInternal() {
        mainHandler.removeCallbacks(dismissRunnable)
        presentationMode = OverlayPresentation.AUTOMATIC
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
            scheduleDismiss()
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

        val dsp = addCheckbox(container, "DSP", state.dspEnabled, actions::setDspEnabled)
        val calibration = addCheckbox(
            container,
            "Calibration",
            state.calibrationAvailable && state.calibrationEnabled,
            actions::setCalibrationEnabled,
            enabled = state.calibrationAvailable,
        )
        val eqRow = addEqRow(container, state.presets, state.activePreset)
        val eqEntryId = eqRow.activePreset?.id ?: eqRow.eq.id
        dsp.nextFocusDownId = if (calibration.isEnabled) calibration.id else eqEntryId
        if (calibration.isEnabled) calibration.nextFocusDownId = eqEntryId
        eqRow.eq.nextFocusUpId = if (calibration.isEnabled) calibration.id else dsp.id
        eqRow.presets.forEach { preset ->
            preset.nextFocusUpId = if (calibration.isEnabled) calibration.id else dsp.id
        }

        addSpacer(container, 12)
        val calibrationMenu = addButton(container, "Calibration menu") { page = Page.CALIBRATION; refreshContent() }
        eqRow.eq.nextFocusDownId = calibrationMenu.id
        eqRow.presets.forEach { preset -> preset.nextFocusDownId = calibrationMenu.id }
        calibrationMenu.nextFocusUpId = eqEntryId
        addButton(container, "Show QR Link") { page = Page.PAIRING; forcePairingQr = true; refreshContent() }

        addSpacer(container, 12)
        addCheckbox(container, "Start On Boot", state.startOnBoot, actions::setStartOnBoot)

        addSpacer(container, 6)
        addButton(container, "Exit (Hide to Background)") { hideInternal() }
        addButton(container, "Exit (Stop SweetSpot)") { actions.stopSweetSpot() }
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
        addButton(container, "Show QR Link") { page = Page.PAIRING; forcePairingQr = true; refreshContent() }
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
        enabled: Boolean = true,
    ): CheckBox {
        val checkbox = CheckBox(context).apply {
            text = label
            textSize = 16f
            isChecked = checked
            isEnabled = enabled
            isFocusable = enabled
            isClickable = enabled
            setTextColor(CHECKBOX_TEXT_COLORS)
            setButtonTintList(CHECKBOX_TINT_COLORS)
            setOnCheckedChangeListener { _, next ->
                if (enabled) {
                    action(next)
                    refreshContent()
                }
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
        return checkbox
    }

    private fun addEqRow(
        parent: LinearLayout,
        options: List<OverlayPresetOption>,
        activePreset: OverlayPresetOption?,
    ): EqRowFocus {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val eq = createFocusText("EQ") {
            page = Page.EQ_PROFILE
            refreshContent()
        }.apply {
            id = View.generateViewId()
        }
        val initialIndex = options.indexOfFirst { it == activePreset }.coerceAtLeast(0)
        var selectedIndex = initialIndex
        var quickFocusActive = false
        var quickTexts: List<TextView> = emptyList()

        fun updatePresetIndicator() {
            quickTexts.forEachIndexed { index, text ->
                val selected = index == selectedIndex
                text.paintFlags = if (selected) {
                    text.paintFlags or Paint.UNDERLINE_TEXT_FLAG
                } else {
                    text.paintFlags and Paint.UNDERLINE_TEXT_FLAG.inv()
                }
                text.setTextColor(if (selected) 0xFFFFFFFF.toInt() else 0xFFE8E8EA.toInt())
            }
        }

        fun selectPreset(index: Int) {
            if (quickTexts.isEmpty()) return
            selectedIndex = index.coerceIn(0, quickTexts.lastIndex)
            updatePresetIndicator()
        }

        fun focusPreset(index: Int) {
            val preset = quickTexts.getOrNull(index) ?: return
            quickFocusActive = true
            selectPreset(index)
            preset.requestFocus()
        }

        val scroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            isFocusable = false
            isFocusableInTouchMode = false
            isFillViewport = true
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        }
        val quickRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        quickTexts = options.mapIndexed { index, option ->
            createFocusText(option.name.uppercase(Locale.ROOT)) {
                actions.applyPreset(option)
                refreshContent()
            }.apply {
                id = View.generateViewId()
                if (index == 0) nextFocusLeftId = eq.id
                setOnFocusChangeListener { view, hasFocus ->
                    if (hasFocus) {
                        if (!quickFocusActive) {
                            quickFocusActive = true
                            selectPreset(initialIndex)
                        }
                        view.post {
                            val viewportStart = scroll.scrollX
                            val viewportEnd = viewportStart + scroll.width
                            val target = when {
                                scroll.width <= 0 -> null
                                view.left < viewportStart -> view.left - dp(16)
                                view.right > viewportEnd -> view.right - scroll.width + dp(16)
                                else -> null
                            }
                            target?.let { scroll.smoothScrollTo(it.coerceAtLeast(0), 0) }
                        }
                    } else {
                        view.post {
                            if (quickTexts.none { it.hasFocus() }) quickFocusActive = false
                        }
                    }
                }
                setOnKeyListener { _, keyCode, event ->
                    when {
                        keyCode == KeyEvent.KEYCODE_DPAD_LEFT -> {
                            if (event.action == KeyEvent.ACTION_DOWN) {
                                if (index == 0) {
                                    quickFocusActive = false
                                    eq.requestFocus()
                                } else {
                                    focusPreset(index - 1)
                                }
                            }
                            true
                        }
                        keyCode == KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            if (event.action == KeyEvent.ACTION_DOWN && index < quickTexts.lastIndex) {
                                focusPreset(index + 1)
                            }
                            true
                        }
                        keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER -> {
                            if (event.action == KeyEvent.ACTION_UP) performClick()
                            true
                        }
                        else -> false
                    }
                }
            }
        }
        quickTexts.forEach { text ->
            quickRow.addView(text, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { marginEnd = dp(8) })
        }
        updatePresetIndicator()
        eq.nextFocusRightId = quickTexts.firstOrNull()?.id ?: View.NO_ID
        eq.setOnKeyListener { _, keyCode, event ->
            when {
                keyCode == KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (event.action == KeyEvent.ACTION_DOWN) focusPreset(0)
                    true
                }
                keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER -> {
                    if (event.action == KeyEvent.ACTION_UP) eq.performClick()
                    true
                }
                else -> false
            }
        }
        row.addView(eq, LinearLayout.LayoutParams(dp(64), LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            marginEnd = dp(6)
        })
        if (quickTexts.isNotEmpty()) {
            row.addView(createEqArrow("<"))
            scroll.addView(quickRow, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
            row.addView(scroll, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(createEqArrow(">"))
        }
        parent.addView(row, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(6) })
        return EqRowFocus(eq, quickTexts, quickTexts.getOrNull(initialIndex))
    }

    private fun addButton(parent: LinearLayout, label: String, action: () -> Unit): Button {
        val button = createButton(label, action)
        parent.addView(button, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(6) })
        if (!focusAssigned) {
            focusAssigned = true
            button.post { button.requestFocus() }
        }
        return button
    }

    private fun createButton(label: String, action: () -> Unit): Button = Button(context).apply {
        text = label
        textSize = 16f
        isAllCaps = false
        isFocusable = true
        isClickable = true
        setOnClickListener { action() }
    }

    private fun createFocusText(label: String, action: () -> Unit): TextView = TextView(context).apply {
        text = label
        textSize = 16f
        gravity = Gravity.CENTER
        setTextColor(0xFFE8E8EA.toInt())
        setPadding(dp(8), dp(10), dp(8), dp(10))
        minHeight = dp(48)
        isFocusable = true
        isClickable = true
        setOnClickListener { action() }
    }

    private fun createEqArrow(symbol: String): TextView = TextView(context).apply {
        text = symbol
        textSize = 18f
        gravity = Gravity.CENTER
        setTextColor(0xFFB8B8BC.toInt())
        minWidth = dp(20)
        minHeight = dp(48)
        isFocusable = false
        isClickable = false
    }

    private fun addSpacer(parent: LinearLayout, height: Int) {
        parent.addView(View(context), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(height),
        ))
    }

    private fun dp(value: Int): Int = (value * density).roundToInt()

    private fun pairUrl(code: String): String = pairingUrl ?: code
}
