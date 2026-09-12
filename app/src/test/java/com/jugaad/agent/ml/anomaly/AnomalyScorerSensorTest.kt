package com.jugaad.agent.ml.anomaly

import com.jugaad.agent.core.Constants
import com.jugaad.agent.core.config.AppConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class AnomalyScorerSensorTest {

    private val scorer = AnomalyScorer()

    private fun vec(seed: Long, jitter: Float = 0f): FloatArray {
        val r = Random(seed)
        return FloatArray(Constants.FEATURE_DIM) { i ->
            (kotlin.math.sin(i * 0.13).toFloat() * 3f) + (r.nextFloat() - 0.5f) * jitter
        }
    }

    @Test
    fun acousticOnlyOverloadUnchangedWithNoSensorContribution() {
        val base = listOf(vec(3, 0.02f), vec(3, 0.02f), vec(3, 0.02f))
        val stats = scorer.buildBaseline(base)
        val drifted = vec(3).copyOf().also { v -> for (i in 0 until Constants.N_MELS) v[i] += 6f }

        val r = scorer.score(drifted, stats, Thresholds.DEFAULT)

        assertTrue("expected elevated acoustic score, got ${r.acousticScore}", r.acousticScore > Thresholds.DEFAULT.t1)
        assertEquals(0.0, r.sensorScore, 1e-9)
        assertEquals(r.acousticScore, r.score, 1e-9)
        assertEquals("acoustic", r.dominantSource)
    }

    @Test
    fun largeGyroDeltaWithSmallStdDrivesScoreAndIsDominant() {
        val base = listOf(vec(2), vec(2), vec(2))
        val acousticStats = scorer.buildBaseline(base)
        // Tiny healthy gyro std, everything else at the default (0.0 -> zFloor).
        val stats = acousticStats.copy(gyroIndexStd = 0.01)

        val sensorDeltas = doubleArrayOf(0.0, 5.0, 0.0, 0.0) // accel, gyro, mag, magRms
        val cfg = AppConfig()
        val r = scorer.score(vec(2), stats, Thresholds.DEFAULT, sensorDeltas, cfg)

        val expectedZ = 5.0 / maxOf(0.01, cfg.sensors.zFloor)
        val expectedSensorScore = cfg.sensors.sensorScoreScale * cfg.sensors.zWeights.gyro * expectedZ

        assertEquals(0.0, r.acousticScore, 1e-9) // diagnosis feature == baseline mean exactly
        assertEquals(expectedSensorScore, r.sensorScore, 1e-6)
        assertEquals("gyro", r.dominantSource)
        assertEquals(r.sensorScore, r.score, 1e-9)
    }

    @Test
    fun zeroStdFallsBackToZFloorInsteadOfDividingByZero() {
        val base = listOf(vec(4), vec(4), vec(4))
        val stats = scorer.buildBaseline(base) // all sensor stds default to 0.0

        val sensorDeltas = doubleArrayOf(0.4, 0.0, 0.0, 0.0)
        val cfg = AppConfig()
        val r = scorer.score(vec(4), stats, Thresholds.DEFAULT, sensorDeltas, cfg)

        val expectedZ = 0.4 / cfg.sensors.zFloor
        val expectedSensorScore = cfg.sensors.sensorScoreScale * cfg.sensors.zWeights.accel * expectedZ

        assertEquals(expectedSensorScore, r.sensorScore, 1e-6)
        assertEquals("accel", r.dominantSource)
    }
}
