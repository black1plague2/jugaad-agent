package com.jugaad.agent.ml.diagnosis

import com.jugaad.agent.core.Constants
import com.jugaad.agent.core.config.AppConfig
import com.jugaad.agent.domain.model.Baseline
import com.jugaad.agent.domain.model.MachineStatus
import com.jugaad.agent.ml.FeatureExtractor
import com.jugaad.agent.ml.anomaly.AnomalyScorer
import com.jugaad.agent.ml.signal.LogMel
import com.jugaad.agent.ml.signal.MelFilterBank
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class EvidenceExtractorTest {

    private val nMels = Constants.N_MELS
    private val melBank = MelFilterBank(
        sr = Constants.SAMPLE_RATE_HZ,
        nFft = Constants.N_FFT,
        nMels = Constants.N_MELS,
        fMin = Constants.MEL_FMIN_HZ,
        fMax = Constants.MEL_FMAX_HZ,
    )
    private val cfg = AppConfig()

    private fun baseline(meanFeature: FloatArray) = Baseline(
        assetId = "a1",
        capturedAtMs = 0L,
        meanFeature = meanFeature,
        spread = 0.1,
        rawStd = 0.05,
        imuIndexMean = 0.2,
        clipCount = 3,
    )

    private fun analysis(feature: FloatArray) = FeatureExtractor.Analysis(
        logMel = LogMel(FloatArray(0), nMels, 0),
        feature = feature,
        imuIndex = 0.2,
        imuDominantHz = 0.0,
        gyroIndex = 0.0,
        magIndex = 0.0,
        magRms = 0.0,
    )

    private fun anomaly(score: Double = 1.0, dominantHz: Double = 123.0) = AnomalyScorer.Result(
        score = score,
        cosineDistance = 0.1,
        spread = 0.1,
        status = MachineStatus.WARNING,
        dominantDivergenceHz = dominantHz,
        topBands = emptyList(),
    )

    @Test
    fun risingLowBandsProduceHighLowBandDeltaAndNearZeroElsewhere() {
        val base = FloatArray(256)
        val feature = FloatArray(256)
        val low = cfg.features.bandGroups.low
        for (b in 0 until nMels) {
            if (melBank.bandCenterHz[b] in low[0]..low[1]) feature[b] = 2.0f
        }

        val evidence = EvidenceExtractor.extract(analysis(feature), baseline(base), anomaly(), null, cfg)

        assertTrue("lowBandDelta should be well above zero, was ${evidence["lowBandDelta"]}", evidence["lowBandDelta"]!! > 1.0)
        assertEquals(0.0, evidence["midBandDelta"]!!, 1e-9)
        assertEquals(0.0, evidence["highBandDelta"]!!, 1e-9)
        assertEquals(0.0, evidence["veryHighBandDelta"]!!, 1e-9)
    }

    @Test
    fun lineHumIsolatesBandsNearConfiguredLineFrequencies() {
        val base = FloatArray(256)
        val feature = FloatArray(256)
        for (b in 0 until nMels) {
            val hz = melBank.bandCenterHz[b]
            if (cfg.features.lineHz.any { abs(hz - it) <= 6.0 }) feature[b] = 3.0f
        }

        val evidence = EvidenceExtractor.extract(analysis(feature), baseline(base), anomaly(), null, cfg)

        assertTrue("lineHumDelta should pick up the isolated bump", evidence["lineHumDelta"]!! > 1.0)
        assertTrue("broadband average dilutes the same bump", evidence["broadbandDelta"]!! < evidence["lineHumDelta"]!!)
    }

    @Test
    fun temporalVariabilityComesFromTheStdHalfOfTheFeatureVector() {
        val base = FloatArray(256) // mean half + std half both zero
        val feature = FloatArray(256)
        for (b in 0 until nMels) feature[nMels + b] = 1.0f // std half raised uniformly, mean half untouched

        val evidence = EvidenceExtractor.extract(analysis(feature), baseline(base), anomaly(), null, cfg)

        assertEquals(0.0, evidence["broadbandDelta"]!!, 1e-9)
        // mean std delta of 1.0, divided by 2 and clamped to [0, 1] -> 0.5
        assertEquals(0.5, evidence["temporalVariability"]!!, 1e-9)
    }

    @Test
    fun anomalyAndCnnFieldsPassThroughDirectly() {
        val evidence = EvidenceExtractor.extract(
            analysis(FloatArray(256)), baseline(FloatArray(256)), anomaly(score = 2.5, dominantHz = 77.0), null, cfg,
        )

        assertEquals(2.5, evidence["anomalyScore"]!!, 1e-9)
        assertEquals(77.0, evidence["dominantHz"]!!, 1e-9)
        assertEquals(-1.0, evidence["cnnClass"]!!, 1e-9) // no prediction -> -1
        assertEquals(0.0, evidence["cnnConfidence"]!!, 1e-9)
    }
}
