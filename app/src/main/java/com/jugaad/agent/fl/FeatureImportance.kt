package com.jugaad.agent.fl

import com.jugaad.agent.core.Constants
import com.jugaad.agent.core.config.AppConfig
import com.jugaad.agent.core.config.BandGroups
import com.jugaad.agent.ml.signal.MelFilterBank
import kotlin.math.sqrt

/**
 * "What the champion listens to" (v4 plan §6): the L2 norm of the first-layer weights
 * touching each of the 260 input dims, grouped into the same acoustic band groups
 * [com.jugaad.agent.ml.diagnosis.EvidenceExtractor] scores evidence against (dims
 * 0..127 = per-band mean, 128..255 = per-band std, both halves folding into their
 * band's group) plus the four sensor deltas (dims 256..259), normalised to shares that
 * sum to ~100.
 */
object FeatureImportance {
    data class Group(val name: String, val share: Float)

    private val melBank = MelFilterBank(
        sr = Constants.SAMPLE_RATE_HZ,
        nFft = Constants.N_FFT,
        nMels = Constants.N_MELS,
        fMin = Constants.MEL_FMIN_HZ,
        fMax = Constants.MEL_FMAX_HZ,
    )

    fun compute(w: FloatArray, spec: VariantSpec, cfg: AppConfig): List<Group> {
        val perDim = perDimMagnitude(w, spec)
        val nMels = Constants.N_MELS
        val sums = LinkedHashMap<String, Float>()

        for (i in 0 until nMels) {
            val band = bandOf(melBank.bandCenterHz[i], cfg.features.bandGroups)
            sums[band] = (sums[band] ?: 0f) + perDim[i] + perDim[nMels + i]
        }
        val sensorBase = 2 * nMels
        sums["accel"] = perDim.getOrElse(sensorBase) { 0f }
        sums["gyro"] = perDim.getOrElse(sensorBase + 1) { 0f }
        sums["mag"] = perDim.getOrElse(sensorBase + 2) { 0f }
        sums["magRms"] = perDim.getOrElse(sensorBase + 3) { 0f }

        val total = sums.values.sum()
        if (total <= 0f) return sums.map { (name, _) -> Group(name, 0f) }
        return sums.entries
            .map { (name, v) -> Group(name, 100f * v / total) }
            .sortedByDescending { it.share }
    }

    /** L2 norm of the weights touching each input dim; length [FlConstants.INPUT_DIM]. */
    private fun perDimMagnitude(w: FloatArray, spec: VariantSpec): FloatArray {
        val dim = FlConstants.INPUT_DIM
        val out = FloatArray(dim)
        when (spec.kind) {
            VariantKind.MLP -> {
                val hidden = spec.layers.getOrElse(1) { 0 }
                if (hidden <= 0) return out
                for (i in 0 until dim) {
                    var sumSq = 0f
                    val base = i * hidden
                    for (j in 0 until hidden) {
                        val v = w.getOrElse(base + j) { 0f }
                        sumSq += v * v
                    }
                    out[i] = sqrt(sumSq)
                }
            }
            VariantKind.CENTROID -> {
                for (i in 0 until dim) {
                    var sumSq = 0f
                    for (c in 0 until FlConstants.N_CLASSES) {
                        val v = w.getOrElse(c * dim + i) { 0f }
                        sumSq += v * v
                    }
                    out[i] = sqrt(sumSq)
                }
            }
        }
        return out
    }

    private fun bandOf(hz: Double, bg: BandGroups): String = when {
        hz <= bg.low.getOrElse(1) { 150.0 } -> "low"
        hz <= bg.mid.getOrElse(1) { 1200.0 } -> "mid"
        hz <= bg.high.getOrElse(1) { 6000.0 } -> "high"
        else -> "veryHigh"
    }
}
