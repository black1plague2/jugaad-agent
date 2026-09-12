package com.jugaad.agent.fl

import com.jugaad.agent.core.Logx
import com.jugaad.agent.core.config.ConfigStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.math.exp

/**
 * Frozen-backbone centroid adaptation (founder table row 4, v3): weights are just the
 * per-class mean of the delta-feature vector over this phone's training samples — no
 * TFLite graph, no gradients. Inference is softmax over the negative squared distance
 * to each centroid. FedAvg of centroids weighted by nTrain is exact for per-class
 * means, so this variant merges through [FedAvg.merge] like any other. [samplesProvider]
 * is [FlRuntime.trainingSamples] (own labelled + [SharedPool], v4 plan §4) rather than a
 * raw [SampleStore] so peer-contributed samples train this variant too.
 */
class CentroidStrategy(
    override val spec: VariantSpec,
    private val samplesProvider: () -> List<FlSample>,
    dir: File,
) : Strategy {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val weightsFile = File(dir, "weights_${spec.id}.bin")
    private val metricsFile = File(dir, "metrics_${spec.id}.json")
    // D11 fix: which classes have ever received a real training signal (locally or via a peer
    // merge). Persisted the same way as [centroids] -- a little-endian float file in the same
    // [dir], via the same [WeightsCodec] already used for weights_<id>.bin -- rather than a new
    // file format, so it survives process restart exactly like the centroids do. A plain "is the
    // centroid all-zero" check was tried first and rejected: CentroidMathTest legitimately trains
    // a class whose true per-dim mean is exactly 0f, which would be misread as "never trained".
    private val trainedFile = File(dir, "trained_${spec.id}.bin")
    private val dim = FlConstants.INPUT_DIM

    private var centroids: FloatArray = loadOrInitCentroids()
    private var trainedMask: BooleanArray = loadOrInitTrainedMask()

    private val _metrics = MutableStateFlow(loadOrInitMetrics())
    override val metrics: StateFlow<VariantMetrics> = _metrics

    override fun infer(x: FloatArray): FloatArray = classify(x, centroids)

    override suspend fun train(): VariantMetrics {
        val t0 = System.currentTimeMillis()
        val samples = samplesProvider()
        val trainSamples = samples.trainSplit()
        if (trainSamples.isEmpty()) return _metrics.value

        val sums = Array(FlConstants.N_CLASSES) { FloatArray(dim) }
        val counts = IntArray(FlConstants.N_CLASSES)
        for (s in trainSamples) {
            val c = s.label ?: continue
            if (c !in 0 until FlConstants.N_CLASSES) continue
            counts[c]++
            for (i in 0 until dim) sums[c][i] += s.x[i]
        }

        val updated = centroids.copyOf()
        for (c in 0 until FlConstants.N_CLASSES) {
            if (counts[c] == 0) continue // no samples this class this round -> keep the previous centroid
            val base = c * dim
            for (i in 0 until dim) updated[base + i] = sums[c][i] / counts[c]
            trainedMask[c] = true // this class just received a real (possibly all-zero-mean) sample mean
        }
        centroids = updated
        saveWeightsFile(centroids)
        saveTrainedMask()

        val trainAcc = evaluateTrain()
        val valAcc = evaluateVal()
        val trainMs = System.currentTimeMillis() - t0
        val result = _metrics.value.copy(
            nTrain = trainSamples.size,
            nVal = samples.valSplit().size,
            trainAcc = trainAcc,
            valAcc = valAcc,
            lastLoss = 0f,
            lastTrainedMs = System.currentTimeMillis(),
            valLoss = 0f,
            bestEpoch = 0,
            gap = if (valAcc >= 0f) trainAcc - valAcc else 0f,
            trainMs = trainMs,
        )
        saveMetrics(result)
        Logx.i(
            "fl train[${spec.id}]: n=${trainSamples.size} trainAcc=${"%.3f".format(trainAcc)} " +
                "valAcc=${"%.3f".format(valAcc)} trainMs=$trainMs",
        )
        return result
    }

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

    override fun confusionVal(): Array<IntArray> {
        val matrix = Array(FlConstants.N_CLASSES) { IntArray(FlConstants.N_CLASSES) }
        for (s in samplesProvider().valSplit()) {
            val trueLabel = s.label ?: continue
            val predicted = predict(s.x, centroids)
            if (trueLabel in matrix.indices && predicted in matrix.indices) matrix[trueLabel][predicted]++
        }
        return matrix
    }

    override fun currentWeights(): FloatArray = centroids.copyOf()

    override fun applyMerged(w: FloatArray, newRound: Int) {
        require(w.size == spec.weightCount) {
            "CentroidStrategy.applyMerged: expected ${spec.weightCount} weights, got ${w.size}"
        }
        // FedAvg carries no separate "which classes did peers train" signal, only the merged
        // centroid floats -- so a class trained only on a peer is recognized here by its merged
        // centroid being non-zero. This can't misfire the way it would inside predict/classify:
        // FedAvg.merge only ever writes into a class's slot when at least one contributor had
        // nTrain > 0 for it, so a non-zero result here really does mean somebody trained it.
        for (c in 0 until FlConstants.N_CLASSES) {
            if (trainedMask[c]) continue
            val base = c * dim
            for (i in 0 until dim) if (w[base + i] != 0f) { trainedMask[c] = true; break }
        }
        centroids = w.copyOf()
        saveWeightsFile(centroids)
        saveTrainedMask()
        saveMetrics(_metrics.value.copy(round = newRound))
    }

    private fun evaluateOn(samples: List<FlSample>, w: FloatArray?): Float {
        val use = w ?: centroids
        var correct = 0
        for (s in samples) if (predict(s.x, use) == s.label) correct++
        return correct.toFloat() / samples.size
    }

    private fun classify(x: FloatArray, c: FloatArray): FloatArray {
        // D11 fix: an untrained class is never a prediction candidate -- its stale/initial
        // all-zero centroid otherwise sits right on top of a healthy (near-origin) sample in
        // this baseline-relative feature space.
        val trained = (0 until FlConstants.N_CLASSES).filter { trainedMask[it] }
        if (trained.isEmpty()) {
            // No class has ever been trained (locally or via a peer merge): there is no signal
            // to put probability mass behind. Return an all-zero distribution rather than crash
            // or divide by zero in softmax.
            return FloatArray(FlConstants.N_CLASSES)
        }
        val logits = FloatArray(FlConstants.N_CLASSES) { cls ->
            if (cls in trained) -0.5f * squaredDistance(x, c, cls) / dim else Float.NEGATIVE_INFINITY
        }
        return softmax(logits)
    }

    private fun predict(x: FloatArray, c: FloatArray): Int {
        var best = -1
        var bestDist = Float.MAX_VALUE
        for (cls in 0 until FlConstants.N_CLASSES) {
            if (!trainedMask[cls]) continue // untrained classes are never prediction candidates
            val d = squaredDistance(x, c, cls)
            if (d < bestDist) {
                bestDist = d
                best = cls
            }
        }
        return best // -1 when no class has ever been trained; never a valid label, safe everywhere it's compared
    }

    private fun squaredDistance(x: FloatArray, c: FloatArray, cls: Int): Float {
        var sum = 0f
        val base = cls * dim
        for (i in 0 until dim) {
            val d = x[i] - c[base + i]
            sum += d * d
        }
        return sum
    }

    private fun softmax(logits: FloatArray): FloatArray {
        val max = logits.max()
        val exps = FloatArray(logits.size) { i -> exp((logits[i] - max).toDouble()).toFloat() }
        val sum = exps.sum()
        return FloatArray(logits.size) { i -> exps[i] / sum }
    }

    private fun saveWeightsFile(w: FloatArray) {
        weightsFile.parentFile?.mkdirs()
        weightsFile.writeBytes(WeightsCodec.encode(w))
    }

    private fun saveMetrics(m: VariantMetrics) {
        _metrics.value = m
        metricsFile.parentFile?.mkdirs()
        runCatching { metricsFile.writeText(json.encodeToString(m)) }
            .onFailure { Logx.w("CentroidStrategy[${spec.id}]: failed to save metrics_${spec.id}.json", it) }
    }

    private fun loadOrInitCentroids(): FloatArray {
        val loaded = weightsFile.takeIf { it.exists() }
            ?.let { runCatching { WeightsCodec.decode(it.readBytes()) }.getOrNull() }
        return if (loaded != null && loaded.size == spec.weightCount) loaded else FloatArray(spec.weightCount)
    }

    private fun loadOrInitTrainedMask(): BooleanArray {
        val loaded = trainedFile.takeIf { it.exists() }
            ?.let { runCatching { WeightsCodec.decode(it.readBytes()) }.getOrNull() }
        return if (loaded != null && loaded.size == FlConstants.N_CLASSES) {
            BooleanArray(FlConstants.N_CLASSES) { loaded[it] != 0f }
        } else {
            BooleanArray(FlConstants.N_CLASSES) // fresh strategy: nothing trained yet, none crash-worthy
        }
    }

    private fun saveTrainedMask() {
        trainedFile.parentFile?.mkdirs()
        val asFloats = FloatArray(FlConstants.N_CLASSES) { if (trainedMask[it]) 1f else 0f }
        trainedFile.writeBytes(WeightsCodec.encode(asFloats))
    }

    private fun loadOrInitMetrics(): VariantMetrics {
        val loaded = metricsFile.takeIf { it.exists() }
            ?.let { runCatching { json.decodeFromString<VariantMetrics>(it.readText()) }.getOrNull() }
        if (loaded != null) return loaded
        val fresh = VariantMetrics(
            variantId = spec.id, round = 0, nTrain = 0, nVal = 0, trainAcc = 0f, valAcc = -1f,
            lastLoss = 0f, lastTrainedMs = 0L, valLoss = 0f, bestEpoch = 0, gap = 0f, trainMs = 0L,
        )
        metricsFile.parentFile?.mkdirs()
        runCatching { metricsFile.writeText(json.encodeToString(fresh)) }
        return fresh
    }
}
