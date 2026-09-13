package com.jugaad.agent.ml.anomaly

import com.jugaad.agent.domain.model.MachineStatus
import com.jugaad.agent.domain.model.Sensitivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThresholdsTest {

    @Test
    fun forSensitivityStandardMatchesDefault() {
        val t = Thresholds.forSensitivity(Sensitivity.STANDARD)
        assertEquals(2.0, t.t1, 1e-9)
        assertEquals(4.0, t.t2, 1e-9)
    }

    @Test
    fun forSensitivityHighIsThreeQuartersOfStandard() {
        val t = Thresholds.forSensitivity(Sensitivity.HIGH)
        assertEquals(1.5, t.t1, 1e-9)
        assertEquals(3.0, t.t2, 1e-9)
    }

    @Test
    fun forSensitivityVeryHighIsHalfOfStandard() {
        val t = Thresholds.forSensitivity(Sensitivity.VERY_HIGH)
        assertEquals(1.0, t.t1, 1e-9)
        assertEquals(2.0, t.t2, 1e-9)
    }

    @Test
    fun safeKeepsVeryHighDefaultsUnclamped() {
        val t = Thresholds.safe(1.0, 2.0)
        assertEquals(1.0, t.t1, 1e-9)
        assertEquals(2.0, t.t2, 1e-9)
    }

    @Test
    fun safeKeepsSmallerThanVeryHighDefaultUnclamped() {
        // A calibrated Very high asset can land below its own 1.0/2.0 defaults; safe() must not
        // floor it back up to some larger minimum.
        val t = Thresholds.safe(0.05, 0.2)
        assertEquals(0.05, t.t1, 1e-9)
        assertEquals(0.2, t.t2, 1e-9)
    }

    @Test
    fun safeFallsBackToDefaultOnInvalidInput() {
        val t = Thresholds.safe(-1.0, 2.0)
        assertEquals(Thresholds.DEFAULT, t)
    }

    /** Hardware-measured resting scores (phone A, 2026-09-13, the four still readings out of
     *  five; the fifth was a knock and correctly reads Critical). No sensitivity profile may
     *  bucket ordinary resting noise as Critical, and Very high's tighter band should surface
     *  at least one Warning from that same noise, matching its on-screen description. */
    @Test
    fun restingNoiseNeverReadsCriticalAndVeryHighWarnsOnIt() {
        val restingScores = listOf(0.90, 1.12, 1.12, 1.97)

        for (sensitivity in Sensitivity.entries) {
            val thresholds = Thresholds.forSensitivity(sensitivity)
            val statuses = restingScores.map { MachineStatus.fromScore(it, thresholds.t1, thresholds.t2) }
            assertTrue(
                "$sensitivity must never read resting noise as Critical: $statuses",
                statuses.none { it == MachineStatus.CRITICAL },
            )
        }

        val veryHigh = Thresholds.forSensitivity(Sensitivity.VERY_HIGH)
        val veryHighStatuses = restingScores.map { MachineStatus.fromScore(it, veryHigh.t1, veryHigh.t2) }
        assertTrue(
            "Very high should warn on at least one resting reading: $veryHighStatuses",
            veryHighStatuses.any { it == MachineStatus.WARNING },
        )
    }
}
