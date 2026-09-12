package com.jugaad.agent.domain.model

/**
 * The healthy fingerprint of an asset. Stored at filesDir/assets/<id>/baseline.json.
 *
 * @param meanFeature     256-d mean log-mel statistic vector across [clipCount] clips
 * @param spread          healthy-cluster radius (floored) used to normalise the score
 * @param rawStd          un-floored std of the per-clip distances (shown for calibration)
 * @param imuIndexMean    mean accelerometer 5-60 Hz vibration index while healthy
 * @param clipCount       number of baseline clips (spec: 3)
 * @param gyroIndexMean   mean gyroscope 5-60 Hz vibration index while healthy (0.0 if no gyroscope)
 * @param magIndexMean    mean magnetometer 3-25 Hz vibration index while healthy (0.0 if no magnetometer)
 * @param magRmsMean      mean magnetometer AC RMS (microtesla) while healthy (0.0 if no magnetometer)
 * @param imuIndexStd     population std of the accelerometer index across the baseline clips
 * @param gyroIndexStd    population std of the gyroscope index across the baseline clips
 * @param magIndexStd     population std of the magnetometer index across the baseline clips
 * @param magRmsStd       population std of the magnetometer AC RMS across the baseline clips
 */
data class Baseline(
    val assetId: String,
    val capturedAtMs: Long,
    val meanFeature: FloatArray,
    val spread: Double,
    val rawStd: Double,
    val imuIndexMean: Double,
    val clipCount: Int,
    val gyroIndexMean: Double = 0.0,
    val magIndexMean: Double = 0.0,
    val magRmsMean: Double = 0.0,
    val imuIndexStd: Double = 0.0,
    val gyroIndexStd: Double = 0.0,
    val magIndexStd: Double = 0.0,
    val magRmsStd: Double = 0.0,
)
