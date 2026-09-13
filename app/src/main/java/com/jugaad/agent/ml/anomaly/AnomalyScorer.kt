package com.jugaad.agent.ml.anomaly

import com.jugaad.agent.core.Constants
import com.jugaad.agent.core.config.AppConfig
import com.jugaad.agent.domain.model.MachineStatus
import com.jugaad.agent.ml.signal.MelFilterBank
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * MUST-HAVE decision path.
 *
 * Baseline = mean of the K healthy feature vectors.
 * Spread   = mean cosine distance of each baseline vector to that mean (the
 *            "radius" of the healthy cluster), floored so identical clips don't
 *            make the score explode.
 * Score    = cosineDistance(x, baselineMean) / spread          (a robust z-like ratio)
 *
 * Thresholds T1 = 2, T2 = 4 then bucket the score into Healthy / Warning / Critical.
 * This runs with ZERO ML dependencies — it is the offline fallback that always works.
 */
class AnomalyScorer(
    private val melBank: MelFilterBank = MelFilterBank(
        sr = Constants.SAMPLE_RATE_HZ,
        nFft = Constants.N_FFT,
        nMels = Constants.N_MELS,
        fMin = Constants.MEL_FMIN_HZ,
        fMax = Constants.MEL_FMAX_HZ,
    ),
) {
    /** Noise floor for the healthy-cluster radius (~2 % cosine distance). */
    private val spreadFloor = SPREAD_FLOOR_BASE

    data class BaselineStats(
        val mean: FloatArray,
        val spread: Double,
        val rawStd: Double,
        val clipCount: Int,
        /** Population std of the healthy accel/gyro/mag/magRms indices — see [com.jugaad.agent.domain.model.Baseline]. */
        val imuIndexStd: Double = 0.0,
        val gyroIndexStd: Double = 0.0,
        val magIndexStd: Double = 0.0,
        val magRmsStd: Double = 0.0,
    )

    data class Result(
        val score: Double,
        val cosineDistance: Double,
        val spread: Double,
        val status: MachineStatus,
        val dominantDivergenceHz: Double,
        val topBands: List<BandDelta>,
        /** Acoustic-only component of [score] (cosine distance / spread). */
        val acousticScore: Double = 0.0,
        /** Sensor-z component of [score] — `sensorScoreScale * max_s(zWeight_s * z_s)`. */
        val sensorScore: Double = 0.0,
        /** Which side drove [score]: "acoustic", "accel", "gyro", "mag" or "magRms". */
        val dominantSource: String = "acoustic",
    )

    data class BandDelta(val hz: Double, val delta: Double)

    fun buildBaseline(features: List<FloatArray>, floor: Double = spreadFloor): BaselineStats {
        require(features.isNotEmpty()) { "need at least one baseline clip" }
        val dim = features.first().size
        val mean = FloatArray(dim)
        for (f in features) {
            require(f.size == dim) { "ragged baseline feature vectors" }
            for (i in 0 until dim) mean[i] += f[i]
        }
        for (i in 0 until dim) mean[i] /= features.size

        val dists = features.map { cosineDistance(it, mean) }
        val radius = dists.average()
        val std = if (dists.size > 1) {
            val m = dists.average()
            sqrt(dists.sumOf { (it - m) * (it - m) } / dists.size)
        } else 0.0

        return BaselineStats(
            mean = mean,
            spread = radius.coerceAtLeast(floor),
            rawStd = std,
            clipCount = features.size,
        )
    }

    /** Acoustic-only overload — existing callers keep working with no sensor contribution. */
    fun score(
        diagnosisFeature: FloatArray,
        baseline: BaselineStats,
        thresholds: Thresholds = Thresholds.DEFAULT,
    ): Result = score(diagnosisFeature, baseline, thresholds, ZERO_SENSOR_DELTAS, AppConfig())

    /**
     * Sensor-aware score: `z_s = |delta_s| / max(std_s, cfg.sensors.zFloor)` for each of
     * accel/gyro/mag/magRms (that order in [sensorDeltas]), `sensorScore = sensorScoreScale *
     * max_s(zWeight_s * z_s)`, final `score = max(acousticScore, sensorScore)`.
     */
    fun score(
        diagnosisFeature: FloatArray,
        baseline: BaselineStats,
        thresholds: Thresholds,
        sensorDeltas: DoubleArray,
        cfg: AppConfig,
    ): Result {
        val cd = cosineDistance(diagnosisFeature, baseline.mean)
        val acousticScore = cd / baseline.spread

        val zFloor = cfg.sensors.zFloor
        val weights = doubleArrayOf(
            cfg.sensors.zWeights.accel,
            cfg.sensors.zWeights.gyro,
            cfg.sensors.zWeights.mag,
            cfg.sensors.zWeights.magRms,
        )
        val stds = doubleArrayOf(baseline.imuIndexStd, baseline.gyroIndexStd, baseline.magIndexStd, baseline.magRmsStd)

        var bestWeighted = 0.0
        var bestIdx = -1
        for (i in 0 until minOf(sensorDeltas.size, SENSOR_NAMES.size)) {
            val z = abs(sensorDeltas[i]) / maxOf(stds[i], zFloor)
            val weighted = weights[i] * z
            if (weighted > bestWeighted) {
                bestWeighted = weighted
                bestIdx = i
            }
        }
        val sensorScore = cfg.sensors.sensorScoreScale * bestWeighted

        val score = maxOf(acousticScore, sensorScore)
        val dominantSource = if (bestIdx < 0 || acousticScore >= sensorScore) "acoustic" else SENSOR_NAMES[bestIdx]
        val status = MachineStatus.fromScore(score, thresholds.t1, thresholds.t2)

        val bands = topDivergentBands(diagnosisFeature, baseline.mean, k = 3)
        return Result(
            score = score,
            cosineDistance = cd,
            spread = baseline.spread,
            status = status,
            dominantDivergenceHz = bands.firstOrNull()?.hz ?: 0.0,
            topBands = bands,
            acousticScore = acousticScore,
            sensorScore = sensorScore,
            dominantSource = dominantSource,
        )
    }

    /**
     * Which mel bands gained the most energy vs baseline. The first 128 entries of
     * the feature vector are the per-band log-mel means, so a positive delta there
     * is "more energy at this frequency than when healthy".
     */
    private fun topDivergentBands(x: FloatArray, baseMean: FloatArray, k: Int): List<BandDelta> {
        val nB = Constants.N_MELS
        val deltas = ArrayList<BandDelta>(nB)
        for (b in 0 until nB) {
            deltas.add(BandDelta(melBank.bandCenterHz[b], (x[b] - baseMean[b]).toDouble()))
        }
        return deltas.sortedByDescending { it.delta }.take(k)
    }

    private fun cosineDistance(a: FloatArray, b: FloatArray): Double {
        var dot = 0.0; var na = 0.0; var nb = 0.0
        for (i in a.indices) {
            dot += a[i].toDouble() * b[i]
            na += a[i].toDouble() * a[i]
            nb += b[i].toDouble() * b[i]
        }
        if (na == 0.0 || nb == 0.0) return 1.0
        val cos = (dot / (sqrt(na) * sqrt(nb))).coerceIn(-1.0, 1.0)
        return 1.0 - cos
    }

    companion object {
        /** Sensor order shared by [BaselineStats]'s std fields, [score]'s sensorDeltas and cfg.sensors.zWeights. */
        private val SENSOR_NAMES = arrayOf("accel", "gyro", "mag", "magRms")
        private val ZERO_SENSOR_DELTAS = DoubleArray(SENSOR_NAMES.size)

        /** Global noise floor for the healthy-cluster radius. Sensitivity does NOT scale this —
         *  only the t1/t2 thresholds and calibration multipliers scale with a per-asset
         *  [com.jugaad.agent.domain.model.Sensitivity.factor]; scaling this floor too would
         *  divide the score by a shrinking denominator on top of falling thresholds. */
        const val SPREAD_FLOOR_BASE = 0.02
    }
}
