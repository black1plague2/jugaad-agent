package com.jugaad.agent.ml.anomaly

import com.jugaad.agent.core.Constants
import com.jugaad.agent.domain.model.Sensitivity
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The floors that stop a tight healthy cluster's radius from collapsing the score
 * (spreadFloor for the acoustic path, zFloor for the sensor path) must scale with the
 * asset's [Sensitivity], not stay at the global Standard value - otherwise a light
 * object's genuinely small healthy spread is masked by the floor instead of the data.
 * See the v16 plan.
 */
class AnomalyScorerSensitivityTest {

    private val scorer = AnomalyScorer()

    private fun vec(seed: Long, jitter: Float = 0f): FloatArray {
        val r = Random(seed)
        return FloatArray(Constants.FEATURE_DIM) { i ->
            (kotlin.math.sin(i * 0.13).toFloat() * 3f) + (r.nextFloat() - 0.5f) * jitter
        }
    }

    @Test
    fun tinyHealthySpreadScoresHigherUnderVeryHighThanStandard() {
        // A very tight healthy cluster: its true radius is far below the Standard floor.
        val base = listOf(vec(5, 0.001f), vec(5, 0.001f), vec(5, 0.001f))

        val standardFloor = AnomalyScorer.SPREAD_FLOOR_BASE * Sensitivity.STANDARD.factor // 0.02
        val veryHighFloor = AnomalyScorer.SPREAD_FLOOR_BASE * Sensitivity.VERY_HIGH.factor // 0.005

        val standardStats = scorer.buildBaseline(base, standardFloor)
        val veryHighStats = scorer.buildBaseline(base, veryHighFloor)

        // Same small deviation scored against both baselines.
        val diagnosisFeature = vec(5, 0.02f)

        val standardResult = scorer.score(diagnosisFeature, standardStats, Thresholds.DEFAULT)
        val veryHighResult = scorer.score(diagnosisFeature, veryHighStats, Thresholds.DEFAULT)

        assertTrue(
            "expected Very high floor to be smaller: standard=${standardStats.spread} veryHigh=${veryHighStats.spread}",
            veryHighStats.spread < standardStats.spread,
        )
        assertTrue(
            "expected Very high score (${veryHighResult.score}) to exceed Standard score (${standardResult.score})",
            veryHighResult.score > standardResult.score,
        )
    }
}
