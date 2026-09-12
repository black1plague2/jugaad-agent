package com.jugaad.agent.fl

import com.jugaad.agent.domain.model.Baseline

/**
 * Builds the 260-d input the federated head trains and infers on: the 256-d
 * log-mel feature expressed as a delta from this machine's own healthy
 * baseline, plus rescaled accelerometer/gyroscope/magnetometer deltas.
 * Deltas (not raw values) are what let a head trained on one machine
 * transfer to another. A missing sensor contributes 0 for both the reading
 * and the baseline, so its delta term is 0 rather than misleading.
 */
object FeatureDelta {
    fun build(
        feature: FloatArray,
        imuIndex: Double,
        gyroIndex: Double,
        magIndex: Double,
        magRms: Double,
        baseline: Baseline,
    ): FloatArray {
        val n = FlConstants.INPUT_DIM - FlConstants.SENSOR_DIMS // 256
        val x = FloatArray(FlConstants.INPUT_DIM)
        for (i in 0 until n) {
            x[i] = feature[i] - baseline.meanFeature[i]
        }
        x[n] = ((imuIndex - baseline.imuIndexMean) * FlConstants.IMU_SCALE).toFloat()
        x[n + 1] = ((gyroIndex - baseline.gyroIndexMean) * FlConstants.IMU_SCALE).toFloat()
        x[n + 2] = ((magIndex - baseline.magIndexMean) * FlConstants.IMU_SCALE).toFloat()
        x[n + 3] = ((magRms - baseline.magRmsMean) / maxOf(baseline.magRmsMean, 1.0)).toFloat()
        return x
    }

    /** Pre-v3 call sites: delegates with zero gyro/mag readings so nothing else breaks. */
    @Deprecated("Use the 6-arg build() with gyro/mag readings.")
    fun build(feature: FloatArray, imuIndex: Double, baseline: Baseline): FloatArray =
        build(feature, imuIndex, 0.0, 0.0, 0.0, baseline)
}
