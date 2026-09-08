package com.jugaad.agent.ml.signal

import kotlin.math.log10
import kotlin.math.pow

/**
 * Triangular mel filter-bank, HTK mel scale, no area normalization.
 *
 * Byte-for-byte reference (see ml/logmel_reference.py):
 *
 *     librosa.filters.mel(sr=SR, n_fft=N_FFT, n_mels=N_MELS,
 *                         fmin=FMIN, fmax=FMAX, htk=True, norm=None)
 *
 * @param sr       sample rate (Hz)
 * @param nFft     FFT size; produces nFft/2 + 1 magnitude bins
 * @param nMels    number of mel bands
 * @param fMin     low edge (Hz)
 * @param fMax     high edge (Hz)
 */
class MelFilterBank(
    sr: Int,
    private val nFft: Int,
    private val nMels: Int,
    fMin: Double,
    fMax: Double,
) {
    private val numBins = nFft / 2 + 1

    /** filters[mel][bin] weight. */
    private val filters: Array<DoubleArray>

    /** Centre frequency (Hz) of each mel band — used to phrase the LLM prompt. */
    val bandCenterHz: DoubleArray

    init {
        val melMin = hzToMel(fMin)
        val melMax = hzToMel(fMax)
        // nMels + 2 equally spaced points on the mel axis -> nMels triangles.
        val melPoints = DoubleArray(nMels + 2) { melMin + (melMax - melMin) * it / (nMels + 1) }
        val hzPoints = DoubleArray(nMels + 2) { melToHz(melPoints[it]) }
        bandCenterHz = DoubleArray(nMels) { hzPoints[it + 1] }
        // Map Hz edges to fractional FFT bin indices.
        val binPoints = DoubleArray(nMels + 2) { hzPoints[it] * (nFft) / sr }

        filters = Array(nMels) { DoubleArray(numBins) }
        for (m in 0 until nMels) {
            val left = binPoints[m]
            val center = binPoints[m + 1]
            val right = binPoints[m + 2]
            for (k in 0 until numBins) {
                val kk = k.toDouble()
                val w = when {
                    kk < left || kk > right -> 0.0
                    kk <= center -> if (center == left) 0.0 else (kk - left) / (center - left)
                    else -> if (right == center) 0.0 else (right - kk) / (right - center)
                }
                filters[m][k] = w.coerceAtLeast(0.0)
            }
        }
    }

    /** power: length numBins power spectrum -> length nMels mel energies. */
    fun apply(power: DoubleArray): DoubleArray {
        require(power.size == numBins) { "expected $numBins bins, got ${power.size}" }
        val out = DoubleArray(nMels)
        for (m in 0 until nMels) {
            val f = filters[m]
            var acc = 0.0
            for (k in 0 until numBins) acc += f[k] * power[k]
            out[m] = acc
        }
        return out
    }

    companion object {
        // HTK mel scale.
        fun hzToMel(hz: Double): Double = 2595.0 * log10(1.0 + hz / 700.0)
        fun melToHz(mel: Double): Double = 700.0 * (10.0.pow(mel / 2595.0) - 1.0)
    }
}
