package com.jugaad.agent.ml.signal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

class MagneticIndexTest {

    @Test
    fun sinusoidOnDcOffsetGivesHighIndexAndCorrectRms() {
        val rateHz = 100.0
        val n = 300 // 3 s at 100 Hz = exactly 36 cycles of the 12 Hz tone
        val freqHz = 12.0
        val amplitude = 5.0
        val dcOffset = 40.0 // microtesla, a typical ambient field magnitude
        val w = 2.0 * PI * freqHz / rateHz
        val mag = FloatArray(n) { (dcOffset + amplitude * sin(w * it)).toFloat() }

        val result = MagneticIndex.compute(mag, rateHz)

        assertTrue("index ${result.index} should exceed 0.8", result.index > 0.8)
        val expectedRms = amplitude / sqrt(2.0)
        assertEquals(expectedRms, result.rmsMicroTesla, 0.5)
    }

    @Test
    fun tooFewSamplesGivesZero() {
        val result = MagneticIndex.compute(FloatArray(4), 100.0)
        assertEquals(0.0, result.index, 1e-9)
        assertEquals(0.0, result.rmsMicroTesla, 1e-9)
    }

    @Test
    fun zeroRateGivesZero() {
        val result = MagneticIndex.compute(FloatArray(300), 0.0)
        assertEquals(0.0, result.index, 1e-9)
        assertEquals(0.0, result.rmsMicroTesla, 1e-9)
    }
}
