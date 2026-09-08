package com.jugaad.agent.ml.signal

import com.jugaad.agent.core.Constants
import org.jtransforms.fft.DoubleFFT_1D
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * A 128 x 128 log-mel "image" plus the derived 256-d statistic vector.
 *
 * @param mel      row-major, mel-major: `mel[b * frames + t]`, size [nMels * frames]
 * @param nMels    128
 * @param frames   128
 */
class LogMel(
    val mel: FloatArray,
    val nMels: Int,
    val frames: Int,
) {
    fun at(band: Int, frame: Int): Float = mel[band * frames + frame]
}

/**
 * On-device STFT -> mel -> log front-end.
 *
 * Parameters (from [Constants], mirrored into BuildConfig and ml/logmel_reference.py):
 *   n_fft = 2048, hop = 1024, n_mels = 128, band = 20 Hz .. 11 kHz,
 *   window = periodic Hann, center = false, power = 2.0, log = ln(x + 1e-6).
 *
 * A 3 s / 132300-sample clip yields exactly `1 + (132300 - 2048) / 1024 = 128` frames,
 * so the output is a clean 128 x 128 with no resampling. Off-length input is
 * truncated / edge-padded to keep the shape fixed for the CNN.
 */
class LogMelSpectrogram(
    private val sampleRate: Int = Constants.SAMPLE_RATE_HZ,
    private val nFft: Int = Constants.N_FFT,
    private val hop: Int = Constants.HOP,
    private val nMels: Int = Constants.N_MELS,
    private val targetFrames: Int = Constants.SPEC_FRAMES,
) {
    private val fft = DoubleFFT_1D(nFft.toLong())
    private val window = HannWindow.of(nFft)
    private val numBins = nFft / 2 + 1
    private val melBank = MelFilterBank(
        sr = sampleRate,
        nFft = nFft,
        nMels = nMels,
        fMin = Constants.MEL_FMIN_HZ,
        fMax = Constants.MEL_FMAX_HZ,
    )
    private val logFloor = 1e-6

    fun compute(samples: FloatArray): LogMel {
        val rawFrames = if (samples.size >= nFft) 1 + (samples.size - nFft) / hop else 0
        val cols = ArrayList<DoubleArray>(maxOf(rawFrames, 1))

        val buf = DoubleArray(nFft)
        for (f in 0 until rawFrames) {
            val start = f * hop
            for (i in 0 until nFft) buf[i] = samples[start + i].toDouble() * window[i]
            fft.realForward(buf)                 // in place, packed real output
            val power = powerSpectrum(buf)
            val melEnergies = melBank.apply(power)
            for (b in 0 until nMels) melEnergies[b] = ln(melEnergies[b] + logFloor)
            cols.add(melEnergies)
        }
        if (cols.isEmpty()) cols.add(DoubleArray(nMels) { ln(logFloor) })

        // Fix the time axis to exactly targetFrames.
        val fixed = ArrayList<DoubleArray>(targetFrames)
        for (t in 0 until targetFrames) fixed.add(cols[t.coerceAtMost(cols.size - 1)])

        val out = FloatArray(nMels * targetFrames)
        for (b in 0 until nMels) {
            for (t in 0 until targetFrames) {
                out[b * targetFrames + t] = fixed[t][b].toFloat()
            }
        }
        return LogMel(out, nMels, targetFrames)
    }

    /** [Constants.FEATURE_DIM] = per-band mean (nMels) || per-band std (nMels). */
    fun featureVector(logMel: LogMel): FloatArray {
        val nB = logMel.nMels
        val nT = logMel.frames
        val out = FloatArray(nB * 2)
        for (b in 0 until nB) {
            var sum = 0.0
            for (t in 0 until nT) sum += logMel.at(b, t)
            val mean = sum / nT
            var varAcc = 0.0
            for (t in 0 until nT) {
                val d = logMel.at(b, t) - mean
                varAcc += d * d
            }
            val std = sqrt(varAcc / nT)
            out[b] = mean.toFloat()
            out[nB + b] = std.toFloat()
        }
        return out
    }

    private fun powerSpectrum(packed: DoubleArray): DoubleArray {
        val p = DoubleArray(numBins)
        p[0] = packed[0] * packed[0]                       // DC
        val nyq = packed[1]
        p[nFft / 2] = nyq * nyq                            // Nyquist
        var k = 1
        while (k < nFft / 2) {
            val re = packed[2 * k]
            val im = packed[2 * k + 1]
            p[k] = re * re + im * im
            k++
        }
        return p
    }
}
