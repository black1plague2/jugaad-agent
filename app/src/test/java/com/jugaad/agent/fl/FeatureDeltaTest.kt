package com.jugaad.agent.fl

import com.jugaad.agent.domain.model.Baseline
import org.junit.Assert.assertEquals
import org.junit.Test

class FeatureDeltaTest {

    private fun baseline(
        mean: FloatArray,
        imuMean: Double,
        gyroMean: Double = 0.0,
        magMean: Double = 0.0,
        magRmsMean: Double = 0.0,
    ) = Baseline(
        assetId = "a1",
        capturedAtMs = 0L,
        meanFeature = mean,
        spread = 1.0,
        rawStd = 1.0,
        imuIndexMean = imuMean,
        clipCount = 3,
        gyroIndexMean = gyroMean,
        magIndexMean = magMean,
        magRmsMean = magRmsMean,
    )

    @Test
    fun outputHasInputDimLength() {
        val feature = FloatArray(256) { it.toFloat() }
        val base = baseline(FloatArray(256), 0.0)
        val x = FeatureDelta.build(feature, 1.0, 0.0, 0.0, 0.0, base)
        assertEquals(FlConstants.INPUT_DIM, x.size)
        assertEquals(260, x.size)
    }

    @Test
    fun deltaIsFeatureMinusBaselineMean() {
        val feature = FloatArray(256) { (it + 1).toFloat() }
        val mean = FloatArray(256) { 0.5f }
        val base = baseline(mean, 2.0)
        val x = FeatureDelta.build(feature, 2.0, 0.0, 0.0, 0.0, base)
        for (i in 0 until 256) {
            assertEquals((feature[i] - mean[i]).toDouble(), x[i].toDouble(), 1e-6)
        }
    }

    @Test
    fun sensorTermsAreScaledDeltas() {
        val feature = FloatArray(256)
        val base = baseline(FloatArray(256), imuMean = 1.5, gyroMean = 0.5, magMean = 0.2, magRmsMean = 3.0)
        val x = FeatureDelta.build(feature, 2.0, 1.0, 0.4, 5.0, base)
        assertEquals((2.0 - 1.5) * FlConstants.IMU_SCALE, x[256].toDouble(), 1e-6)
        assertEquals((1.0 - 0.5) * FlConstants.IMU_SCALE, x[257].toDouble(), 1e-6)
        assertEquals((0.4 - 0.2) * FlConstants.IMU_SCALE, x[258].toDouble(), 1e-6)
        assertEquals((5.0 - 3.0) / 3.0, x[259].toDouble(), 1e-6)
    }

    @Test
    fun missingSensorsGiveZeroDelta() {
        val feature = FloatArray(256)
        val base = baseline(FloatArray(256), imuMean = 0.0, gyroMean = 0.0, magMean = 0.0, magRmsMean = 0.0)
        val x = FeatureDelta.build(feature, 0.0, 0.0, 0.0, 0.0, base)
        assertEquals(0.0, x[256].toDouble(), 1e-6)
        assertEquals(0.0, x[257].toDouble(), 1e-6)
        assertEquals(0.0, x[258].toDouble(), 1e-6)
        assertEquals(0.0, x[259].toDouble(), 1e-6)
    }

    @Test
    fun deprecatedThreeArgOverloadDelegatesWithZeros() {
        val feature = FloatArray(256)
        val base = baseline(FloatArray(256), imuMean = 1.0, gyroMean = 0.3, magMean = 0.1, magRmsMean = 2.0)
        @Suppress("DEPRECATION")
        val x = FeatureDelta.build(feature, 2.0, base)
        val x2 = FeatureDelta.build(feature, 2.0, 0.0, 0.0, 0.0, base)
        assertEquals(x2.toList(), x.toList())
    }
}
