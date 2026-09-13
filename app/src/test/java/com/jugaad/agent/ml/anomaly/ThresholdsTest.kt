package com.jugaad.agent.ml.anomaly

import com.jugaad.agent.domain.model.Sensitivity
import org.junit.Assert.assertEquals
import org.junit.Test

class ThresholdsTest {

    @Test
    fun forSensitivityStandardMatchesDefault() {
        val t = Thresholds.forSensitivity(Sensitivity.STANDARD)
        assertEquals(2.0, t.t1, 1e-9)
        assertEquals(4.0, t.t2, 1e-9)
    }

    @Test
    fun forSensitivityHighIsHalfOfStandard() {
        val t = Thresholds.forSensitivity(Sensitivity.HIGH)
        assertEquals(1.0, t.t1, 1e-9)
        assertEquals(2.0, t.t2, 1e-9)
    }

    @Test
    fun forSensitivityVeryHighIsQuarterOfStandard() {
        val t = Thresholds.forSensitivity(Sensitivity.VERY_HIGH)
        assertEquals(0.5, t.t1, 1e-9)
        assertEquals(1.0, t.t2, 1e-9)
    }

    @Test
    fun safeKeepsVeryHighDefaultsUnclamped() {
        val t = Thresholds.safe(0.5, 1.0)
        assertEquals(0.5, t.t1, 1e-9)
        assertEquals(1.0, t.t2, 1e-9)
    }

    @Test
    fun safeKeepsSmallerThanVeryHighDefaultUnclamped() {
        // A calibrated Very high asset can land below its own 0.5/1.0 defaults; safe() must not
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
}
