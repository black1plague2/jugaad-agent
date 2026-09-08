package com.jugaad.agent.ml.signal

import com.jugaad.agent.core.Constants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * JVM test — JTransforms is a pure-Java lib so the on-device front-end runs
 * unchanged here. This is the parity anchor against ml/logmel_reference.py.
 */
class LogMelSpectrogramTest {

    private val fe = LogMelSpectrogram()
    private val bank = MelFilterBank(
        Constants.SAMPLE_RATE_HZ, Constants.N_FFT, Constants.N_MELS,
        Constants.MEL_FMIN_HZ, Constants.MEL_FMAX_HZ,
    )

    private fun sine(freqHz: Double, n: Int = Constants.CAPTURE_SAMPLES): FloatArray {
        val w = 2.0 * PI * freqHz / Constants.SAMPLE_RATE_HZ
        return FloatArray(n) { (0.5 * sin(w * it)).toFloat() }
    }

    private fun bandNearest(hz: Double): Int =
        bank.bandCenterHz.indices.minBy { kotlin.math.abs(bank.bandCenterHz[it] - hz) }

    @Test
    fun outputShapeIs128x128() {
        val lm = fe.compute(sine(1_000.0))
        assertEquals(Constants.N_MELS, lm.nMels)
        assertEquals(Constants.SPEC_FRAMES, lm.frames)
        assertEquals(Constants.N_MELS * Constants.SPEC_FRAMES, lm.mel.size)
    }

    @Test
    fun featureVectorIs256d() {
        val lm = fe.compute(sine(1_000.0))
        assertEquals(Constants.FEATURE_DIM, fe.featureVector(lm).size)
    }

    @Test
    fun toneEnergyLandsInTheRightMelBand() {
        val lm = fe.compute(sine(1_000.0))
        val feat = fe.featureVector(lm)         // first N_MELS entries = per-band mean
        val near = feat[bandNearest(1_000.0)]
        val far = feat[bandNearest(8_000.0)]
        assertTrue("1 kHz band ($near) should exceed 8 kHz band ($far)", near > far + 1.0)
    }

    @Test
    fun offLengthInputIsCoercedToFixedShape() {
        val lm = fe.compute(sine(500.0, n = 40_000))   // shorter than a full clip
        assertEquals(Constants.N_MELS * Constants.SPEC_FRAMES, lm.mel.size)
    }
}
