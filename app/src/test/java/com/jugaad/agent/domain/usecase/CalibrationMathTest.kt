package com.jugaad.agent.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CalibrationMathTest {

    @Test
    fun healthyOnlyUsesRobustMedianAndMad() {
        val healthy = listOf(1.0, 1.2, 1.1, 0.9, 1.3) // sorted: 0.9,1.0,1.1,1.2,1.3 -> median 1.1
        // |x - 1.1| = 0.2,0.1,0.0,0.1,0.2 -> sorted 0.0,0.1,0.1,0.2,0.2 -> median 0.1, floored below MAD_FLOOR? no, 0.1 > 0.05
        val result = CalibrationMath.compute(healthy, emptyList(), k1 = 3.0, k2 = 6.0, minHealthy = 5)

        assertNotNull(result)
        assertEquals(1.1, result!!.medianHealthy, 1e-9)
        assertEquals(0.1, result.madHealthy, 1e-9)
        assertEquals(1.1 + 3.0 * 0.1, result.t1, 1e-9)
        // T2 is floored at 1.5 x T1 by contract, which exceeds median + 6 * MAD here.
        assertEquals(maxOf(1.1 + 6.0 * 0.1, 1.5 * result.t1), result.t2, 1e-9)
        assertEquals(5, result.nHealthy)
        assertEquals(0, result.nFaulty)
    }

    @Test
    fun madIsFlooredWhenHealthyClusterIsTight() {
        val healthy = listOf(1.0, 1.0, 1.0, 1.0, 1.0) // MAD = 0, floored to 0.05
        val result = CalibrationMath.compute(healthy, emptyList(), k1 = 3.0, k2 = 6.0, minHealthy = 5)

        assertNotNull(result)
        assertEquals(CalibrationMath.MAD_FLOOR, result!!.madHealthy, 1e-9)
        assertEquals(1.0 + 3.0 * CalibrationMath.MAD_FLOOR, result.t1, 1e-9)
    }

    @Test
    fun faultyAdjustmentTightensT1AndRaisesT2() {
        val healthy = listOf(1.0, 1.2, 1.1, 0.9, 1.3) // median 1.1, mad 0.1
        val faulty = listOf(1.4, 2.0) // maxHealthy 1.3, minFaulty 1.4 -> midpoint 1.35 > median

        val result = CalibrationMath.compute(healthy, faulty, k1 = 3.0, k2 = 6.0, minHealthy = 5)

        assertNotNull(result)
        val rawT1 = 1.1 + 3.0 * 0.1 // 1.4
        val midpoint1 = (1.3 + 1.4) / 2.0 // 1.35, pulls t1 down from 1.4
        assertEquals(minOf(rawT1, midpoint1), result!!.t1, 1e-9)
        val midpoint2 = (result.t1 + 2.0) / 2.0
        assertTrue("t2 should be pushed up to at least the faulty midpoint", result.t2 >= midpoint2 - 1e-9)
        assertEquals(2, result.nFaulty)
    }

    @Test
    fun t2NeverFallsBelowOneAndAHalfTimesT1() {
        // Healthy cluster wide enough that raw t1/t2 from median+k*mad would already
        // satisfy t2 >= 1.5*t1, but a close faulty midpoint would undercut it without the floor.
        val healthy = listOf(1.0, 1.2, 1.1, 0.9, 1.3)
        val faulty = listOf(1.31, 1.32) // minFaulty just above maxHealthy -> tiny midpoints

        val result = CalibrationMath.compute(healthy, faulty, k1 = 3.0, k2 = 6.0, minHealthy = 5)

        assertNotNull(result)
        assertTrue(result!!.t2 >= 1.5 * result.t1 - 1e-9)
    }

    @Test
    fun fewerThanMinHealthyReturnsNull() {
        val healthy = listOf(1.0, 1.1, 1.2, 1.3)
        assertNull(CalibrationMath.compute(healthy, listOf(5.0), k1 = 3.0, k2 = 6.0, minHealthy = 5))
    }

    @Test
    fun driftDetectedWhenRecentHealthyMeanExceedsRatioOfT1() {
        val recent = listOf(1.5, 1.6, 1.7)
        assertTrue(CalibrationMath.driftDetected(recent, t1 = 2.0, driftRatio = 0.5)) // mean 1.6 > 1.0
    }

    @Test
    fun noDriftWhenRecentHealthyMeanBelowRatioOfT1() {
        val recent = listOf(0.1, 0.2, 0.15)
        assertFalse(CalibrationMath.driftDetected(recent, t1 = 2.0, driftRatio = 0.5)) // mean ~0.15 < 1.0
    }

    @Test
    fun driftIsFalseWithNoRecentReadings() {
        assertFalse(CalibrationMath.driftDetected(emptyList(), t1 = 2.0, driftRatio = 0.5))
    }

    /** v16: a High/Very-high asset scales k1, k2 and the MAD floor by its sensitivity factor `f`
     *  so calibration keeps it tighter than Standard instead of snapping back. Median is chosen
     *  as 0 and the healthy spread well above either floor so the k*mad term - the only term
     *  affected by f - scales exactly linearly and isolates the scaling behaviour being tested. */
    @Test
    fun sensitivityFactorHalvesThresholdsOnIdenticalScores() {
        val healthy = listOf(-1.0, -0.5, 0.0, 0.5, 1.0) // median 0.0, mad 0.5 (well above any floor here)
        val f = 0.5

        val standard = CalibrationMath.compute(
            healthy, emptyList(), k1 = 3.0, k2 = 6.0, minHealthy = 5, madFloor = CalibrationMath.MAD_FLOOR,
        )
        val scaled = CalibrationMath.compute(
            healthy, emptyList(), k1 = 3.0 * f, k2 = 6.0 * f, minHealthy = 5, madFloor = CalibrationMath.MAD_FLOOR * f,
        )

        assertNotNull(standard)
        assertNotNull(scaled)
        assertEquals(standard!!.t1 * f, scaled!!.t1, 1e-9)
        assertEquals(standard.t2 * f, scaled.t2, 1e-9)
    }

    @Test
    fun madFloorScalesWithSensitivityFactor() {
        val identical = listOf(1.0, 1.0, 1.0, 1.0, 1.0) // mad = 0, always floored
        val f = 0.5

        val standard = CalibrationMath.compute(identical, emptyList(), k1 = 3.0, k2 = 6.0, minHealthy = 5)
        val scaled = CalibrationMath.compute(
            identical, emptyList(), k1 = 3.0, k2 = 6.0, minHealthy = 5, madFloor = CalibrationMath.MAD_FLOOR * f,
        )

        assertNotNull(standard)
        assertNotNull(scaled)
        assertEquals(CalibrationMath.MAD_FLOOR * f, scaled!!.madHealthy, 1e-9)
        assertTrue("a smaller MAD floor should tighten t1", scaled.t1 < standard!!.t1)
    }
}
