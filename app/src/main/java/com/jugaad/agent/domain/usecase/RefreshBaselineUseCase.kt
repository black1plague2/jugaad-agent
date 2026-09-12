package com.jugaad.agent.domain.usecase

import com.jugaad.agent.core.Constants
import com.jugaad.agent.core.Logx
import com.jugaad.agent.core.Outcome
import com.jugaad.agent.core.config.AppConfig
import com.jugaad.agent.domain.model.Baseline
import com.jugaad.agent.domain.repository.AssetRepository
import com.jugaad.agent.fl.FlSample
import com.jugaad.agent.fl.SampleStore
import com.jugaad.agent.ml.anomaly.AnomalyScorer
import kotlin.math.sqrt

/**
 * Recomputes an asset's reference measurement from its own recent healthy readings
 * instead of the original 3-clip capture, so the baseline tracks slow, legitimate
 * drift (a re-lubricated bearing, a cleaned filter) instead of flagging it forever.
 * See the v4 plan, "Adaptive calibration and everything odd" — [CalibrateUseCase]
 * decides *when* a refresh is warranted (drift + autoRefreshBaseline); this use
 * case does the recomputation itself.
 */
class RefreshBaselineUseCase(
    private val assets: AssetRepository,
    private val store: SampleStore,
    private val cfg: () -> AppConfig,
) {
    private val scorer = AnomalyScorer()

    /** Number of healthy-labelled samples for this asset that carry both [FlSample.abs] and [FlSample.absSensors]. */
    suspend fun canRefresh(assetId: String): Int = eligibleSamples(assetId).size

    /** Recomputes and saves the baseline from absolute features, needing >= `calibration.refreshMinHealthy`. */
    suspend fun refresh(assetId: String): Outcome<Baseline> {
        val eligible = eligibleSamples(assetId)
        val minNeeded = cfg().calibration.refreshMinHealthy
        if (eligible.size < minNeeded) {
            return Outcome.Err(
                "Need at least $minNeeded healthy readings with absolute features to refresh baseline, have ${eligible.size}"
            )
        }

        val floor = cfg().thresholds.spreadFloor
        val absFeatures = eligible.map { it.abs!! }
        val stats = scorer.buildBaseline(absFeatures, floor)
        val rawStd = maxOf(stats.rawStd, floor)

        val sensorMeans = DoubleArray(Constants.SENSOR_DIMS)
        for (s in eligible) {
            val sensors = s.absSensors!!
            for (i in 0 until Constants.SENSOR_DIMS) sensorMeans[i] += sensors[i]
        }
        for (i in 0 until Constants.SENSOR_DIMS) sensorMeans[i] /= eligible.size

        val sensorStds = DoubleArray(Constants.SENSOR_DIMS)
        for (s in eligible) {
            val sensors = s.absSensors!!
            for (i in 0 until Constants.SENSOR_DIMS) {
                val d = sensors[i] - sensorMeans[i]
                sensorStds[i] += d * d
            }
        }
        for (i in 0 until Constants.SENSOR_DIMS) sensorStds[i] = sqrt(sensorStds[i] / eligible.size)

        val baseline = Baseline(
            assetId = assetId,
            capturedAtMs = System.currentTimeMillis(),
            meanFeature = stats.mean,
            spread = stats.spread,
            rawStd = rawStd,
            imuIndexMean = sensorMeans[0],
            clipCount = eligible.size,
            gyroIndexMean = sensorMeans[1],
            magIndexMean = sensorMeans[2],
            magRmsMean = sensorMeans[3],
            imuIndexStd = sensorStds[0],
            gyroIndexStd = sensorStds[1],
            magIndexStd = sensorStds[2],
            magRmsStd = sensorStds[3],
        )
        assets.saveBaseline(baseline)
        Logx.i("reference refreshed asset=$assetId n=${eligible.size} spread=${"%.4f".format(baseline.spread)}")
        return Outcome.Ok(baseline)
    }

    /** Sensor order: accel, gyro, mag, magRms — matches [AnomalyScorer.score]'s sensorDeltas. */
    private suspend fun eligibleSamples(assetId: String): List<FlSample> =
        store.healthyForAsset(assetId).filter { it.abs != null && it.absSensors != null }
}
