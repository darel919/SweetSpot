package com.darelisme.sweetspot.calibration.analysis

import com.darelisme.sweetspot.calibration.CalibrationTestFixtures
import com.darelisme.sweetspot.calibration.model.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpatialCorrectionTest {
    private val correction = SpatialCorrection()

    @Test
    fun boundedUsableProducesAConservativeCorrection() {
        val positions = mandatoryPositions { FloatArray(CalibrationBandGrid.BAND_COUNT) { 4f } }

        val solution = (correction.optimize(positions) as OptimizationResult.Valid).solution

        assertEquals(UsabilityGrade.BOUNDED_USABLE, solution.confidence.grade)
        assertTrue(solution.correctionDb.toFloatArray().all { it in -3.01f..-2.99f })
    }

    @Test
    fun unstableNarrowBandIsZeroedWithoutInvalidatingStableBands() {
        val positions = mandatoryPositions { position ->
            FloatArray(CalibrationBandGrid.BAND_COUNT) { band ->
                if (band == 50) when (position) {
                    CalibrationPosition.CENTER -> -20f
                    CalibrationPosition.LEFT -> 0f
                    else -> 20f
                } else 4f
            }
        }

        val solution = (correction.optimize(positions) as OptimizationResult.Valid).solution

        assertEquals(0f, solution.correctionDb[50], 0f)
        assertTrue(solution.correctionDb[10] < 0f)
    }

    @Test
    fun deepNullDoesNotReceiveAggressiveBoost() {
        val positions = mandatoryPositions {
            FloatArray(CalibrationBandGrid.BAND_COUNT) { band -> if (band == 10) -20f else 4f }
        }

        val solution = (correction.optimize(positions) as OptimizationResult.Valid).solution

        assertEquals(0f, solution.correctionDb[10], 0f)
        assertTrue(solution.correctionDb[20] < 0f)
    }

    @Test
    fun globallyIncoherentRoomIsInsufficient() {
        val positions = mandatoryPositions { position ->
            val value = when (position) {
                CalibrationPosition.CENTER -> -20f
                CalibrationPosition.LEFT -> 0f
                else -> 20f
            }
            FloatArray(CalibrationBandGrid.BAND_COUNT) { value }
        }

        val result = correction.optimize(positions)

        assertTrue(result is OptimizationResult.Insufficient)
    }

    @Test
    fun oneExtremeOptionalOutlierDoesNotMoveTheRobustAggregate() {
        val positions = CalibrationPosition.entries.map { position ->
            val value = if (position == CalibrationPosition.BACKWARD) 30f else 2f
            CalibrationTestFixtures.complete(
                position,
                FloatArray(CalibrationBandGrid.BAND_COUNT) { value },
            )
        }

        val solution = (correction.optimize(positions) as OptimizationResult.Valid).solution

        assertEquals(UsabilityGrade.SUFFICIENT, solution.confidence.grade)
        assertTrue(solution.correctionDb.toFloatArray().all { it < 0f })
    }

    @Test
    fun comparatorKeepsCurrentSolutionWhenProposalScoresWorse() {
        val positions = mandatoryPositions { FloatArray(CalibrationBandGrid.BAND_COUNT) }
        val current = solution("current", positions, usableBands = 64, score = 0.75f)
        val proposed = solution("proposed", positions, usableBands = 63, score = 0.99f)

        assertTrue(!CalibrationSolutionComparator.prefers(proposed, current))
    }

    @Test
    fun restrictedModeCorrectsOnlyLowerFrequencies() {
        val positions = mandatoryPositions { FloatArray(CalibrationBandGrid.BAND_COUNT) { 4f } }

        val solution = (correction.optimize(positions, CorrectionMode.RESTRICTED_BAND) as OptimizationResult.Valid).solution

        solution.confidence.bands.forEachIndexed { index, band ->
            if (band.frequencyHz > 500f) assertEquals(0f, solution.correctionDb[index], 0f)
        }
    }

    private fun mandatoryPositions(response: (CalibrationPosition) -> FloatArray): List<CompletePosition> =
        PositionLedger.MANDATORY_POSITIONS.sortedBy { it.ordinal }.map { position ->
            CalibrationTestFixtures.complete(position, response(position))
        }

    private fun solution(
        id: String,
        positions: List<CompletePosition>,
        usableBands: Int,
        score: Float,
    ): CalibrationSolution {
        val bands = List(CalibrationBandGrid.BAND_COUNT) { index ->
            BandConfidence(
                CalibrationBandGrid.centerFrequenciesHz[index],
                confidence = if (index < usableBands) score else 0f,
                spatialSpreadDb = 0f,
                usable = index < usableBands,
            )
        }
        val confidence = CalibrationConfidence(
            bands,
            usableBands,
            score,
            UsabilityGrade.BOUNDED_USABLE,
        )
        return CalibrationSolution.fromCompletePositions(
            SolutionId(id),
            positions,
            BandCurve.of(FloatArray(CalibrationBandGrid.BAND_COUNT)),
            confidence,
            score,
            CorrectionMode.NORMAL,
        )
    }
}
