package com.jugaad.agent.domain.usecase

import android.Manifest
import androidx.annotation.RequiresPermission
import com.jugaad.agent.core.Constants
import com.jugaad.agent.core.Logx
import com.jugaad.agent.core.Outcome
import com.jugaad.agent.domain.model.Baseline
import com.jugaad.agent.domain.repository.AssetRepository
import com.jugaad.agent.ml.FeatureExtractor
import com.jugaad.agent.ml.anomaly.AnomalyScorer
import com.jugaad.agent.sensor.CaptureCoordinator

/**
 * Step 2 of the user flow: capture 3 x 3-second clips while the machine is healthy,
 * then store the mean feature vector + healthy-cluster spread + mean IMU index.
 */
class CaptureBaselineUseCase(
    private val coordinator: CaptureCoordinator,
    private val features: FeatureExtractor,
    private val scorer: AnomalyScorer,
    private val assets: AssetRepository,
) {
    sealed interface Event {
        data class ClipStarted(val index: Int, val total: Int) : Event
        data class ClipDone(val index: Int, val total: Int) : Event
        data class Finished(val baseline: Baseline) : Event
        data class Failed(val message: String) : Event
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    suspend fun run(
        assetId: String,
        clips: Int = Constants.BASELINE_CLIPS,
        onEvent: (Event) -> Unit = {},
    ): Outcome<Baseline> {
        val featureVectors = ArrayList<FloatArray>(clips)
        val imuIndices = ArrayList<Double>(clips)
        val gyroIndices = ArrayList<Double>(clips)
        val magIndices = ArrayList<Double>(clips)
        val magRmsValues = ArrayList<Double>(clips)

        for (i in 1..clips) {
            onEvent(Event.ClipStarted(i, clips))
            when (val cap = coordinator.capture()) {
                is Outcome.Err -> {
                    onEvent(Event.Failed(cap.message))
                    return cap
                }
                is Outcome.Ok -> {
                    val a = features.analyze(cap.value.audio, cap.value.motion)
                    featureVectors.add(a.feature)
                    imuIndices.add(a.imuIndex)
                    gyroIndices.add(a.gyroIndex)
                    magIndices.add(a.magIndex)
                    magRmsValues.add(a.magRms)
                    onEvent(Event.ClipDone(i, clips))
                }
            }
        }

        val stats = scorer.buildBaseline(featureVectors)
        val baseline = Baseline(
            assetId = assetId,
            capturedAtMs = System.currentTimeMillis(),
            meanFeature = stats.mean,
            spread = stats.spread,
            rawStd = stats.rawStd,
            imuIndexMean = if (imuIndices.isEmpty()) 0.0 else imuIndices.average(),
            clipCount = featureVectors.size,
            gyroIndexMean = if (gyroIndices.isEmpty()) 0.0 else gyroIndices.average(),
            magIndexMean = if (magIndices.isEmpty()) 0.0 else magIndices.average(),
            magRmsMean = if (magRmsValues.isEmpty()) 0.0 else magRmsValues.average(),
            imuIndexStd = populationStd(imuIndices),
            gyroIndexStd = populationStd(gyroIndices),
            magIndexStd = populationStd(magIndices),
            magRmsStd = populationStd(magRmsValues),
        )
        assets.saveBaseline(baseline)
        Logx.i(
            "baseline saved asset=$assetId spread=${"%.4f".format(baseline.spread)} " +
                "imu=${"%.3f".format(baseline.imuIndexMean)} gyro=${"%.3f".format(baseline.gyroIndexMean)} " +
                "mag=${"%.3f".format(baseline.magIndexMean)}"
        )
        onEvent(Event.Finished(baseline))
        return Outcome.Ok(baseline)
    }
}

/** Population std (divide by N, not N-1) over a small clip count — matches [AnomalyScorer.buildBaseline]'s rawStd. */
private fun populationStd(values: List<Double>): Double {
    if (values.isEmpty()) return 0.0
    val mean = values.average()
    return kotlin.math.sqrt(values.sumOf { (it - mean) * (it - mean) } / values.size)
}
