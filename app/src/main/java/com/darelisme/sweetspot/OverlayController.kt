package com.darelisme.sweetspot

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.BitmapDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * System-level floating panel shown above other apps (e.g. YouTube).
 *
 * Implemented with a [WindowManager] view of type [WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY]
 * so it can appear over arbitrary foreground apps. Requires the
 * `SYSTEM_ALERT_WINDOW` permission, which must be granted on the device
 * (e.g. `adb shell appops set <pkg> android:system_alert_window allow`).
 *
 * All [WindowManager] mutations are posted to the main thread because they are
 * UI operations and may be triggered from the web-server worker thread or the
 * relay client thread.
 *
 * The panel shows the pairing state: relay status, QR code pointing at the
 * hosted dashboard with the current pair code, and the code as text fallback.
 */
class OverlayController(private val context: Context) {

    companion object {
        private const val TAG = "SweetSpotOverlay"
        private const val QR_SIZE_PX = 320

        // Relay states, mirrored from RelayClient.
        const val RELAY_DISCONNECTED = "disconnected"
        const val RELAY_CONNECTING = "connecting"
        const val RELAY_WAITING = "waiting"
        const val RELAY_CONNECTED = "connected"
    }

    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var overlayView: View? = null
    @Volatile
    private var shown = false

    // Latest pair info to render; set before show() or while shown.
    @Volatile
    private var pairCode: String? = null

    @Volatile
    private var relayState: String = RELAY_DISCONNECTED

    fun show() {
        mainHandler.post { showInternal() }
    }

    fun hide() {
        mainHandler.post { hideInternal() }
    }

    fun isShown(): Boolean = shown

    /** Updates the pair code and repaints if visible. Call from any thread. */
    fun updatePairInfo(code: String) {
        mainHandler.post {
            pairCode = code
            if (shown) refreshContent()
        }
    }

    /** Updates the relay connection state and repaints if visible. Call from any thread. */
    fun updateRelayState(state: String) {
        mainHandler.post {
            relayState = state
            if (shown) refreshContent()
        }
    }

    private fun showInternal() {
        if (shown) return
        if (!Settings.canDrawOverlays(context)) {
            Log.w(
                TAG,
                "SYSTEM_ALERT_WINDOW not granted — overlay will not show. " +
                    "Grant with: adb shell appops set ${context.packageName} " +
                    "android:system_alert_window allow"
            )
            return
        }
        val view = buildView()
        val params = WindowManager.LayoutParams(
            (context.resources.displayMetrics.widthPixels * 0.34).toInt(),
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.END or Gravity.CENTER_VERTICAL
        try {
            windowManager.addView(view, params)
            overlayView = view
            shown = true
            Log.i(TAG, "Overlay shown")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add overlay view", e)
        }
    }

    private fun hideInternal() {
        if (!shown) return
        overlayView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                Log.e(TAG, "removeView failed", e)
            }
        }
        overlayView = null
        shown = false
        Log.i(TAG, "Overlay hidden")
    }

    /** Rebuilds the content of the live overlay (called on the main thread only). */
    private fun refreshContent() {
        if (!shown) return
        val old = overlayView ?: return
        windowManager.removeView(old)
        val view = buildView()
        try {
            windowManager.addView(view, (old.layoutParams as WindowManager.LayoutParams))
            overlayView = view
        } catch (e: Exception) {
            Log.e(TAG, "refresh failed", e)
        }
    }

    private fun statusText(): String = when (relayState) {
        RELAY_CONNECTED -> "Dashboard connected"
        RELAY_WAITING -> "Waiting for dashboard — scan the code"
        RELAY_CONNECTING -> "Connecting to SweetSpot cloud..."
        else -> "Offline — check TV network"
    }

    private fun buildView(): View {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xCC000000.toInt())
            setPadding(40, 48, 40, 48)
            gravity = Gravity.CENTER_HORIZONTAL
        }

        val title = TextView(context).apply {
            textSize = 26f
            setTextColor(0xFFFFFFFF.toInt())
            text = "SweetSpot"
        }
        container.addView(title)

        val status = TextView(context).apply {
            textSize = 20f
            setTextColor(0xFFE8E8EA.toInt())
            text = statusText()
            setPadding(0, 24, 0, 24)
        }
        container.addView(status)

        val code = pairCode
        if (code != null && relayState != RELAY_CONNECTED) {
            try {
                val qr = ImageView(context).apply {
                    contentDescription = "Pairing QR code $code"
                    background = BitmapDrawable(context.resources, QrCode.generate(pairUrl(code), QR_SIZE_PX))
                    setPadding(16, 16, 16, 16)
                }
                container.addView(qr)

                val urlHint = TextView(context).apply {
                    textSize = 15f
                    setTextColor(0xFFB8B8BC.toInt())
                    text = Config.DASHBOARD_URL.removePrefix("https://") + "/connect/" + code
                    setPadding(0, 20, 0, 4)
                }
                container.addView(urlHint)
            } catch (e: Exception) {
                Log.e(TAG, "QR generation failed", e)
            }
        }

        val lanIp = NetworkUtils.getLanIpAddress()
        if (lanIp != null) {
            val lan = TextView(context).apply {
                textSize = 13f
                setTextColor(0xFF77777C.toInt())
                text = "LAN debug: http://$lanIp:${Config.WEB_PORT}"
                setPadding(0, 12, 0, 0)
            }
            container.addView(lan)
        }

        val hideBtn = TextView(context).apply {
            text = "Hide"
            textSize = 18f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(24, 12, 24, 12)
            setBackgroundColor(0x55333333)
            isClickable = true
            setOnClickListener { hideInternal() }
        }
        container.addView(hideBtn)
        return container
    }

    private fun pairUrl(code: String): String =
        "${Config.DASHBOARD_URL}/connect/${PairCodeManager.normalize(code)}"
}
