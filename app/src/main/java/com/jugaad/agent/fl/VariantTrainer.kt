package com.jugaad.agent.fl

import com.jugaad.agent.core.Logx
import com.jugaad.agent.core.config.ConfigStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.math.ln
import kotlin.random.Random

/** Clamp on a predicted class probability before taking its log (mean cross-entropy) — numerical-stability floor, not a tunable recipe scalar. */
private const val CE_PROB_FLOOR = 1e-7f

/**
 * True when every labelled sample in [trainSamples] shares the same class (D4): a cheap,
 * pure check so it can be exercised without a [FlModel] or [ConfigStore]. An empty list isn't
 * "degenerate", it's just nothing to train on yet, so it reports false.
 */
internal fun isSingleClassTrainingSet(trainSamples: List<FlSample>): Boolean {
    if (trainSamples.isEmpty()) return false
    val firstLabel = trainSamples.first().label
    return trainSamples.all { it.label == firstLabel }
}

/**
 * Owns one MLP [VariantSpec]'s weight lifecycle on this phone: load-or-init on startup,
 * local SGD training on labelled (and, for `distill`, teacher-confident pending) samples
 * per the variant's recipe with early stopping, held-out evaluation, and applying a
 * FedAvg-merged model after a sync round. [samplesProvider] is [FlRuntime.trainingSamples]
 * (own labelled + [SharedPool], v4 plan §4) rather than a raw [SampleStore], so
 * peer-contributed samples train this variant too; [pendingProvider] stays this device's
 * own unlabelled queue since pending (unlabelled) readings are never shared over the
 * network. Epoch caps, early-stop patience/floor and every recipe scalar (`noiseSigma`,
 * `distillAlpha`, `distillMinConf`, `uncertainFloor`) come from
 * [com.jugaad.agent.core.config.Training] / [com.jugaad.agent.core.config.Recipes]
 * (`AppConfig.training` / `.recipes`), read from [ConfigStore.effective] at call time
 * (v4 plan §1) — [VariantSpec] itself keeps only which recipe applies (architecture),
 * not its magnitude.
 */
class VariantTrainer(
    override val spec: VariantSpec,
    private val model: FlModel,
    private val samplesProvider: () -> List<FlSample>,
    private val pendingProvider: () -> List<FlSample>,
    dir: File,
) : Strategy {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val weightsFile = File(dir, "weights_${spec.id}.bin")
    private val metricsFile = File(dir, "metrics_${spec.id}.json")

    /** Fixed seed for the `noise` recipe's Gaussian input noise (Model spec v2). */
    private val noiseSeed = 7L

    init {
        val loaded = weightsFile.takeIf { it.exists() }
            ?.let { runCatching { WeightsCodec.decode(it.readBytes()) }.getOrNull() }
        if (loaded != null && loaded.size == spec.weightCount) {
            model.setWeights(loaded)
        } else {
            saveWeightsFile(model.getWeights())
        }
    }

    private val _metrics = MutableStateFlow(loadOrInitMetrics())
    override val metrics: StateFlow<VariantMetrics> = _metrics

    override suspend fun train(): VariantMetrics = withContext(Dispatchers.Default) {
        val cfg = ConfigStore.effective.value
        val samples = samplesProvider()
        val trainSamples = samples.trainSplit()
        if (trainSamples.isEmpty()) return@withContext _metrics.value
        val valSamples = samples.valSplit()

        // Degenerate single-class training set (D4): every label the same means trainAcc=1.0
        // for a model that only ever answers that one class, which reads as a real result on
        // the Sync/Performance tabs. We don't own VariantMetrics.kt to add a dedicated field,
        // and the founder is mid-way through collecting a second class so training must not be
        // blocked outright — so we surface it as a loud warning log alongside the metrics line
        // already emitted below, rather than silently reporting the 1.0.
        if (isSingleClassTrainingSet(trainSamples)) {
            Logx.w(
                "fl train[${spec.id}]: single-class training set (label=${trainSamples.first().label}, " +
                    "n=${trainSamples.size}) - trainAcc is not a meaningful signal until a second class is labelled",
            )
        }

        val t0 = System.currentTimeMillis()
        val useEarlyStop = valSamples.size >= cfg.training.minVal
        val maxEpochs = if (useEarlyStop) cfg.training.maxEpochs else cfg.training.fixedEpochs
        val patience = cfg.training.patience
        val distillAlpha = cfg.recipes.distillAlpha.toFloat()
        val distillMinConf = cfg.recipes.distillMinConf.toFloat()
        val uncertainFloor = cfg.recipes.uncertainFloor.toFloat()
        val noiseSigma = cfg.recipes.noiseSigma.toFloat()

        val noiseRandom = if (spec.noiseSigma > 0f) java.util.Random(noiseSeed) else null

        // `distill`: teacher starts as a copy of the student's initial weights, and is
        // updated by EMA after every epoch; pending (unlabelled) readings join with soft targets.
        var teacherWeights = if (spec.selfDistill) model.getWeights().copyOf() else null
        val pendingSamples = if (spec.selfDistill) pendingProvider() else emptyList<FlSample>()

        var esState = EarlyStopping.initial()
        var bestWeights = model.getWeights().copyOf()
        var lastEpochLoss = 0f

        for (epoch in 0 until maxEpochs) {
            lastEpochLoss = when {
                spec.selfDistill -> trainDistillEpoch(trainSamples, pendingSamples, teacherWeights!!, distillMinConf)
                spec.focusUncertain -> trainUncertainEpoch(trainSamples, noiseRandom, noiseSigma, uncertainFloor)
                else -> trainRecipeEpoch(trainSamples, noiseRandom, noiseSigma)
            }

            if (spec.selfDistill) {
                val student = model.getWeights()
                teacherWeights = FloatArray(student.size) { i ->
                    distillAlpha * teacherWeights!![i] + (1f - distillAlpha) * student[i]
                }
            }

            if (useEarlyStop) {
                val valLoss = meanCrossEntropy(valSamples)
                val (next, stop) = EarlyStopping.step(esState, epoch, valLoss, patience)
                esState = next
                if (esState.bestEpoch == epoch) bestWeights = model.getWeights().copyOf()
                if (stop) break
            }
        }

        if (useEarlyStop) model.setWeights(bestWeights)

        val trainAcc = evaluateTrain()
        val valAcc = evaluateVal()
        val trainMs = System.currentTimeMillis() - t0
        saveWeightsFile(model.getWeights())

        val updated = _metrics.value.copy(
            nTrain = trainSamples.size,
            nVal = valSamples.size,
            trainAcc = trainAcc,
            valAcc = valAcc,
            lastLoss = lastEpochLoss,
            lastTrainedMs = System.currentTimeMillis(),
            valLoss = if (useEarlyStop) esState.bestLoss else 0f,
            bestEpoch = if (useEarlyStop) esState.bestEpoch else maxEpochs - 1,
            gap = if (valAcc >= 0f) trainAcc - valAcc else 0f,
            trainMs = trainMs,
        )
        saveMetrics(updated)
        Logx.i(
            "fl train[${spec.id}]: n=${trainSamples.size} trainAcc=${"%.3f".format(trainAcc)} " +
                "valAcc=${"%.3f".format(valAcc)} bestEpoch=${updated.bestEpoch} trainMs=$trainMs",
        )
        updated
    }

    /** Held-out accuracy; -1 when there are fewer validation samples than [com.jugaad.agent.core.config.Training.minVal]. */
    override fun evaluateVal(w: FloatArray?): Float {
        val samples = samplesProvider().valSplit()
        if (samples.size < ConfigStore.effective.value.training.minVal) return -1f
        return evaluateOn(samples, w)
    }

    override fun evaluateTrain(w: FloatArray?): Float {
        val samples = samplesProvider().trainSplit()
        if (samples.isEmpty()) return 0f
        return evaluateOn(samples, w)
    }

    /** 3x3 confusion matrix on the held-out set with the current weights; rows = true, cols = predicted. */
    override fun confusionVal(): Array<IntArray> {
        val matrix = Array(FlConstants.N_CLASSES) { IntArray(FlConstants.N_CLASSES) }
        for (s in samplesProvider().valSplit()) {
            val trueLabel = s.label ?: continue
            val probs = model.infer(s.x)
            val predicted = probs.indices.maxByOrNull { probs[it] } ?: 0
            if (trueLabel in matrix.indices && predicted in matrix.indices) matrix[trueLabel][predicted]++
        }
        return matrix
    }

    override fun currentWeights(): FloatArray = model.getWeights()

    override fun applyMerged(w: FloatArray, newRound: Int) {
        model.setWeights(w)
        saveWeightsFile(w)
        saveMetrics(_metrics.value.copy(round = newRound))
    }

    override fun infer(x: FloatArray): FloatArray = model.infer(x)

    // --- Per-epoch training -------------------------------------------------

    private fun trainRecipeEpoch(trainSamples: List<FlSample>, noiseRandom: java.util.Random?, noiseSigma: Float): Float {
        val batches = RecipeBatching.buildBatches(trainSamples, spec, FlConstants.TRAIN_BATCH, Random.Default)
        return trainOnSampleBatches(batches, noiseRandom, noiseSigma)
    }

    private fun trainUncertainEpoch(trainSamples: List<FlSample>, noiseRandom: java.util.Random?, noiseSigma: Float, uncertainFloor: Float): Float {
        val probsById = trainSamples.associate { it.id to model.infer(it.x) }
        val batches = RecipeBatching.buildUncertainBatches(trainSamples, probsById, FlConstants.TRAIN_BATCH, Random.Default, uncertainFloor)
        return trainOnSampleBatches(batches, noiseRandom, noiseSigma)
    }

    private fun trainDistillEpoch(
        trainSamples: List<FlSample>,
        pendingSamples: List<FlSample>,
        teacherWeights: FloatArray,
        distillMinConf: Float,
    ): Float {
        val teacherProbsById = pendingSamples.associate { it.id to inferWithWeights(teacherWeights, it.x) }
        val rows = RecipeBatching.buildDistillRows(trainSamples, pendingSamples, teacherProbsById, FlConstants.N_CLASSES, distillMinConf)
        val batches = RecipeBatching.buildRowBatches(rows, FlConstants.TRAIN_BATCH, Random.Default)
        return trainOnRowBatches(batches)
    }

    private fun trainOnSampleBatches(batches: List<List<FlSample>>, noiseRandom: java.util.Random?, noiseSigma: Float): Float {
        if (batches.isEmpty()) return 0f
        var epochLoss = 0f
        for (batch in batches) {
            val xBatch = Array(FlConstants.TRAIN_BATCH) { idx ->
                val row = batch[idx].x
                if (noiseRandom != null) RecipeBatching.applyNoise(row, noiseSigma, noiseRandom) else row
            }
            val yBatch = Array(FlConstants.TRAIN_BATCH) { idx -> oneHot(batch[idx].label!!) }
            epochLoss += model.trainStep(xBatch, yBatch)
        }
        return epochLoss / batches.size
    }

    private fun trainOnRowBatches(batches: List<List<RecipeBatching.TrainingRow>>): Float {
        if (batches.isEmpty()) return 0f
        var epochLoss = 0f
        for (batch in batches) {
            val xBatch = Array(FlConstants.TRAIN_BATCH) { idx -> batch[idx].x }
            val yBatch = Array(FlConstants.TRAIN_BATCH) { idx -> batch[idx].y }
            epochLoss += model.trainStep(xBatch, yBatch)
        }
        return epochLoss / batches.size
    }

    private fun inferWithWeights(w: FloatArray, x: FloatArray): FloatArray {
        val previous = model.getWeights()
        model.setWeights(w)
        try {
            return model.infer(x)
        } finally {
            model.setWeights(previous)
        }
    }

    /** Mean cross-entropy of the current weights on [samples], clamping each predicted prob to [CE_PROB_FLOOR]. */
    private fun meanCrossEntropy(samples: List<FlSample>): Float {
        if (samples.isEmpty()) return 0f
        var sum = 0.0
        for (s in samples) {
            val label = s.label ?: continue
            val probs = model.infer(s.x)
            val p = probs.getOrElse(label) { 0f }.coerceAtLeast(CE_PROB_FLOOR)
            sum += -ln(p.toDouble())
        }
        return (sum / samples.size).toFloat()
    }

    private fun evaluateOn(samples: List<FlSample>, w: FloatArray?): Float {
        val previous = if (w != null) model.getWeights() else null
        if (w != null) model.setWeights(w)
        try {
            var correct = 0
            for (s in samples) {
                val probs = model.infer(s.x)
                val predicted = probs.indices.maxByOrNull { probs[it] } ?: 0
                if (predicted == s.label) correct++
            }
            return correct.toFloat() / samples.size
        } finally {
            if (previous != null) model.setWeights(previous)
        }
    }

    private fun oneHot(label: Int): FloatArray =
        FloatArray(FlConstants.N_CLASSES).also { it[label] = 1f }

    private fun saveWeightsFile(w: FloatArray) {
        weightsFile.parentFile?.mkdirs()
        weightsFile.writeBytes(WeightsCodec.encode(w))
    }

    private fun saveMetrics(m: VariantMetrics) {
        _metrics.value = m
        metricsFile.parentFile?.mkdirs()
        runCatching { metricsFile.writeText(json.encodeToString(m)) }
            .onFailure { Logx.w("VariantTrainer[${spec.id}]: failed to save metrics_${spec.id}.json", it) }
    }

    private fun loadOrInitMetrics(): VariantMetrics {
        val loaded = metricsFile.takeIf { it.exists() }
            ?.let { runCatching { json.decodeFromString<VariantMetrics>(it.readText()) }.getOrNull() }
        if (loaded != null) return loaded
        val fresh = VariantMetrics(
            variantId = spec.id,
            round = 0,
            nTrain = 0,
            nVal = 0,
            trainAcc = 0f,
            valAcc = -1f,
            lastLoss = 0f,
            lastTrainedMs = 0L,
            valLoss = 0f,
            bestEpoch = 0,
            gap = 0f,
            trainMs = 0L,
        )
        metricsFile.parentFile?.mkdirs()
        runCatching { metricsFile.writeText(json.encodeToString(fresh)) }
        return fresh
    }
}
