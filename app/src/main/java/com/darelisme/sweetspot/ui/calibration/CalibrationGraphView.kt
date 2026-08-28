package com.darelisme.sweetspot

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import kotlin.math.log10
import kotlin.math.max

class CalibrationGraphView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private companion object {
        const val MIN_FREQUENCY_HZ = 20.0
        const val FREQUENCY_LOG_SPAN = 3.0
        const val MIN_MAGNITUDE_DB = -18.0
        const val MAX_MAGNITUDE_DB = 18.0
        const val BACKGROUND_COLOR = 0xFF111214.toInt()
        const val GRID_COLOR = 0x553C424A
        const val AXIS_COLOR = 0xFF8E969F.toInt()
        const val TEXT_COLOR = 0xFFD4D9DE.toInt()
        const val LEFT_TRACE_COLOR = 0xFF4DD9FF.toInt()
        const val RIGHT_TRACE_COLOR = 0xFFFFB454.toInt()

        val FREQUENCY_LABELS = arrayOf(
            FrequencyLabel(20.0, "20 Hz"),
            FrequencyLabel(100.0, "100 Hz"),
            FrequencyLabel(1_000.0, "1 kHz"),
            FrequencyLabel(10_000.0, "10 kHz"),
            FrequencyLabel(20_000.0, "20 kHz")
        )
    }

    private data class FrequencyLabel(val frequencyHz: Double, val text: String)

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = GRID_COLOR
        style = Paint.Style.STROKE
    }
    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AXIS_COLOR
        style = Paint.Style.STROKE
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = TEXT_COLOR
        style = Paint.Style.FILL
    }
    private val leftTracePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = LEFT_TRACE_COLOR
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val rightTracePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = RIGHT_TRACE_COLOR
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val tracePath = Path()
    private val density = resources.displayMetrics.density
    private val scaledDensity = density * resources.configuration.fontScale
    private var response: MeasurementResponse? = null

    init {
        isFocusable = false
        contentDescription = "Calibration frequency response graph"
    }

    internal fun setMeasurementResponse(value: MeasurementResponse) {
        if (response == value) return
        response = value
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(BACKGROUND_COLOR)

        val plotLeft = 52f * density
        val plotRight = width.toFloat() - 24f * density
        val plotTop = 12f * density
        val plotBottom = height.toFloat() - 32f * density
        if (plotRight <= plotLeft || plotBottom <= plotTop) return

        gridPaint.strokeWidth = max(1f, density)
        axisPaint.strokeWidth = max(1f, density)
        leftTracePaint.strokeWidth = max(2f, 2f * density)
        rightTracePaint.strokeWidth = max(2f, 2f * density)
        textPaint.textSize = 13f * scaledDensity

        FREQUENCY_LABELS.forEach { label ->
            val x = xForFrequency(label.frequencyHz, plotLeft, plotRight)
            canvas.drawLine(x, plotTop, x, plotBottom, gridPaint)
        }
        canvas.drawLine(plotLeft, plotTop, plotLeft, plotBottom, axisPaint)
        canvas.drawLine(plotLeft, plotTop, plotRight, plotTop, axisPaint)
        canvas.drawLine(plotLeft, plotBottom, plotRight, plotBottom, axisPaint)
        val zeroDbY = yForMagnitude(0.0, plotTop, plotBottom)
        canvas.drawLine(plotLeft, zeroDbY, plotRight, zeroDbY, gridPaint)

        textPaint.textAlign = Paint.Align.LEFT
        textPaint.color = TEXT_COLOR
        canvas.drawText("+18 dB", plotLeft, plotTop + textPaint.textSize, textPaint)
        canvas.drawText("0 dB", plotLeft, zeroDbY + textPaint.textSize / 2f, textPaint)
        canvas.drawText("-18 dB", plotLeft, plotBottom, textPaint)
        drawFrequencyLabels(canvas, plotLeft, plotRight, plotBottom)
        drawLegend(canvas, plotRight, plotTop)

        val currentResponse = response
        if (currentResponse == null || (currentResponse.left == null && currentResponse.right == null)) {
            textPaint.textAlign = Paint.Align.CENTER
            canvas.drawText(
                "Waiting for measurement data",
                (plotLeft + plotRight) / 2f,
                (plotTop + plotBottom) / 2f,
                textPaint
            )
            return
        }

        currentResponse.left?.let { trace ->
            drawTrace(canvas, trace, leftTracePaint, plotLeft, plotRight, plotTop, plotBottom)
        }
        currentResponse.right?.let { trace ->
            drawTrace(canvas, trace, rightTracePaint, plotLeft, plotRight, plotTop, plotBottom)
        }
    }

    private fun drawFrequencyLabels(canvas: Canvas, plotLeft: Float, plotRight: Float, plotBottom: Float) {
        val baseline = plotBottom + 24f * density
        FREQUENCY_LABELS.forEachIndexed { index, label ->
            textPaint.textAlign = when (index) {
                0 -> Paint.Align.LEFT
                FREQUENCY_LABELS.lastIndex -> Paint.Align.RIGHT
                else -> Paint.Align.CENTER
            }
            canvas.drawText(
                label.text,
                xForFrequency(label.frequencyHz, plotLeft, plotRight),
                baseline,
                textPaint
            )
        }
    }

    private fun drawLegend(canvas: Canvas, plotRight: Float, plotTop: Float) {
        val baseline = plotTop + 16f * density
        textPaint.textAlign = Paint.Align.RIGHT
        textPaint.color = LEFT_TRACE_COLOR
        canvas.drawText("L", plotRight - 28f * density, baseline, textPaint)
        textPaint.color = RIGHT_TRACE_COLOR
        canvas.drawText("R", plotRight - 8f * density, baseline, textPaint)
        textPaint.color = TEXT_COLOR
    }

    private fun drawTrace(
        canvas: Canvas,
        trace: MeasurementTrace,
        paint: Paint,
        plotLeft: Float,
        plotRight: Float,
        plotTop: Float,
        plotBottom: Float
    ) {
        tracePath.reset()
        trace.frequenciesHz.forEachIndexed { index, frequencyHz ->
            val x = xForFrequency(frequencyHz, plotLeft, plotRight)
            val y = yForMagnitude(trace.magnitudesDb[index], plotTop, plotBottom)
            if (index == 0) tracePath.moveTo(x, y) else tracePath.lineTo(x, y)
        }
        canvas.drawPath(tracePath, paint)
    }

    private fun xForFrequency(frequencyHz: Double, plotLeft: Float, plotRight: Float): Float {
        val normalized = (log10(frequencyHz / MIN_FREQUENCY_HZ) / FREQUENCY_LOG_SPAN)
            .coerceIn(0.0, 1.0)
        return plotLeft + (plotRight - plotLeft) * normalized.toFloat()
    }

    private fun yForMagnitude(magnitudeDb: Double, plotTop: Float, plotBottom: Float): Float {
        val normalized = ((MAX_MAGNITUDE_DB - magnitudeDb) /
            (MAX_MAGNITUDE_DB - MIN_MAGNITUDE_DB)).coerceIn(0.0, 1.0)
        return plotTop + (plotBottom - plotTop) * normalized.toFloat()
    }
}
