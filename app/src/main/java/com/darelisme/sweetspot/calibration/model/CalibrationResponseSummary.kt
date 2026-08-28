package com.darelisme.sweetspot

import java.util.Locale

internal data class MeasurementTraceSummary(
    val pointCount: Int,
    val firstFrequencyHz: Double,
    val lastFrequencyHz: Double,
    val minimumDb: Double,
    val maximumDb: Double,
) {
    init {
        require(pointCount >= 2)
        require(firstFrequencyHz.isFinite() && firstFrequencyHz > 0.0)
        require(lastFrequencyHz.isFinite() && lastFrequencyHz >= firstFrequencyHz)
        require(minimumDb.isFinite() && maximumDb.isFinite())
        require(minimumDb <= maximumDb)
    }
}

internal data class MeasurementResponseSummary(
    val current: Int,
    val total: Int,
    val left: MeasurementTraceSummary?,
    val right: MeasurementTraceSummary?,
) {
    init {
        require(total > 0 && current in 0..total)
        require(left != null || right != null)
    }

    fun displayText(): String = buildString {
        append("Phone data received. Take $current of $total.")
        append('\n')
        append("L: ")
        append(left?.displayText() ?: "not included")
        append('\n')
        append("R: ")
        append(right?.displayText() ?: "not included")
    }

    private fun MeasurementTraceSummary.displayText(): String =
        "$pointCount points, ${formatFrequency(firstFrequencyHz)} to " +
            "${formatFrequency(lastFrequencyHz)}, ${formatDb(minimumDb)} to ${formatDb(maximumDb)}"

    private fun formatFrequency(valueHz: Double): String {
        if (valueHz < 1_000.0) return String.format(Locale.US, "%.0f Hz", valueHz)
        val valueKHz = String.format(Locale.US, "%.1f", valueHz / 1_000.0).removeSuffix(".0")
        return "$valueKHz kHz"
    }

    private fun formatDb(valueDb: Double): String = String.format(Locale.US, "%+.1f dB", valueDb)

    companion object {
        fun from(response: MeasurementResponse): MeasurementResponseSummary =
            MeasurementResponseSummary(
                current = response.current,
                total = response.total,
                left = response.left?.toSummary(),
                right = response.right?.toSummary(),
            )

        private fun MeasurementTrace.toSummary(): MeasurementTraceSummary =
            MeasurementTraceSummary(
                pointCount = frequenciesHz.size,
                firstFrequencyHz = frequenciesHz.first(),
                lastFrequencyHz = frequenciesHz.last(),
                minimumDb = magnitudesDb.minOrNull() ?: error("Measurement trace has no magnitudes"),
                maximumDb = magnitudesDb.maxOrNull() ?: error("Measurement trace has no magnitudes"),
            )
    }
}
