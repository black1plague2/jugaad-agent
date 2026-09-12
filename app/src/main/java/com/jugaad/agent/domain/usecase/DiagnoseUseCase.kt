package com.jugaad.agent.domain.usecase

import android.Manifest
import androidx.annotation.RequiresPermission
import com.jugaad.agent.core.Constants
import com.jugaad.agent.core.Logx
import com.jugaad.agent.core.Outcome
import com.jugaad.agent.core.config.AppConfig
import com.jugaad.agent.core.config.MachineType
import com.jugaad.agent.domain.model.Diagnosis
import com.jugaad.agent.domain.model.MachineStatus
import com.jugaad.agent.domain.repository.AssetRepository
import com.jugaad.agent.domain.repository.DiagnosisRepository
import com.jugaad.agent.fl.FeatureDelta
import com.jugaad.agent.fl.SampleSource
import com.jugaad.agent.fl.SampleStore
import com.jugaad.agent.ml.FeatureExtractor
import com.jugaad.agent.ml.advisor.AdviceContext
import com.jugaad.agent.ml.advisor.MaintenanceAdvisor
import com.jugaad.agent.ml.anomaly.AnomalyScorer
import com.jugaad.agent.ml.classifier.FaultClassifier
import com.jugaad.agent.ml.diagnosis.EvidenceExtractor
import com.jugaad.agent.ml.diagnosis.RulesEngine
import com.jugaad.agent.ml.signal.LogMel
import com.jugaad.agent.sensor.CaptureCoordinator
import java.util.UUID

/**
 * Step 3-4 of the user flow: one 3-second clip -> anomaly score
 * -> (optional) CNN fault class -> ranked issue suggestions -> on-device advice
 * -> persisted [Diagnosis].
 *
 * The anomaly score + status are ALWAYS produced. The CNN label and the rule-engine
 * issue suggestions are only computed (and only shown) when status != Healthy and a
 * classifier/catalogue entry is ready. Advice comes from Gemma when available,
 * otherwise the deterministic template.
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
    private val sampleStore: SampleStore? = null,
    private val cfg: () -> AppConfig,
    private val catalog: (String) -> MachineType,
    private val calibrate: CalibrateUseCase? = null,
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

        val a = features.analyze(cap.audio, cap.motion)
        val appConfig = cfg()

        val baseStats = AnomalyScorer.BaselineStats(
            mean = baseline.meanFeature,
            spread = baseline.spread,
            rawStd = baseline.rawStd,
            clipCount = baseline.clipCount,
            imuIndexStd = baseline.imuIndexStd,
            gyroIndexStd = baseline.gyroIndexStd,
            magIndexStd = baseline.magIndexStd,
            magRmsStd = baseline.magRmsStd,
        )
        val sensorDeltas = doubleArrayOf(
            a.imuIndex - baseline.imuIndexMean,
            a.gyroIndex - baseline.gyroIndexMean,
            a.magIndex - baseline.magIndexMean,
            a.magRms - baseline.magRmsMean,
        )
        val anomaly = scorer.score(a.feature, baseStats, asset.thresholds, sensorDeltas, appConfig)

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
            runCatching {
                classifier.classify(
                    FaultClassifier.Input(a.logMel.mel, a.feature, a.imuIndex, baseline, a.gyroIndex, a.magIndex, a.magRms),
                )
            }.getOrNull()
        } else null
        val pred = if (anomaly.status != MachineStatus.HEALTHY) rawPred else null
        // A non-finite confidence means the prediction itself is untrustworthy (e.g. a still-
        // poisoned FL head): drop the fault class along with it rather than persist a label
        // paired with a fabricated confidence. kotlinx.serialization throws saving a NaN/Infinity
        // JSON number, and `pred?.confidence ?: 0f` alone never catches it since NaN != null.
        val safePred = sanitizePrediction(pred)

        // --- Rule-engine issue suggestions (should-have) ------------------------
        // Evidence is cheap and pure to compute either way; only expose suggestions
        // when the machine is not Healthy — the catalogue never speaks up on a
        // clean reading.
        val evidence = EvidenceExtractor.extract(a, baseline, anomaly, pred, appConfig)
        val rawIssues = runCatching { RulesEngine.evaluate(catalog(asset.machineTypeId), evidence, appConfig) }
            .onFailure { Logx.w("RulesEngine.evaluate failed for machineType=${asset.machineTypeId}", it) }
            .getOrDefault(emptyList())
        val issues = if (anomaly.status != MachineStatus.HEALTHY) rawIssues else emptyList()
        val topIssue = issues.firstOrNull()

        // --- Advice ---------------------------------------------------------
        val ctx = AdviceContext(
            status = anomaly.status,
            anomalyScore = anomaly.score,
            fault = pred?.faultClass,
            dominantHz = dominantHz,
            imuIndex = a.imuIndex,
            assetName = asset.name,
            topIssue = topIssue,
            issues = issues,
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
            dominantSource = anomaly.dominantSource,
            sensorScore = anomaly.sensorScore,
            faultClass = safePred?.faultClass,
            faultConfidence = safePred?.confidence ?: 0f,
            backend = pred?.backend ?: com.jugaad.agent.domain.model.InferenceBackend.NONE,
            inferenceMs = pred?.inferenceMs ?: 0L,
            issues = issues,
            advice = adviceText,
            adviceSource = adviceSource,
        )
        val saved = history.save(diagnosis, png)
        Logx.i("diagnosis ${saved.id} status=${saved.status} score=${"%.2f".format(saved.anomalyScore)} fault=${saved.faultClass?.label} backend=${saved.backend} issues=${saved.issues.size}")

        // --- FL sample capture (should-have) --------------------------------
        // Never let this break a diagnosis: queue the reading for training, and
        // auto-label a clearly-healthy clip as class 0 so there's data to train
        // on before any technician has tapped a label.
        // Bench/test equipment is excluded at the source: no FL sample, no
        // peer-sample-pool entry, no calibration input.
        if (asset.benchTest) {
            Logx.i("diagnosis ${saved.id}: bench equipment, not used for training")
        } else {
            val store = sampleStore
            if (store != null) {
                runCatching {
                    val x = FeatureDelta.build(a.feature, a.imuIndex, a.gyroIndex, a.magIndex, a.magRms, baseline)
                    val absSensors = doubleArrayOf(a.imuIndex, a.gyroIndex, a.magIndex, a.magRms).map { it.toFloat() }.toFloatArray()
                    store.addPending(saved.id, assetId, x, anomaly.score, a.feature, absSensors, asset.machineTypeId)
                    if (anomaly.status == MachineStatus.HEALTHY && anomaly.score <= 0.5 * asset.thresholds.t1) {
                        store.label(saved.id, 0, SampleSource.AUTO)
                    }
                }
            }
            runCatching { calibrate?.autoRun(assetId) }
        }

        return Outcome.Ok(Result(saved, a.logMel))
    }

    companion object {
        /**
         * Drops a prediction whose confidence is non-finite (NaN/Infinity) so it never reaches
         * a persisted [Diagnosis]. Pulled out as its own function (rather than inlined at the
         * call site) so the guard is exercisable in a JVM unit test independent of the rest of
         * [run], which needs a real [CaptureCoordinator] (AudioRecord/SensorManager) to execute.
         */
        internal fun sanitizePrediction(pred: FaultClassifier.Prediction?): FaultClassifier.Prediction? =
            pred?.takeIf { it.confidence.isFinite() }
    }
}
