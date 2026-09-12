package com.jugaad.agent.ml.diagnosis

import com.jugaad.agent.core.Constants
import com.jugaad.agent.core.config.AppConfig
import com.jugaad.agent.domain.model.Baseline
import com.jugaad.agent.ml.FeatureExtractor
import com.jugaad.agent.ml.anomaly.AnomalyScorer
import com.jugaad.agent.ml.classifier.FaultClassifier
import com.jugaad.agent.ml.signal.MelFilterBank
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Fixed vocabulary of metric names a [com.jugaad.agent.core.config.Condition] may
 * reference. Produced only by [EvidenceExtractor], scored only by [RulesEngine].
 */
typealias Evidence = Map<String, Double>

/**
 * Turns one diagnose run into the fixed-vocabulary [Evidence] the catalogue's fault
 * rules score against. Band groupings and mains-line frequencies come from
 * [AppConfig.features] — never a literal Hz in this file; band centre frequencies
 * come from [MelFilterBank.bandCenterHz] (same filter-bank geometry as [AnomalyScorer]).
 */
object EvidenceExtractor {

    /** A band "at" a configured line frequency if its centre is within this many Hz of it. */
    private const val LINE_HUM_TOLERANCE_HZ = 6.0

    private val melBank = MelFilterBank(
        sr = Constants.SAMPLE_RATE_HZ,
        nFft = Constants.N_FFT,
        nMels = Constants.N_MELS,
        fMin = Constants.MEL_FMIN_HZ,
        fMax = Constants.MEL_FMAX_HZ,
    )

    fun extract(
        analysis: FeatureExtractor.Analysis,
        baseline: Baseline,
        anomaly: AnomalyScorer.Result,
        pred: FaultClassifier.Prediction?,
        cfg: AppConfig,
    ): Evidence {
        val nMels = Constants.N_MELS
        // feature/baseline layout: [per-band mean (nMels) || per-band std (nMels)].
        val meanDelta = DoubleArray(nMels) { (analysis.feature[it] - baseline.meanFeature[it]).toDouble() }
        val stdDelta = DoubleArray(nMels) { (analysis.feature[nMels + it] - baseline.meanFeature[nMels + it]).toDouble() }

        val groups = cfg.features.bandGroups
        val broadbandDelta = meanDelta.average()
        val temporalVariability = (stdDelta.average() / 2.0).coerceIn(0.0, 1.0)

        return mapOf(
            "lowBandDelta" to meanDeltaInRange(meanDelta, groups.low),
            "midBandDelta" to meanDeltaInRange(meanDelta, groups.mid),
            "highBandDelta" to meanDeltaInRange(meanDelta, groups.high),
            "veryHighBandDelta" to meanDeltaInRange(meanDelta, groups.veryHigh),
            "broadbandDelta" to broadbandDelta,
            "spectralSpread" to stdOf(meanDelta),
            "peakiness" to ((meanDelta.maxOrNull() ?: 0.0) - broadbandDelta),
            "dominantHz" to anomaly.dominantDivergenceHz,
            "lineHumDelta" to meanDeltaNearLines(meanDelta, cfg.features.lineHz),
            "temporalVariability" to temporalVariability,
            "imuDelta" to (analysis.imuIndex - baseline.imuIndexMean),
            "gyroDelta" to (analysis.gyroIndex - baseline.gyroIndexMean),
            "magDelta" to (analysis.magIndex - baseline.magIndexMean),
            "magRmsDelta" to (analysis.magRms - baseline.magRmsMean),
            "anomalyScore" to anomaly.score,
            "cnnClass" to (pred?.faultClass?.index?.toDouble() ?: -1.0),
            "cnnConfidence" to (pred?.confidence?.toDouble() ?: 0.0),
        )
    }

    /** Mean per-band delta over the bands whose centre falls in `[range[0], range[1]]` Hz. */
    private fun meanDeltaInRange(delta: DoubleArray, range: List<Double>): Double {
        if (range.size < 2) return 0.0
        val lo = range[0]; val hi = range[1]
        val bands = melBank.bandCenterHz.indices.filter { melBank.bandCenterHz[it] in lo..hi }
        return meanAt(bands, delta)
    }

    /** Mean per-band delta over the bands whose centre sits within [LINE_HUM_TOLERANCE_HZ] of any configured line Hz. */
    private fun meanDeltaNearLines(delta: DoubleArray, lineHz: List<Double>): Double {
        if (lineHz.isEmpty()) return 0.0
        val bands = melBank.bandCenterHz.indices.filter { b ->
            lineHz.any { hz -> abs(melBank.bandCenterHz[b] - hz) <= LINE_HUM_TOLERANCE_HZ }
        }
        return meanAt(bands, delta)
    }

    private fun meanAt(bandIndices: List<Int>, delta: DoubleArray): Double =
        if (bandIndices.isEmpty()) 0.0 else bandIndices.sumOf { delta[it] } / bandIndices.size

    private fun stdOf(values: DoubleArray): Double {
        if (values.isEmpty()) return 0.0
        val mean = values.average()
        val variance = values.sumOf { (it - mean) * (it - mean) } / values.size
        return sqrt(variance)
    }
}
