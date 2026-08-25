package com.darelisme.sweetspot

import android.content.Context
import android.graphics.drawable.PictureDrawable
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.caverock.androidsvg.SVG
import java.io.InputStream
import kotlin.math.roundToInt

internal enum class CalibrationGuideState {
    READY,
    MEASURING,
}

internal object CalibrationPositionAssets {
    fun pathFor(positionId: String): String? = when (positionId) {
        "center" -> "calibration_position/center.svg"
        "left" -> "calibration_position/left.svg"
        "right" -> "calibration_position/right.svg"
        "forward" -> "calibration_position/forward.svg"
        "backward" -> "calibration_position/backward.svg"
        else -> null
    }

    fun <T> loadOrNull(positionId: String, loader: (String) -> T): T? =
        pathFor(positionId)?.let { path -> runCatching { loader(path) }.getOrNull() }
}

internal class CalibrationPositionGuideView(context: Context) : LinearLayout(context) {
    private val progressView = TextView(context)
    private val artworkView = ImageView(context)
    private val titleView = TextView(context)
    private val instructionView = TextView(context)
    private val artworkCache = mutableMapOf<String, PictureDrawable?>()
    private val density = resources.displayMetrics.density

    init {
        orientation = VERTICAL
        gravity = android.view.Gravity.CENTER_HORIZONTAL
        setPadding(dp(14), dp(10), dp(14), dp(10))
        setBackgroundColor(0xFF17191C.toInt())

        progressView.apply {
            textSize = 15f
            setTextColor(0xFFB8B8BC.toInt())
            gravity = android.view.Gravity.CENTER
        }
        addView(progressView, wrapParams(bottom = 4))

        artworkView.apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(0xFFFFFFFF.toInt())
            setPadding(dp(6), dp(6), dp(6), dp(6))
            setLayerType(View.LAYER_TYPE_SOFTWARE, null)
            contentDescription = "Calibration position diagram"
        }
        addView(artworkView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f,
        ).apply {
            setMargins(0, 0, 0, dp(6))
        })

        titleView.apply {
            textSize = 22f
            setTextColor(0xFFFFFFFF.toInt())
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = android.view.Gravity.CENTER
        }
        addView(titleView, wrapParams(bottom = 5))

        instructionView.apply {
            textSize = 16f
            setTextColor(0xFFE8E8EA.toInt())
            gravity = android.view.Gravity.CENTER
            setLineSpacing(0f, 1.08f)
        }
        addView(instructionView, wrapParams())

        showEmpty()
    }

    fun showEmpty() {
        progressView.text = ""
        titleView.text = "POSITION GUIDE"
        instructionView.text = "Waiting for the next measurement position."
        artworkView.setImageDrawable(null)
        artworkView.visibility = View.GONE
    }

    fun show(measurementContext: MeasurementContext, state: CalibrationGuideState) {
        progressView.text = measurementContext.label()
        titleView.text = when {
            measurementContext.phase == "validation" && measurementContext.attemptIndex > 0 -> "VALIDATION • RETRY • ${measurementContext.positionTitle()}"
            measurementContext.phase == "validation" && state == CalibrationGuideState.MEASURING -> "VALIDATION • MEASURING • ${measurementContext.positionTitle()}"
            measurementContext.phase == "validation" -> "VALIDATION • ${measurementContext.positionTitle()}"
            measurementContext.attemptIndex > 0 -> "RETRY • ${measurementContext.positionTitle()}"
            state == CalibrationGuideState.MEASURING -> "MEASURING • ${measurementContext.positionTitle()}"
            else -> measurementContext.positionTitle()
        }
        instructionView.text = measurementContext.instruction()

        val drawable = if (artworkCache.containsKey(measurementContext.positionId)) {
            artworkCache[measurementContext.positionId]
        } else {
            CalibrationPositionAssets.loadOrNull(measurementContext.positionId) { path ->
                context.assets.open(path).use(::renderSvg)
            }.also { artworkCache[measurementContext.positionId] = it }
        }
        artworkView.setImageDrawable(drawable)
        artworkView.visibility = if (drawable == null) View.GONE else View.VISIBLE
    }

    private fun renderSvg(input: InputStream): PictureDrawable =
        PictureDrawable(SVG.getFromInputStream(input).renderToPicture())

    private fun wrapParams(bottom: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
            if (bottom > 0) setMargins(0, 0, 0, dp(bottom))
        }

    private fun dp(value: Int): Int = (value * density).roundToInt()
}
