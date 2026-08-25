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
import kotlin.math.roundToInt

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

        fun updatePrimaryAction(sessionId: String, label: String?) {
            activeActivity?.get()?.let { activity ->
                if (activity.sessionId != sessionId) return
                activity.runOnUiThread {
                    val button = activity.primaryActionButton ?: return@runOnUiThread
                    button.text = label.orEmpty()
                    button.visibility = if (label.isNullOrBlank()) View.GONE else View.VISIBLE
                    if (!label.isNullOrBlank()) button.requestFocus()
                }
            }
        }

        internal fun updatePositionGuide(sessionId: String, context: MeasurementContext?, state: CalibrationGuideState) {
            activeActivity?.get()?.let { activity ->
                if (activity.sessionId != sessionId) return
                activity.runOnUiThread {
                    if (activity.sessionId != sessionId) return@runOnUiThread
                    val guide = activity.positionGuideView ?: return@runOnUiThread
                    if (context == null) guide.showEmpty() else guide.show(context, state)
                }
            }
        }

        internal fun updateGraph(sessionId: String, response: MeasurementResponse) {
            activeActivity?.get()?.let { activity ->
                if (activity.sessionId != sessionId || response.sessionId != sessionId) return
                val summary = MeasurementResponseSummary.from(response).displayText()
                activity.runOnUiThread {
                    if (activity.sessionId == sessionId) {
                        activity.measurementSummaryView?.text = summary
                        activity.graphView?.setMeasurementResponse(response)
                    }
                }
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
    private var positionGuideView: CalibrationPositionGuideView? = null
    private var measurementSummaryView: TextView? = null
    private var graphView: CalibrationGraphView? = null
    private var primaryActionButton: Button? = null
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
        val density = resources.displayMetrics.density
        val horizontalPadding = (28f * density).roundToInt()
        val verticalPadding = (14f * density).roundToInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding)
            setBackgroundColor(0xFF0A0A0B.toInt())
        }
        val title = TextView(this).apply {
            text = "SweetSpot Calibration"
            textSize = 27f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = android.view.Gravity.CENTER
        }
        root.addView(title, fullWidthParams(bottom = 2))

        statusView = TextView(this).apply {
            text = "Preparing measurement…"
            textSize = 19f
            setTextColor(0xFFE8E8EA.toInt())
            gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, (6f * density).roundToInt())
        }
        root.addView(statusView, fullWidthParams(bottom = 6))

        val positionGuide = CalibrationPositionGuideView(this)
        positionGuideView = positionGuide

        measurementSummaryView = TextView(this).apply {
            text = "Waiting for phone data."
            textSize = 16f
            setTextColor(0xFFB8B8BC.toInt())
            gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, (5f * density).roundToInt())
        }

        val graph = CalibrationGraphView(this)
        graphView = graph

        val graphPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF111214.toInt())
            addView(measurementSummaryView, fullWidthParams())
            addView(graph, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ))
        }

        val mainContent = LinearLayout(this).apply {
            orientation = if (resources.configuration.screenWidthDp >= 800) {
                LinearLayout.HORIZONTAL
            } else {
                LinearLayout.VERTICAL
            }
            gravity = android.view.Gravity.FILL
        }
        if (mainContent.orientation == LinearLayout.HORIZONTAL) {
            addWeightedPanel(mainContent, positionGuide, 0.38f, right = 8)
            addWeightedPanel(mainContent, graphPanel, 0.62f)
        } else {
            addWeightedPanel(mainContent, positionGuide, 0.34f, bottom = 8)
            addWeightedPanel(mainContent, graphPanel, 0.66f)
        }
        root.addView(mainContent, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f,
        ).apply {
            setMargins(0, 0, 0, (8f * density).roundToInt())
        })

        val hint = TextView(this).apply {
            text = "Keep the iPhone upright.\n" +
                "Point the bottom edge toward the center of the TV."
            textSize = 15f
            setTextColor(0xFFB8B8BC.toInt())
            gravity = android.view.Gravity.CENTER_VERTICAL
        }

        primaryActionButton = Button(this).apply {
            textSize = 18f
            visibility = View.GONE
            isFocusable = true
            setOnClickListener { sendServiceAction(SweetSpotService.ACTION_CALIBRATION_CONTINUE) }
        }

        val cancel = Button(this).apply {
            text = "Cancel"
            textSize = 16f
            setOnClickListener { sendServiceAction(SweetSpotService.ACTION_CALIBRATION_CANCEL) }
        }

        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(hint, LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f,
            ))
            addView(primaryActionButton, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                setMargins((8f * density).roundToInt(), 0, 0, 0)
            })
            addView(cancel, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                setMargins((8f * density).roundToInt(), 0, 0, 0)
            })
        }
        root.addView(footer, fullWidthParams())
        return root
    }

    private fun fullWidthParams(bottom: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            if (bottom > 0) setMargins(0, 0, 0, (bottom * resources.displayMetrics.density).roundToInt())
        }

    private fun addWeightedPanel(
        parent: LinearLayout,
        child: View,
        weight: Float,
        right: Int = 0,
        bottom: Int = 0,
    ) {
        val isWide = parent.orientation == LinearLayout.HORIZONTAL
        parent.addView(child, LinearLayout.LayoutParams(
            if (isWide) 0 else LinearLayout.LayoutParams.MATCH_PARENT,
            if (isWide) LinearLayout.LayoutParams.MATCH_PARENT else 0,
            weight,
        ).apply {
            val density = resources.displayMetrics.density
            setMargins(0, 0, (right * density).roundToInt(), (bottom * density).roundToInt())
        })
    }

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
