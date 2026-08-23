package com.darelisme.sweetspot

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
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
 * UI operations and may be triggered from the web-server worker thread.
 */
class OverlayController(private val context: Context) {

    companion object {
        private const val TAG = "SweetSpotOverlay"
    }

    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var overlayView: View? = null
    @Volatile
    private var shown = false

    fun show() {
        mainHandler.post { showInternal() }
    }

    fun hide() {
        mainHandler.post { hideInternal() }
    }

    fun isShown(): Boolean = shown

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
            (context.resources.displayMetrics.widthPixels * 0.5).toInt(),
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

    private fun buildView(): View {
        val ip = NetworkUtils.getLanIpAddress() ?: "unknown"
        val url = "http://$ip:${Config.WEB_PORT}"

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xCC000000.toInt())
            setPadding(40, 48, 40, 48)
        }

        val title = TextView(context).apply {
            textSize = 26f
            setTextColor(0xFFFFFFFF.toInt())
            text = "SweetSpot"
        }

        val status = TextView(context).apply {
            textSize = 20f
            setTextColor(0xFFE8E8EA.toInt())
            text = "Audio service running\n\nWeb interface:\n$url\n\n" +
                "Open it on your phone/PC to control EQ."
            setPadding(0, 24, 0, 24)
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

        container.addView(title)
        container.addView(status)
        container.addView(hideBtn)
        return container
    }
}
