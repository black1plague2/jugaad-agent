package com.jugaad.agent.data.model

import com.jugaad.agent.domain.model.Asset
import com.jugaad.agent.domain.model.Baseline
import com.jugaad.agent.domain.model.Diagnosis
import com.jugaad.agent.domain.model.FaultClass
import com.jugaad.agent.domain.model.InferenceBackend
import com.jugaad.agent.domain.model.IssueSuggestion
import com.jugaad.agent.domain.model.MachineStatus
import com.jugaad.agent.ml.anomaly.Thresholds
import kotlinx.serialization.Serializable

/* ============================ On-disk schema ============================
 * Deliberately flat + primitive so files stay diff-able and forward-compatible.
 * `schema` lets a future version migrate. Everything lives in filesDir/assets/.
 * ====================================================================== */

@Serializable
data class AssetDto(
    val schema: Int = 1,
    val id: String,
    val name: String,
    val createdAtMs: Long,
    val nameplatePhoto: String? = null,
    val hasBaseline: Boolean = false,
    val t1: Double = Thresholds.DEFAULT.t1,
    val t2: Double = Thresholds.DEFAULT.t2,
    val machineTypeId: String = "generic",
    val benchTest: Boolean = false,
) {
    fun toDomain() = Asset(
        id = id,
        name = name,
        createdAtMs = createdAtMs,
        nameplatePhoto = nameplatePhoto,
        hasBaseline = hasBaseline,
        thresholds = Thresholds.safe(t1, t2),
        machineTypeId = machineTypeId,
        benchTest = benchTest,
    )

    companion object {
        fun from(a: Asset) = AssetDto(
            id = a.id,
            name = a.name,
            createdAtMs = a.createdAtMs,
            nameplatePhoto = a.nameplatePhoto,
            hasBaseline = a.hasBaseline,
            t1 = a.thresholds.t1,
            t2 = a.thresholds.t2,
            machineTypeId = a.machineTypeId,
            benchTest = a.benchTest,
        )
    }
}

@Serializable
data class BaselineDto(
    val schema: Int = 1,
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
) {
    fun toDomain() = Baseline(
        assetId, capturedAtMs, meanFeature, spread, rawStd, imuIndexMean, clipCount,
        gyroIndexMean, magIndexMean, magRmsMean,
        imuIndexStd, gyroIndexStd, magIndexStd, magRmsStd,
    )

    companion object {
        fun from(b: Baseline) = BaselineDto(
            assetId = b.assetId,
            capturedAtMs = b.capturedAtMs,
            meanFeature = b.meanFeature,
            spread = b.spread,
            rawStd = b.rawStd,
            imuIndexMean = b.imuIndexMean,
            clipCount = b.clipCount,
            gyroIndexMean = b.gyroIndexMean,
            magIndexMean = b.magIndexMean,
            magRmsMean = b.magRmsMean,
            imuIndexStd = b.imuIndexStd,
            gyroIndexStd = b.gyroIndexStd,
            magIndexStd = b.magIndexStd,
            magRmsStd = b.magRmsStd,
        )
    }
}

@Serializable
data class DiagnosisDto(
    val schema: Int = 1,
    val id: String,
    val assetId: String,
    val timestampMs: Long,
    val anomalyScore: Double,
    val cosineDistance: Double,
    val spread: Double,
    val status: String,
    val imuIndex: Double,
    val dominantHz: Double,
    val dominantSource: String = "acoustic",
    val sensorScore: Double = 0.0,
    val faultClassIndex: Int? = null,
    val faultConfidence: Float = 0f,
    val backend: String = InferenceBackend.NONE.name,
    val inferenceMs: Long = 0L,
    val issues: List<IssueSuggestion> = emptyList(),
    val advice: String = "",
    val adviceSource: String = Diagnosis.AdviceSource.NONE.name,
    val spectrogramPng: String? = null,
) {
    fun toDomain() = Diagnosis(
        id = id,
        assetId = assetId,
        timestampMs = timestampMs,
        anomalyScore = anomalyScore,
        cosineDistance = cosineDistance,
        spread = spread,
        status = runCatching { MachineStatus.valueOf(status) }.getOrDefault(MachineStatus.HEALTHY),
        imuIndex = imuIndex,
        dominantHz = dominantHz,
        dominantSource = dominantSource,
        sensorScore = sensorScore,
        faultClass = faultClassIndex?.let { FaultClass.fromIndex(it) },
        faultConfidence = faultConfidence,
        backend = runCatching { InferenceBackend.valueOf(backend) }.getOrDefault(InferenceBackend.NONE),
        inferenceMs = inferenceMs,
        issues = issues,
        advice = advice,
        adviceSource = runCatching { Diagnosis.AdviceSource.valueOf(adviceSource) }
            .getOrDefault(Diagnosis.AdviceSource.NONE),
        spectrogramPng = spectrogramPng,
    )

    companion object {
        fun from(d: Diagnosis) = DiagnosisDto(
            id = d.id,
            assetId = d.assetId,
            timestampMs = d.timestampMs,
            anomalyScore = d.anomalyScore,
            cosineDistance = d.cosineDistance,
            spread = d.spread,
            status = d.status.name,
            imuIndex = d.imuIndex,
            dominantHz = d.dominantHz,
            dominantSource = d.dominantSource,
            sensorScore = d.sensorScore,
            faultClassIndex = d.faultClass?.index,
            faultConfidence = d.faultConfidence,
            backend = d.backend.name,
            inferenceMs = d.inferenceMs,
            issues = d.issues,
            advice = d.advice,
            adviceSource = d.adviceSource.name,
            spectrogramPng = d.spectrogramPng,
        )
    }
}
