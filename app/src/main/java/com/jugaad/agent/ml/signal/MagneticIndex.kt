package com.jugaad.agent.ml.signal

import com.jugaad.agent.core.Constants
import org.jtransforms.fft.DoubleFFT_1D
import kotlin.math.sqrt

/**
 * Magnetometer vibration index = fraction of |B| energy in the [3 Hz, 25 Hz]
 * band, after linear detrend + Hann window — same approach as
 * [ImuVibrationIndex] but tuned for the magnetometer's lower sample rate.
 * Phones sample B at 50-100 Hz, so line-frequency hum aliases down into this
 * low band along with any rotor-related field fluctuation.
 *
 * Input is the per-sample field magnitude (sqrt(x^2+y^2+z^2)); use
 * [ImuVibrationIndex.magnitude] to build it from raw x/y/z.
 */
object MagneticIndex {

    data class Result(val index: Double, val rmsMicroTesla: Double)

    fun compute(
        mag: FloatArray,
        rateHz: Double,
        lowHz: Double = Constants.MAG_BAND_LOW_HZ,
        highHz: Double = Constants.MAG_BAND_HIGH_HZ,
    ): Result {
        val n = mag.size
        if (n < 8 || rateHz <= 0.0) return Result(0.0, 0.0)

        // Detrend first: rmsMicroTesla describes the AC fluctuation, not the
        // ambient DC field, and the FFT below would otherwise dump the DC
        // term's energy into the lowest bins.
        val detrended = Detrend.removeLinearTrend(DoubleArray(n) { mag[it].toDouble() })
        val rms = sqrt(detrended.sumOf { it * it } / n)

        val w = HannWindow.of(n)
        val x = DoubleArray(n) { detrended[it] * w[it] }
        DoubleFFT_1D(n.toLong()).realForward(x)

        val numBins = n / 2 + 1
        val binHz = rateHz / n
        var total = 0.0
        var band = 0.0
        for (k in 1 until numBins) {              // k = 0 is residual DC, ignored
            val power = when (k) {
                n / 2 -> if (n % 2 == 0) x[1] * x[1] else 0.0
                else -> {
                    val re = x[2 * k]
                    val im = x[2 * k + 1]
                    re * re + im * im
                }
            }
            total += power
            val hz = k * binHz
            if (hz in lowHz..highHz) band += power
        }
        val idx = if (total > 0.0) (band / total).coerceIn(0.0, 1.0) else 0.0
        return Result(idx, rms)
    }
}
