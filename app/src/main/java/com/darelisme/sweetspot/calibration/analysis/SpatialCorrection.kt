package com.darelisme.sweetspot.calibration

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

sealed interface OptimizationResult {
    data class Valid(val solution: CalibrationSolution) : OptimizationResult
    data class Insufficient(val confidence: CalibrationConfidence) : OptimizationResult
}

class SpatialCorrection {
    fun optimize(
        positions: List<CompletePosition>,
        mode: CorrectionMode = CorrectionMode.NORMAL,
    ): OptimizationResult {
        require(positions.map(CompletePosition::position).distinct().size == positions.size)
        val aggregate = SpatialAggregator.aggregate(positions)
        val confidence = SpatialConfidence.calculate(aggregate.responsesByBand, positions.size)
        val grade = confidence.grade ?: return OptimizationResult.Insufficient(confidence)
        val blocked = BooleanArray(CalibrationBandGrid.BAND_COUNT)
        val correction = FloatArray(CalibrationBandGrid.BAND_COUNT) { band ->
            val medianResponse = aggregate.medianResponseDb[band]
            val bandConfidence = confidence.bands[band]
            val requested = -medianResponse
            val deepNull = requested > 0f && medianResponse <= DEEP_NULL_DB
            val outsideRestrictedBand = mode == CorrectionMode.RESTRICTED_BAND &&
                bandConfidence.frequencyHz > RESTRICTED_MAX_HZ
            blocked[band] = deepNull || outsideRestrictedBand || !bandConfidence.usable
            val nullSafe = if (deepNull) 0f else requested
            CorrectionLimiter.limitBand(
                requestedDb = nullSafe,
                band = band,
                confidence = bandConfidence,
                mode = mode,
            )
        }
        val smoothed = CorrectionLimiter.smooth(correction, confidence).also { values ->
            blocked.forEachIndexed { band, isBlocked ->
                if (isBlocked) values[band] = 0f
            }
        }
        val source = positions.sortedBy { it.position.ordinal }
        val id = SolutionId(
            buildString {
                append("solution-")
                append(mode.name.lowercase())
                append('-')
                append(source.joinToString("-") { it.position.name.lowercase() })
                append('-')
                append((confidence.score * 10_000).roundToInt())
            },
        )
        return OptimizationResult.Valid(
            CalibrationSolution.fromCompletePositions(
                id = id,
                positions = source,
                correctionDb = BandCurve.of(smoothed),
                confidence = confidence,
                score = confidence.score,
                correctionMode = mode,
            ),
        )
    }

    private companion object {
        const val DEEP_NULL_DB = -12f
        const val RESTRICTED_MAX_HZ = 500f
    }
}

data class SpatialAggregate(
    val medianResponseDb: FloatArray,
    val responsesByBand: List<FloatArray>,
)

object SpatialAggregator {
    fun aggregate(positions: List<CompletePosition>): SpatialAggregate {
        require(positions.isNotEmpty())
        val responsesByBand = List(CalibrationBandGrid.BAND_COUNT) { band ->
            FloatArray(positions.size) { positionIndex ->
                val position = positions[positionIndex]
                (position.left.responseDb[band] + position.right.responseDb[band]) / 2f
            }
        }
        return SpatialAggregate(
            medianResponseDb = FloatArray(CalibrationBandGrid.BAND_COUNT) { band ->
                median(responsesByBand[band])
            },
            responsesByBand = responsesByBand,
        )
    }
}

object SpatialConfidence {
    fun calculate(responsesByBand: List<FloatArray>, positionCount: Int): CalibrationConfidence {
        require(responsesByBand.size == CalibrationBandGrid.BAND_COUNT)
        require(positionCount > 0)
        val countConfidence = min(1f, positionCount / 4f)
        val bands = responsesByBand.mapIndexed { band, responses ->
            require(responses.size == positionCount)
            val center = median(responses)
            val spread = median(FloatArray(responses.size) { abs(responses[it] - center) }) * MAD_SCALE
            val agreement = (1f - spread / MAX_TRUSTED_SPREAD_DB).coerceIn(0f, 1f)
            val confidence = countConfidence * agreement
            BandConfidence(
                frequencyHz = CalibrationBandGrid.centerFrequenciesHz[band],
                confidence = confidence,
                spatialSpreadDb = spread,
                usable = confidence >= MIN_USABLE_CONFIDENCE,
            )
        }
        val usableCount = bands.count(BandConfidence::usable)
        val score = bands.map(BandConfidence::confidence).average().toFloat().coerceIn(0f, 1f)
        val grade = when {
            usableCount < MIN_USABLE_BANDS -> null
            positionCount >= 4 && score >= SUFFICIENT_SCORE -> UsabilityGrade.SUFFICIENT
            else -> UsabilityGrade.BOUNDED_USABLE
        }
        return CalibrationConfidence(bands, usableCount, score, grade)
    }

    private const val MAD_SCALE = 1.4826f
    private const val MAX_TRUSTED_SPREAD_DB = 12f
    private const val MIN_USABLE_CONFIDENCE = 0.35f
    private const val MIN_USABLE_BANDS = 16
    private const val SUFFICIENT_SCORE = 0.8f
}

object CorrectionLimiter {
    fun limitBand(
        requestedDb: Float,
        band: Int,
        confidence: BandConfidence,
        mode: CorrectionMode,
    ): Float {
        require(band in 0 until CalibrationBandGrid.BAND_COUNT)
        if (!confidence.usable) return 0f
        if (mode == CorrectionMode.RESTRICTED_BAND && confidence.frequencyHz > RESTRICTED_MAX_HZ) return 0f
        val strength = when (mode) {
            CorrectionMode.NORMAL -> 1f
            CorrectionMode.GENTLE -> 0.5f
            CorrectionMode.RESTRICTED_BAND -> 0.5f
        }
        val maxBoost = when (mode) {
            CorrectionMode.NORMAL -> 6f
            CorrectionMode.GENTLE,
            CorrectionMode.RESTRICTED_BAND -> 3f
        }
        return (requestedDb * confidence.confidence * strength).coerceIn(MAX_CUT_DB, maxBoost)
    }

    fun smooth(values: FloatArray, confidence: CalibrationConfidence): FloatArray {
        require(values.size == CalibrationBandGrid.BAND_COUNT)
        return FloatArray(values.size) { band ->
            if (!confidence.bands[band].usable) {
                0f
            } else {
                val start = (band - 1).coerceAtLeast(0)
                val end = (band + 1).coerceAtMost(values.lastIndex)
                var total = 0f
                for (index in start..end) total += values[index]
                total / (end - start + 1)
            }
        }
    }

    private const val MAX_CUT_DB = -12f
    private const val RESTRICTED_MAX_HZ = 500f
}

object CalibrationSolutionComparator {
    fun prefers(proposed: CalibrationSolution, current: CalibrationSolution): Boolean {
        val grade = proposed.confidence.grade.rank().compareTo(current.confidence.grade.rank())
        if (grade != 0) return grade > 0
        val usableBands = proposed.confidence.usableBandCount.compareTo(current.confidence.usableBandCount)
        if (usableBands != 0) return usableBands > 0
        val score = proposed.score.compareTo(current.score)
        if (score != 0) return score > 0
        val sourceCount = proposed.sourcePositions.size.compareTo(current.sourcePositions.size)
        if (sourceCount != 0) return sourceCount > 0
        return proposed.id.value > current.id.value
    }

    private fun UsabilityGrade?.rank(): Int = when (this) {
        null -> 0
        UsabilityGrade.BOUNDED_USABLE -> 1
        UsabilityGrade.SUFFICIENT -> 2
    }
}

internal fun median(values: FloatArray): Float {
    require(values.isNotEmpty())
    val sorted = values.sortedArray()
    val middle = sorted.size / 2
    return if (sorted.size % 2 == 0) {
        (sorted[middle - 1] + sorted[middle]) / 2f
    } else {
        sorted[middle]
    }
}
