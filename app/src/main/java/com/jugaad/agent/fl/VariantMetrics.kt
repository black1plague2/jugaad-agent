package com.jugaad.agent.fl

import kotlinx.serialization.Serializable

/** One variant's latest local training/evaluation snapshot. Persisted at `fl/metrics_<id>.json`. */
@Serializable
data class VariantMetrics(
    val variantId: String,
    val round: Int,
    val nTrain: Int,
    val nVal: Int,
    val trainAcc: Float,
    val valAcc: Float,
    val lastLoss: Float,
    val lastTrainedMs: Long,
    /** Best held-out mean cross-entropy seen during early stopping; 0 when `nVal < 4` (Decision 4, v3). */
    val valLoss: Float = 0f,
    /** Epoch (0-indexed) whose weights were kept; the last epoch run when there's no held-out data. */
    val bestEpoch: Int = 0,
    /** `trainAcc - valAcc`, or 0 when `valAcc < 0` (no held-out data) — shown as "Overfitting" at >= 0.15. */
    val gap: Float = 0f,
    /** Wall-clock training time for the last [train] call, in ms. */
    val trainMs: Long = 0L,
)
