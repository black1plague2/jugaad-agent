package com.jugaad.agent.fl

import kotlin.random.Random

/**
 * Pure batch-construction helpers for [VariantTrainer.train]'s per-variant recipe.
 * Kept free of [FlModel]/TFLite so [RecipeBatchTest] can exercise them directly.
 */
object RecipeBatching {

    /** Splits `samples` into fixed-size batches per the variant's recipe. */
    fun buildBatches(samples: List<FlSample>, spec: VariantSpec, batchSize: Int, random: Random): List<List<FlSample>> =
        if (spec.balanced) buildBalancedBatches(samples, batchSize, random) else buildShuffledPaddedBatches(samples, batchSize, random)

    /**
     * `balanced` recipe: each batch samples uniformly across the classes present, with
     * replacement. One slot per present class is filled first so every batch is
     * guaranteed to contain every present class; remaining slots pick a class uniformly
     * at random (with replacement) and a random row from it.
     */
    fun buildBalancedBatches(samples: List<FlSample>, batchSize: Int, random: Random): List<List<FlSample>> {
        val byClass = samples.filter { it.label != null }.groupBy { it.label!! }
        val classes = byClass.keys.toList()
        if (classes.isEmpty()) return emptyList()
        val numBatches = (samples.size + batchSize - 1) / batchSize
        return List(numBatches) {
            val batch = ArrayList<FlSample>(batchSize)
            for (c in classes) {
                if (batch.size >= batchSize) break
                batch += byClass[c]!!.random(random)
            }
            while (batch.size < batchSize) {
                val c = classes[random.nextInt(classes.size)]
                batch += byClass[c]!!.random(random)
            }
            batch
        }
    }

    /** v1 behaviour: shuffle, then pad a short last batch by repeating rows from within it. */
    fun buildShuffledPaddedBatches(samples: List<FlSample>, batchSize: Int, random: Random): List<List<FlSample>> {
        val shuffled = samples.shuffled(random)
        val batches = ArrayList<List<FlSample>>()
        var i = 0
        while (i < shuffled.size) {
            val end = (i + batchSize).coerceAtMost(shuffled.size)
            val chunk = shuffled.subList(i, end)
            batches += if (chunk.size == batchSize) chunk else List(batchSize) { idx -> chunk[idx % chunk.size] }
            i += batchSize
        }
        return batches
    }

    /** `noise` recipe: Gaussian input noise (training only) on a copy of `x`; never mutates it. */
    fun applyNoise(x: FloatArray, sigma: Float, random: java.util.Random): FloatArray =
        FloatArray(x.size) { i -> x[i] + (random.nextGaussian() * sigma).toFloat() }

    /** Extra weight added on top of `(1 - maxProb)` so even a fully-confident row can still be drawn — default matches `recipes.uncertainFloor` (0.1); callers pass the configured value explicitly. */
    private const val DEFAULT_UNCERTAIN_FLOOR = 0.1f

    /**
     * `focusUncertain` recipe: builds each batch by sampling rows with probability
     * proportional to `(1 - maxProb) + floor`, with replacement, where `probsById` is the
     * per-sample softmax computed from the model's weights at epoch start (founder table
     * row 3, uncertainty-triggered fine-tuning). A sample missing from `probsById` is
     * treated as maximally uncertain (weight `1 + floor`). [floor] is
     * `AppConfig.recipes.uncertainFloor` at the real call site ([VariantTrainer]).
     */
    fun buildUncertainBatches(
        samples: List<FlSample>,
        probsById: Map<String, FloatArray>,
        batchSize: Int,
        random: Random,
        floor: Float = DEFAULT_UNCERTAIN_FLOOR,
    ): List<List<FlSample>> {
        if (samples.isEmpty()) return emptyList()
        val weights = samples.map { s -> (1f - (probsById[s.id]?.maxOrNull() ?: 0f)) + floor }
        val totalWeight = weights.sum()
        val numBatches = (samples.size + batchSize - 1) / batchSize
        return List(numBatches) { List(batchSize) { weightedSample(samples, weights, totalWeight, random) } }
    }

    private fun weightedSample(samples: List<FlSample>, weights: List<Float>, totalWeight: Float, random: Random): FlSample {
        var r = random.nextFloat() * totalWeight
        for (i in samples.indices) {
            r -= weights[i]
            if (r <= 0f) return samples[i]
        }
        return samples.last()
    }

    /** One training row: model input plus its target distribution — one-hot for a labelled
     * sample, teacher-predicted soft probabilities for a distilled pending sample. */
    data class TrainingRow(val x: FloatArray, val y: FloatArray)

    /** Default matches `recipes.distillMinConf` (0.9); [VariantTrainer] passes the configured value explicitly. */
    private const val DISTILL_CONFIDENCE_THRESHOLD = 0.9f

    /**
     * `distill` recipe: every labelled sample gets a one-hot target; a pending sample
     * joins training only when the EMA teacher's max probability for it clears
     * [threshold] (`AppConfig.recipes.distillMinConf` at the real call site), with its
     * target set to the teacher's own probabilities (founder table row 1, EMA-teacher
     * self-distillation).
     */
    fun buildDistillRows(
        labelled: List<FlSample>,
        pending: List<FlSample>,
        teacherProbsById: Map<String, FloatArray>,
        numClasses: Int,
        threshold: Float = DISTILL_CONFIDENCE_THRESHOLD,
    ): List<TrainingRow> {
        val rows = ArrayList<TrainingRow>(labelled.size + pending.size)
        for (s in labelled) {
            val label = s.label ?: continue
            rows += TrainingRow(s.x, FloatArray(numClasses).also { it[label] = 1f })
        }
        for (s in pending) {
            val probs = teacherProbsById[s.id] ?: continue
            if ((probs.maxOrNull() ?: 0f) >= threshold) rows += TrainingRow(s.x, probs)
        }
        return rows
    }

    /** Shuffles+pads (v1 policy) a list of already-built training rows into fixed-size batches. */
    fun buildRowBatches(rows: List<TrainingRow>, batchSize: Int, random: Random): List<List<TrainingRow>> {
        val shuffled = rows.shuffled(random)
        val batches = ArrayList<List<TrainingRow>>()
        var i = 0
        while (i < shuffled.size) {
            val end = (i + batchSize).coerceAtMost(shuffled.size)
            val chunk = shuffled.subList(i, end)
            batches += if (chunk.size == batchSize) chunk else List(batchSize) { idx -> chunk[idx % chunk.size] }
            i += batchSize
        }
        return batches
    }
}
