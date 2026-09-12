package com.jugaad.agent.fl

/** MLP variants train a TFLite head; CENTROID has no graph at all (Decision 3, v3). */
enum class VariantKind { MLP, CENTROID }

/**
 * One trainable variant's architecture + training recipe. `layers` is the per-layer unit
 * count including input ([FlConstants.INPUT_DIM]) and output ([FlConstants.N_CLASSES]);
 * `weightCount` is `sum(W + b)` over consecutive layer pairs, row-major, matching the
 * exported `.tflite`'s layout — empty for [VariantKind.CENTROID], which instead stores
 * [FlConstants.N_CLASSES] centroids of [FlConstants.INPUT_DIM] floats each.
 *
 * `epochs` is retained from v2 for documentation only: [VariantTrainer.train] now always
 * runs the early-stopping loop (up to 30 epochs, or a fixed 10 with too little held-out
 * data) per Decision 4, so this field no longer drives the loop.
 */
data class VariantSpec(
    val id: String,
    val kind: VariantKind,
    val asset: String?,
    val layers: IntArray,
    val weightCount: Int,
    val epochs: Int,
    val noiseSigma: Float,
    val balanced: Boolean,
    val usesUnlabelled: Boolean,
    val earlyStop: Boolean,
    val weightDecayNote: String,
    val focusUncertain: Boolean,
    val selfDistill: Boolean,
    val label: String,
    val description: String,
) {
    init {
        val expected = when (kind) {
            VariantKind.MLP -> {
                var e = 0
                for (i in 0 until layers.size - 1) e += layers[i] * layers[i + 1] + layers[i + 1]
                e
            }
            VariantKind.CENTROID -> FlConstants.N_CLASSES * FlConstants.INPUT_DIM
        }
        require(expected == weightCount) {
            "VariantSpec $id: weightCount mismatch, expected $expected for kind $kind (layers ${layers.toList()}), got $weightCount"
        }
    }
}

/** Mirrors the Python variant table (v3 model spec). Champion at install is [CHAMPION_DEFAULT]. */
object FlVariants {
    const val CHAMPION_DEFAULT = "base"

    private const val BASE_ASSET = "models/fl_head_base.tflite"
    private val BASE_LAYERS = intArrayOf(FlConstants.INPUT_DIM, 64, 3)
    private val SMALL_LAYERS = intArrayOf(FlConstants.INPUT_DIM, 32, 3)
    private val DEEP_LAYERS = intArrayOf(FlConstants.INPUT_DIM, 64, 32, 3)

    /** `sum(W + b)` over consecutive layer pairs — kept symbolic in [FlConstants.INPUT_DIM] so this table never hardcodes a weight count. */
    private fun mlpWeights(layers: IntArray): Int {
        var w = 0
        for (i in 0 until layers.size - 1) w += layers[i] * layers[i + 1] + layers[i + 1]
        return w
    }

    val ALL: List<VariantSpec> = listOf(
        VariantSpec(
            id = "base", kind = VariantKind.MLP, asset = BASE_ASSET, layers = BASE_LAYERS,
            weightCount = mlpWeights(BASE_LAYERS), epochs = 30, noiseSigma = 0f, balanced = false,
            usesUnlabelled = false, earlyStop = true, weightDecayNote = "wd 1e-4",
            focusUncertain = false, selfDistill = false,
            label = "Base", description = "${FlConstants.INPUT_DIM}-64-3, natural class mix, early stop",
        ),
        VariantSpec(
            id = "small", kind = VariantKind.MLP, asset = "models/fl_head_small.tflite", layers = SMALL_LAYERS,
            weightCount = mlpWeights(SMALL_LAYERS), epochs = 30, noiseSigma = 0f, balanced = false,
            usesUnlabelled = false, earlyStop = true, weightDecayNote = "wd 1e-4",
            focusUncertain = false, selfDistill = false,
            label = "Small", description = "${FlConstants.INPUT_DIM}-32-3, lighter head",
        ),
        VariantSpec(
            id = "deep", kind = VariantKind.MLP, asset = "models/fl_head_deep.tflite", layers = DEEP_LAYERS,
            weightCount = mlpWeights(DEEP_LAYERS), epochs = 30, noiseSigma = 0f, balanced = false,
            usesUnlabelled = false, earlyStop = true, weightDecayNote = "wd 1e-4",
            focusUncertain = false, selfDistill = false,
            label = "Deep", description = "${FlConstants.INPUT_DIM}-64-32-3, extra hidden layer",
        ),
        VariantSpec(
            id = "noise", kind = VariantKind.MLP, asset = BASE_ASSET, layers = BASE_LAYERS,
            weightCount = mlpWeights(BASE_LAYERS), epochs = 30, noiseSigma = 0.15f, balanced = false,
            usesUnlabelled = false, earlyStop = true, weightDecayNote = "wd 1e-4",
            focusUncertain = false, selfDistill = false,
            label = "Noise", description = "${FlConstants.INPUT_DIM}-64-3, Gaussian input noise sigma 0.15",
        ),
        VariantSpec(
            id = "balanced", kind = VariantKind.MLP, asset = BASE_ASSET, layers = BASE_LAYERS,
            weightCount = mlpWeights(BASE_LAYERS), epochs = 30, noiseSigma = 0f, balanced = true,
            usesUnlabelled = false, earlyStop = true, weightDecayNote = "wd 1e-4",
            focusUncertain = false, selfDistill = false,
            label = "Balanced", description = "${FlConstants.INPUT_DIM}-64-3, class-balanced batches",
        ),
        VariantSpec(
            id = "uncertain", kind = VariantKind.MLP, asset = BASE_ASSET, layers = BASE_LAYERS,
            weightCount = mlpWeights(BASE_LAYERS), epochs = 30, noiseSigma = 0f, balanced = false,
            usesUnlabelled = false, earlyStop = true, weightDecayNote = "wd 1e-4",
            focusUncertain = true, selfDistill = false,
            label = "Uncertain", description = "${FlConstants.INPUT_DIM}-64-3, batches biased to low-confidence rows",
        ),
        VariantSpec(
            id = "distill", kind = VariantKind.MLP, asset = BASE_ASSET, layers = BASE_LAYERS,
            weightCount = mlpWeights(BASE_LAYERS), epochs = 30, noiseSigma = 0f, balanced = false,
            usesUnlabelled = true, earlyStop = true, weightDecayNote = "wd 1e-4",
            focusUncertain = false, selfDistill = true,
            label = "Distill", description = "${FlConstants.INPUT_DIM}-64-3, EMA-teacher self-distillation on pending samples",
        ),
        VariantSpec(
            id = "centroid", kind = VariantKind.CENTROID, asset = null, layers = intArrayOf(),
            weightCount = FlConstants.N_CLASSES * FlConstants.INPUT_DIM, epochs = 0, noiseSigma = 0f, balanced = false,
            usesUnlabelled = false, earlyStop = false, weightDecayNote = "none (closed-form mean)",
            focusUncertain = false, selfDistill = false,
            label = "Centroid", description = "per-class mean, softmax over distance, no TFLite graph",
        ),
    )

    fun byId(id: String): VariantSpec = ALL.first { it.id == id }

    /** Every variant except the install-time champion. */
    val challengers: List<VariantSpec> = ALL.filter { it.id != CHAMPION_DEFAULT }
}
