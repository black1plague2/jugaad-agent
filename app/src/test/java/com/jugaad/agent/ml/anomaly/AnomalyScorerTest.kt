package com.jugaad.agent.ml.anomaly

import com.jugaad.agent.core.Constants
import com.jugaad.agent.domain.model.MachineStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class AnomalyScorerTest {

    private val scorer = AnomalyScorer()

    private fun vec(seed: Long, jitter: Float = 0f): FloatArray {
        val r = Random(seed)
        return FloatArray(Constants.FEATURE_DIM) { i ->
            // deterministic "healthy" shape + optional perturbation
            (kotlin.math.sin(i * 0.13).toFloat() * 3f) + (r.nextFloat() - 0.5f) * jitter
        }
    }

    @Test
    fun healthyClipScoresNearZero() {
        val base = listOf(vec(1), vec(1), vec(1))
        val stats = scorer.buildBaseline(base)
        val r = scorer.score(vec(1), stats)
        assertTrue("score ${r.score} should be ~0", r.score < 0.5)
        assertEquals(MachineStatus.HEALTHY, r.status)
    }

    @Test
    fun spreadIsFlooredNotZero() {
        val stats = scorer.buildBaseline(listOf(vec(2), vec(2), vec(2)))
        assertTrue(stats.spread >= 0.02 - 1e-9)
    }

    @Test
    fun largeDeviationTripsCritical() {
        val base = listOf(vec(3, 0.02f), vec(3, 0.02f), vec(3, 0.02f))
        val stats = scorer.buildBaseline(base)

        val drifted = vec(3).copyOf().also { v ->
            for (i in 0 until Constants.N_MELS) v[i] += 6f   // big low-band energy jump
        }
        val r = scorer.score(drifted, stats, Thresholds.DEFAULT)
        assertTrue("expected elevated score, got ${r.score}", r.score > Thresholds.DEFAULT.t1)
        assertTrue(r.status != MachineStatus.HEALTHY)
        assertTrue("dominant divergence Hz should be set", r.dominantDivergenceHz > 0.0)
    }

    @Test
    fun thresholdsBucketMonotonically() {
        assertEquals(MachineStatus.HEALTHY, MachineStatus.fromScore(1.0, 2.0, 4.0))
        assertEquals(MachineStatus.WARNING, MachineStatus.fromScore(3.0, 2.0, 4.0))
        assertEquals(MachineStatus.CRITICAL, MachineStatus.fromScore(9.0, 2.0, 4.0))
    }
}
