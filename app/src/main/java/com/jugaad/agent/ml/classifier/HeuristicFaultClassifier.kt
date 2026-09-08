package com.jugaad.agent.ml.classifier

import com.jugaad.agent.core.Constants
import com.jugaad.agent.domain.model.FaultClass
import com.jugaad.agent.domain.model.InferenceBackend
import com.jugaad.agent.ml.signal.MelFilterBank
import kotlin.math.exp

/**
 * Deterministic, dependency-free fallback classifier.
 *
 * Not a neural net — it splits the log-mel energy into physically meaningful
 * bands and applies simple rules so the demo always has a plausible label:
 *
 *   - Rotor imbalance      -> excess energy in the low band (~10-120 Hz), where a
 *                             once-per-revolution unbalance force shows up.
 *   - Airflow obstruction  -> excess broadband energy in the mid/high band
 *                             (~1-6 kHz), the "rushing / whistling" of a blocked
 *                             filter or duct.
 *
 * When [ExecuTorchFaultClassifier] loads a real .pte this class is not used.
 */
class HeuristicFaultClassifier(
    private val melBank: MelFilterBank = MelFilterBank(
        sr = Constants.SAMPLE_RATE_HZ,
        nFft = Constants.N_FFT,
        nMels = Constants.N_MELS,
        fMin = Constants.MEL_FMIN_HZ,
        fMax = Constants.MEL_FMAX_HZ,
    ),
) : FaultClassifier {

    override val isReady = true
    override val backend = InferenceBackend.NONE

    override fun classify(logMel: FloatArray): FaultClassifier.Prediction {
        val t0 = System.nanoTime()
        val nMels = Constants.N_MELS
        val frames = Constants.SPEC_FRAMES

        // Mean log-energy per band across time, converted back to linear scale.
        var lowE = 0.0; var midE = 0.0; var highE = 0.0
        for (b in 0 until nMels) {
            var s = 0.0
            for (t in 0 until frames) s += logMel[b * frames + t]
            val lin = exp(s / frames)
            when {
                melBank.bandCenterHz[b] < 150.0 -> lowE += lin
                melBank.bandCenterHz[b] < 1200.0 -> midE += lin
                else -> highE += lin
            }
        }
        val total = (lowE + midE + highE).coerceAtLeast(1e-9)
        val lowFrac = lowE / total
        val highFrac = highE / total

        // Soft scores -> softmax so we can report a "confidence".
        val healthy = 1.0
        val imbalance = (lowFrac - 0.33).coerceAtLeast(0.0) * 6.0
        val airflow = (highFrac - 0.33).coerceAtLeast(0.0) * 6.0
        val probs = softmax(doubleArrayOf(healthy, imbalance, airflow))

        val idx = probs.indices.maxBy { probs[it] }
        val ms = (System.nanoTime() - t0) / 1_000_000

        return FaultClassifier.Prediction(
            faultClass = FaultClass.fromIndex(idx),
            confidence = probs[idx].toFloat(),
            probabilities = FloatArray(probs.size) { probs[it].toFloat() },
            backend = InferenceBackend.NONE,
            inferenceMs = ms,
        )
    }

    private fun softmax(x: DoubleArray): DoubleArray {
        val m = x.max()
        val e = DoubleArray(x.size) { exp(x[it] - m) }
        val s = e.sum()
        return DoubleArray(x.size) { e[it] / s }
    }
}
