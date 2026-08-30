package com.darelisme.sweetspot.calibration.analysis

import com.darelisme.sweetspot.calibration.model.BandCurve
import com.darelisme.sweetspot.calibration.model.CalibrationBandGrid
import com.darelisme.sweetspot.calibration.model.CalibrationJob
import com.darelisme.sweetspot.calibration.model.CalibrationPosition
import com.darelisme.sweetspot.calibration.model.ValidationOutcome
import kotlin.math.abs
import kotlin.math.log10

internal object ValidationScorer {
    private const val TOLERANCE_DB = 0.5f

    fun classify(job: CalibrationJob, analysis: CalibrationAnalysis): ValidationScore {
        val center = job.ledger.complete(CalibrationPosition.CENTER)
            ?: return ValidationScore(ValidationOutcome.INCONCLUSIVE_CAPTURE, null, null)
        val baseline = FloatArray(CalibrationBandGrid.BAND_COUNT) { band ->
            (center.left.responseDb[band] + center.right.responseDb[band]) / 2f
        }
        val response = when {
            analysis.leftResponse.isNotEmpty() && analysis.rightResponse.isNotEmpty() -> {
                val left = analysis.leftResponse.toBandCurve().toFloatArray()
                val right = analysis.rightResponse.toBandCurve().toFloatArray()
                FloatArray(left.size) { (left[it] + right[it]) / 2f }
            }
            analysis.leftResponse.isNotEmpty() -> analysis.leftResponse.toBandCurve().toFloatArray()
            analysis.rightResponse.isNotEmpty() -> analysis.rightResponse.toBandCurve().toFloatArray()
            else -> return ValidationScore(ValidationOutcome.INCONCLUSIVE_CAPTURE, null, null)
        }
        val before = baseline.map(::abs).average().toFloat()
        val after = response.map(::abs).average().toFloat()
        val outcome = when {
            before - after > TOLERANCE_DB -> ValidationOutcome.IMPROVED
            after - before > TOLERANCE_DB -> ValidationOutcome.WORSE
            else -> ValidationOutcome.NEUTRAL
        }
        return ValidationScore(outcome, before, after)
    }
}

internal data class ValidationScore(
    val outcome: ValidationOutcome,
    val beforeDb: Float?,
    val afterDb: Float?,
)

internal fun List<ResponsePoint>.toBandCurve(): BandCurve {
    require(isNotEmpty())
    val ordered = sortedBy(ResponsePoint::frequencyHz)
    return BandCurve.of(FloatArray(CalibrationBandGrid.BAND_COUNT) { band ->
        val frequency = CalibrationBandGrid.centerFrequenciesHz[band]
        val exact = ordered.firstOrNull { it.frequencyHz >= frequency }
        when {
            exact == null -> ordered.last().magnitudeDb
            exact === ordered.first() -> exact.magnitudeDb
            else -> {
                val upper = ordered.indexOf(exact)
                val lower = upper - 1
                val low = ordered[lower]
                val high = ordered[upper]
                val fraction = ((log10(frequency) - log10(low.frequencyHz)) /
                    (log10(high.frequencyHz) - log10(low.frequencyHz))).coerceIn(0f, 1f)
                low.magnitudeDb + (high.magnitudeDb - low.magnitudeDb) * fraction
            }
        }
    })
}
