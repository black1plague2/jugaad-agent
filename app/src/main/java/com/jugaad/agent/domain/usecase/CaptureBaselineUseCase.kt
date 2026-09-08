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

        for (i in 1..clips) {
            onEvent(Event.ClipStarted(i, clips))
            when (val cap = coordinator.capture()) {
                is Outcome.Err -> {
                    onEvent(Event.Failed(cap.message))
                    return cap
                }
                is Outcome.Ok -> {
                    val a = features.analyze(cap.value.audio, cap.value.imu)
                    featureVectors.add(a.feature)
                    imuIndices.add(a.imuIndex)
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
        )
        assets.saveBaseline(baseline)
        Logx.i("baseline saved asset=$assetId spread=${"%.4f".format(baseline.spread)} imu=${"%.3f".format(baseline.imuIndexMean)}")
        onEvent(Event.Finished(baseline))
        return Outcome.Ok(baseline)
    }
}
