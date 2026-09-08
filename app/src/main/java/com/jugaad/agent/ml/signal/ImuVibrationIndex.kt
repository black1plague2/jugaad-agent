package com.jugaad.agent.ml.signal

import com.jugaad.agent.core.Constants
import org.jtransforms.fft.DoubleFFT_1D
import kotlin.math.sqrt

/**
 * IMU vibration index = fraction of accelerometer energy in the [5 Hz, 60 Hz]
 * band, after linear detrend + Hann window.
 *
 * Input is the per-sample acceleration magnitude (sqrt(x^2+y^2+z^2)); using the
 * magnitude makes the index insensitive to how the phone is oriented on the housing.
 *
 * Returned value is in [0, 1]. Rotating-equipment fundamentals (25-50 Hz for a
 * 1500-3000 rpm machine) sit squarely in the band, so a rising index tracks
 * mechanical looseness / imbalance even when the room is acoustically noisy.
 */
object ImuVibrationIndex {

    data class Result(
        val index: Double,
        val bandEnergy: Double,
        val totalEnergy: Double,
        val sampleRateHz: Double,
        val dominantHz: Double,
    )

    fun compute(
        accelMagnitude: FloatArray,
        sampleRateHz: Double,
        lowHz: Double = Constants.IMU_BAND_LOW_HZ,
        highHz: Double = Constants.IMU_BAND_HIGH_HZ,
    ): Result {
        val n = accelMagnitude.size
        if (n < 8 || sampleRateHz <= 0.0) {
            return Result(0.0, 0.0, 0.0, sampleRateHz, 0.0)
        }

        // Detrend then Hann.
        val x = Detrend.removeLinearTrend(DoubleArray(n) { accelMagnitude[it].toDouble() })
        val w = HannWindow.of(n)
        for (i in 0 until n) x[i] *= w[i]

        // Real FFT (JTransforms handles non-power-of-two sizes).
        DoubleFFT_1D(n.toLong()).realForward(x)

        val numBins = n / 2 + 1
        val binHz = sampleRateHz / n
        var total = 0.0
        var band = 0.0
        var domHz = 0.0
        var domPow = -1.0
        for (k in 0 until numBins) {
            val power = when (k) {
                0 -> x[0] * x[0]
                n / 2 -> if (n % 2 == 0) x[1] * x[1] else 0.0
                else -> {
                    val re = x[2 * k]
                    val im = x[2 * k + 1]
                    re * re + im * im
                }
            }
            if (k == 0) continue                    // ignore residual DC
            total += power
            val hz = k * binHz
            if (hz in lowHz..highHz) {
                band += power
                if (power > domPow) { domPow = power; domHz = hz }
            }
        }
        val idx = if (total > 0.0) (band / total).coerceIn(0.0, 1.0) else 0.0
        return Result(idx, band, total, sampleRateHz, domHz)
    }

    /** Convenience: magnitude series from parallel x/y/z arrays. */
    fun magnitude(x: FloatArray, y: FloatArray, z: FloatArray): FloatArray {
        val n = minOf(x.size, y.size, z.size)
        return FloatArray(n) { sqrt(x[it] * x[it] + y[it] * y[it] + z[it] * z[it]) }
    }
}
