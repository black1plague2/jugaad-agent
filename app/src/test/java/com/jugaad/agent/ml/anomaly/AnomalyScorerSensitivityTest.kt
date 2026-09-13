package com.jugaad.agent.ml.anomaly

import com.jugaad.agent.core.Constants
import com.jugaad.agent.core.config.AppConfig
import com.jugaad.agent.domain.model.MachineStatus
import com.jugaad.agent.domain.model.Sensitivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Sensitivity must act in exactly one place: the t1/t2 thresholds (and calibration).
 * It must NOT scale AnomalyScorer's spreadFloor/zFloor — those stay at their global,
 * sensitivity-independent values. Otherwise sensitivity is applied twice (thresholds
 * fall AND the score denominator shrinks), and a resting/healthy object with a
 * noise-sized baseline spread reads as a false CRITICAL under a high sensitivity.
 * See the v16 plan and its Result section (hardware-verified defect fix).
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
    fun sameCosineDistanceAndBaselineGivesSameScoreRegardlessOfSensitivity() {
        // A very tight healthy cluster: its true radius is far below the global floor,
        // so the floor (not the data) sets spread here, for every sensitivity profile.
        val base = listOf(vec(5, 0.001f), vec(5, 0.001f), vec(5, 0.001f))

        // Sensitivity must never reach buildBaseline's floor argument: every caller passes
        // the same global AnomalyScorer.SPREAD_FLOOR_BASE regardless of the asset's profile.
        val standardStats = scorer.buildBaseline(base, AnomalyScorer.SPREAD_FLOOR_BASE)
        val veryHighStats = scorer.buildBaseline(base, AnomalyScorer.SPREAD_FLOOR_BASE)

        assertEquals(standardStats.spread, veryHighStats.spread, 1e-12)

        val diagnosisFeature = vec(5, 0.02f)

        // Same score under both Standard and Very high thresholds - only the status
        // boundaries differ, never the score itself.
        val standardResult = scorer.score(diagnosisFeature, standardStats, Thresholds.forSensitivity(Sensitivity.STANDARD))
        val veryHighResult = scorer.score(diagnosisFeature, veryHighStats, Thresholds.forSensitivity(Sensitivity.VERY_HIGH))

        assertEquals(
            "expected the score to be sensitivity-independent",
            standardResult.score, veryHighResult.score, 1e-12,
        )
        assertEquals(standardResult.acousticScore, veryHighResult.acousticScore, 1e-12)
    }

    /**
     * Reproduces the hardware-verified defect: same phone, lying still, same reference
     * measurement. Before the fix, VERY_HIGH scaled both the zFloor (via a per-call config
     * copy in DiagnoseUseCase) and the thresholds, so a resting object's noise-sized gyro
     * std produced `score=19.27` (CRITICAL) instead of the true ~1.13. With sensitivity
     * touching only thresholds, the score is identical under every profile and only the
     * status bucketing (driven by [Thresholds]) differs.
     */
    @Test
    fun restingObjectScoresHealthyUnderStandardAndDoesNotExplodeUnderVeryHigh() {
        val mean = vec(7)
        // A 3-clip baseline whose gyro std is noise-sized, as measured on-device (phone A).
        val baseline = AnomalyScorer.BaselineStats(
            mean = mean,
            spread = 0.005,
            rawStd = 0.0,
            clipCount = 3,
            imuIndexStd = 0.061,
            gyroIndexStd = 0.00976,
            magIndexStd = 0.050,
            magRmsStd = 0.00318,
        )
        // Identical acoustic feature (no acoustic drift) with a gyro delta sized so the
        // sensor path alone drives the score to ~1.13 through the GLOBAL (unscaled) zFloor.
        val diagnosisFeature = mean.copyOf()
        val sensorDeltas = doubleArrayOf(0.0, 0.0565, 0.0, 0.0) // accel, gyro, mag, magRms

        // The shared, sensitivity-independent config — no per-asset scaled copy.
        val cfg = AppConfig()

        val standardResult = scorer.score(
            diagnosisFeature, baseline, Thresholds.forSensitivity(Sensitivity.STANDARD), sensorDeltas, cfg,
        )
        val veryHighResult = scorer.score(
            diagnosisFeature, baseline, Thresholds.forSensitivity(Sensitivity.VERY_HIGH), sensorDeltas, cfg,
        )

        assertEquals(
            "score must not depend on sensitivity",
            standardResult.score, veryHighResult.score, 1e-9,
        )
        assertTrue(
            "expected the reproduced score near 1.13, was ${standardResult.score}",
            standardResult.score in 1.0..1.3,
        )
        assertEquals(MachineStatus.HEALTHY, standardResult.status)
        assertTrue(
            "the bug produced score=19.27 (~17x); the fixed score must stay far below that",
            veryHighResult.score < 2.0,
        )
    }
}
