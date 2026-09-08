package com.jugaad.agent.domain.usecase

import android.Manifest
import androidx.annotation.RequiresPermission
import com.jugaad.agent.core.Constants
import com.jugaad.agent.core.Logx
import com.jugaad.agent.core.Outcome
import com.jugaad.agent.domain.model.Diagnosis
import com.jugaad.agent.domain.model.MachineStatus
import com.jugaad.agent.domain.repository.AssetRepository
import com.jugaad.agent.domain.repository.DiagnosisRepository
import com.jugaad.agent.ml.FeatureExtractor
import com.jugaad.agent.ml.advisor.AdviceContext
import com.jugaad.agent.ml.advisor.MaintenanceAdvisor
import com.jugaad.agent.ml.anomaly.AnomalyScorer
import com.jugaad.agent.ml.classifier.FaultClassifier
import com.jugaad.agent.ml.signal.LogMel
import com.jugaad.agent.sensor.CaptureCoordinator
import java.util.UUID

/**
 * Step 3-4 of the user flow: one 3-second clip -> anomaly score
 * -> (optional) CNN fault class -> on-device advice -> persisted [Diagnosis].
 *
 * The anomaly score + status are ALWAYS produced. The CNN label is only computed
 * (and only shown) when status != Healthy and a classifier is ready. Advice comes
 * from Gemma when available, otherwise the deterministic template.
 */
class DiagnoseUseCase(
    private val coordinator: CaptureCoordinator,
    private val features: FeatureExtractor,
    private val scorer: AnomalyScorer,
    private val classifier: FaultClassifier,
    private val advisor: MaintenanceAdvisor,
    private val fallbackAdvisor: MaintenanceAdvisor,
    private val assets: AssetRepository,
    private val history: DiagnosisRepository,
    private val pngRenderer: SpectrogramPngRenderer,
) {
    data class Result(val diagnosis: Diagnosis, val logMel: LogMel)

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    suspend fun run(assetId: String): Outcome<Result> {
        val asset = assets.getAsset(assetId)
            ?: return Outcome.Err("Asset $assetId not found")
        val baseline = assets.getBaseline(assetId)
            ?: return Outcome.Err("No baseline yet — capture a baseline first")

        val cap = when (val c = coordinator.capture()) {
            is Outcome.Err -> return c
            is Outcome.Ok -> c.value
        }

        val a = features.analyze(cap.audio, cap.imu)

        val baseStats = AnomalyScorer.BaselineStats(
            mean = baseline.meanFeature,
            spread = baseline.spread,
            rawStd = baseline.rawStd,
            clipCount = baseline.clipCount,
        )
        val anomaly = scorer.score(a.feature, baseStats, asset.thresholds)

        // Frequency to name in the advice: acoustic divergence band, else IMU peak.
        val dominantHz = when {
            anomaly.dominantDivergenceHz > 0 -> anomaly.dominantDivergenceHz
            a.imuDominantHz > 0 -> a.imuDominantHz
            else -> 0.0
        }

        // --- CNN (should-have) --------------------------------------------------
        // Always run inference when a classifier is ready (keeps the on-device-AI
        // path exercised on every diagnose), but only expose the label when the
        // machine is not Healthy — per spec.
        val rawPred = if (classifier.isReady) {
            runCatching { classifier.classify(a.logMel.mel) }.getOrNull()
        } else null
        val pred = if (anomaly.status != MachineStatus.HEALTHY) rawPred else null

        // --- Advice ---------------------------------------------------------
        val ctx = AdviceContext(
            status = anomaly.status,
            anomalyScore = anomaly.score,
            fault = pred?.faultClass,
            dominantHz = dominantHz,
            imuIndex = a.imuIndex,
            assetName = asset.name,
        )
        var adviceText = runCatching { advisor.advise(ctx) }.getOrDefault("")
        var adviceSource = advisor.source
        if (adviceText.isBlank()) {
            adviceText = fallbackAdvisor.advise(ctx)
            adviceSource = fallbackAdvisor.source
        }

        // --- Spectrogram PNG ---------------------------------------------------
        val png = runCatching {
            pngRenderer.render(a.logMel.mel, a.logMel.nMels, a.logMel.frames, 512, 512)
        }.getOrNull()

        val diagnosis = Diagnosis(
            id = UUID.randomUUID().toString().take(10),
            assetId = assetId,
            timestampMs = System.currentTimeMillis(),
            anomalyScore = anomaly.score,
            cosineDistance = anomaly.cosineDistance,
            spread = anomaly.spread,
            status = anomaly.status,
            imuIndex = a.imuIndex,
            dominantHz = dominantHz,
            faultClass = pred?.faultClass,
            faultConfidence = pred?.confidence ?: 0f,
            backend = pred?.backend ?: com.jugaad.agent.domain.model.InferenceBackend.NONE,
            inferenceMs = pred?.inferenceMs ?: 0L,
            advice = adviceText,
            adviceSource = adviceSource,
        )
        val saved = history.save(diagnosis, png)
        Logx.i("diagnosis ${saved.id} status=${saved.status} score=${"%.2f".format(saved.anomalyScore)} fault=${saved.faultClass?.label} backend=${saved.backend}")
        return Outcome.Ok(Result(saved, a.logMel))
    }
}
