package com.darelisme.sweetspot

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.Window
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.lang.ref.WeakReference

class CalibrationActivity : Activity() {
    companion object {
        const val EXTRA_SESSION_ID = "sessionId"

        @Volatile
        private var activeActivity: WeakReference<CalibrationActivity>? = null

        fun updateStatus(sessionId: String, status: String) {
            activeActivity?.get()?.let { activity ->
                if (activity.sessionId != sessionId) return
                activity.runOnUiThread { activity.statusView?.text = status }
            }
        }

        fun finishForSession(sessionId: String) {
            activeActivity?.get()?.let { activity ->
                if (activity.sessionId != sessionId) return
                activity.runOnUiThread {
                    activity.expectedClose = true
                    activity.finish()
                }
            }
        }
    }

    private var sessionId: String = ""
    private var statusView: TextView? = null
    private var expectedClose = false
    private var goneSent = false
    private var readySent = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sessionId = intent.getStringExtra(EXTRA_SESSION_ID).orEmpty()
        if (sessionId.isBlank()) {
            finish()
            return
        }

        activeActivity = WeakReference(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(buildContent())
        requestFullscreen(window)
    }

    override fun onResume() {
        super.onResume()
        if (!readySent) {
            readySent = true
            sendServiceAction(SweetSpotService.ACTION_CALIBRATION_UI_READY)
        }
    }

    override fun onStop() {
        super.onStop()
        if (!expectedClose && !isChangingConfigurations) sendUiGone()
    }

    override fun onDestroy() {
        sendUiGone()
        if (activeActivity?.get() === this) activeActivity = null
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        sendServiceAction(SweetSpotService.ACTION_CALIBRATION_CANCEL)
    }

    private fun buildContent(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            setPadding(64, 48, 64, 48)
            setBackgroundColor(0xFF0A0A0B.toInt())
        }
        val title = TextView(this).apply {
            text = "SweetSpot Calibration"
            textSize = 30f
            setTextColor(0xFFFFFFFF.toInt())
        }
        root.addView(title, centeredParams())

        statusView = TextView(this).apply {
            text = "Preparing measurement…"
            textSize = 22f
            setTextColor(0xFFE8E8EA.toInt())
            setPadding(0, 28, 0, 32)
        }
        root.addView(statusView, centeredParams())

        val hint = TextView(this).apply {
            text = "The TV shows the current stage and progress.\n\n" +
                "Hold the iPhone at ear height.\n" +
                "Point the bottom / USB-C edge toward the center of the speakers.\n" +
                "Keep the same orientation and do not cover the bottom microphone.\n" +
                "Set the TV volume when the pink-noise step starts.\n" +
                "Keep the volume unchanged after you continue."
            textSize = 17f
            setTextColor(0xFFB8B8BC.toInt())
            setPadding(0, 0, 0, 32)
            gravity = android.view.Gravity.CENTER
        }
        root.addView(hint, centeredParams())

        val cancel = Button(this).apply {
            text = "Cancel"
            setOnClickListener { sendServiceAction(SweetSpotService.ACTION_CALIBRATION_CANCEL) }
        }
        root.addView(cancel, centeredParams())
        return root
    }

    private fun centeredParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { gravity = android.view.Gravity.CENTER_HORIZONTAL }

    private fun sendUiGone() {
        if (goneSent || expectedClose || sessionId.isBlank()) return
        goneSent = true
        sendServiceAction(SweetSpotService.ACTION_CALIBRATION_UI_CLOSED)
    }

    private fun sendServiceAction(action: String) {
        val intent = Intent(this, SweetSpotService::class.java).apply {
            this.action = action
            putExtra(EXTRA_SESSION_ID, sessionId)
        }
        try {
            startService(intent)
        } catch (_: Exception) {
            if (!expectedClose) finish()
        }
    }

    private fun requestFullscreen(window: Window) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let { controller ->
                controller.hide(WindowInsets.Type.systemBars())
                controller.systemBarsBehavior =
                    android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                )
        }
    }
}
