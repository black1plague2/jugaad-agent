package com.jugaad.agent.fl

import kotlinx.coroutines.flow.StateFlow

/**
 * One trainable/inferable variant's behaviour, regardless of whether it's backed by a
 * TFLite MLP ([VariantTrainer]) or a TFLite-free frozen-backbone centroid
 * ([CentroidStrategy]). [FlRuntime], `p2p.FlPeer` and the Network UI all go through this
 * seam so v3's non-MLP variant kind needs no special-casing outside [FlRuntime]'s
 * construction (Decision 3, v3).
 */
interface Strategy {
    val spec: VariantSpec
    val metrics: StateFlow<VariantMetrics>

    /** @return probs[FlConstants.N_CLASSES]. */
    fun infer(x: FloatArray): FloatArray

    suspend fun train(): VariantMetrics

    fun currentWeights(): FloatArray

    fun applyMerged(w: FloatArray, newRound: Int)

    /** Held-out accuracy with weights [w] (or the current ones when null); -1 when there's no held-out data. */
    fun evaluateVal(w: FloatArray? = null): Float

    fun evaluateTrain(w: FloatArray? = null): Float

    /** 3x3 confusion matrix on the held-out set with the current weights; rows = true, cols = predicted. */
    fun confusionVal(): Array<IntArray>
}

/**
 * Labelled rows of [this] not held out for validation — the training split shared by
 * [VariantTrainer] and [CentroidStrategy] regardless of where the samples came from
 * (own [SampleStore] or [SharedPool]; v4 plan §4). Split is still by id hash, unchanged.
 */
fun List<FlSample>.trainSplit(): List<FlSample> = filter { it.label != null && !SampleStore.isValidation(it.id) }

/** Labelled rows of [this] held out for validation — see [trainSplit]. */
fun List<FlSample>.valSplit(): List<FlSample> = filter { it.label != null && SampleStore.isValidation(it.id) }
