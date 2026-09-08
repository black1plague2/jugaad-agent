package com.jugaad.agent.domain.model

/**
 * The healthy fingerprint of an asset. Stored at filesDir/assets/<id>/baseline.json.
 *
 * @param meanFeature   256-d mean log-mel statistic vector across [clipCount] clips
 * @param spread        healthy-cluster radius (floored) used to normalise the score
 * @param rawStd        un-floored std of the per-clip distances (shown for calibration)
 * @param imuIndexMean  mean IMU 5-60 Hz vibration index while healthy
 * @param clipCount     number of baseline clips (spec: 3)
 */
data class Baseline(
    val assetId: String,
    val capturedAtMs: Long,
    val meanFeature: FloatArray,
    val spread: Double,
    val rawStd: Double,
    val imuIndexMean: Double,
    val clipCount: Int,
)
