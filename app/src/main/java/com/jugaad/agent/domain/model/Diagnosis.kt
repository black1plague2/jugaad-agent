package com.jugaad.agent.domain.model

/**
 * One diagnose run. Stored as filesDir/assets/<assetId>/history/<id>.json plus a
 * sibling <id>.png spectrogram thumbnail.
 */
data class Diagnosis(
    val id: String,
    val assetId: String,
    val timestampMs: Long,

    // --- Anomaly path (always present) -------------------------------------
    val anomalyScore: Double,
    val cosineDistance: Double,
    val spread: Double,
    val status: MachineStatus,
    val imuIndex: Double,
    val dominantHz: Double,
    /** Which side of [com.jugaad.agent.ml.anomaly.AnomalyScorer.Result] drove the score: "acoustic" or "sensor". */
    val dominantSource: String = "acoustic",
    val sensorScore: Double = 0.0,

    // --- CNN path (present only when a model is loaded AND status != Healthy) --
    val faultClass: FaultClass? = null,
    val faultConfidence: Float = 0f,
    val backend: InferenceBackend = InferenceBackend.NONE,
    val inferenceMs: Long = 0L,

    // --- Rule-based issue suggestions (empty when Healthy) -----------------
    val issues: List<IssueSuggestion> = emptyList(),

    // --- LLM / template advice --------------------------------------------
    val advice: String = "",
    val adviceSource: AdviceSource = AdviceSource.NONE,

    /** Relative path (within the asset folder) to the spectrogram PNG. */
    val spectrogramPng: String? = null,
) {
    enum class AdviceSource { NONE, TEMPLATE, GEMMA }
}
