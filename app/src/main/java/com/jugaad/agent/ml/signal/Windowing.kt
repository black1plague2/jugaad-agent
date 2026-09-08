package com.jugaad.agent.ml.signal

import kotlin.math.cos
import kotlin.math.PI

/** Periodic Hann window (matches numpy `np.hanning`-style periodic form used by librosa). */
object HannWindow {
    private val cache = HashMap<Int, DoubleArray>()

    fun of(n: Int): DoubleArray = cache.getOrPut(n) {
        DoubleArray(n) { i -> 0.5 - 0.5 * cos(2.0 * PI * i / n) }
    }
}

object Detrend {

    /** Subtract the signal mean in place-safe fashion (returns a new array). */
    fun removeMean(x: DoubleArray): DoubleArray {
        if (x.isEmpty()) return x
        var s = 0.0
        for (v in x) s += v
        val m = s / x.size
        return DoubleArray(x.size) { x[it] - m }
    }

    /**
     * Least-squares removal of a linear trend (a*i + b). Used before the IMU FFT so a
     * slow drift / gravity leak does not dump energy into the low bins.
     */
    fun removeLinearTrend(x: DoubleArray): DoubleArray {
        val n = x.size
        if (n < 2) return removeMean(x)
        val nD = n.toDouble()
        var sumX = 0.0; var sumY = 0.0; var sumXY = 0.0; var sumXX = 0.0
        for (i in 0 until n) {
            val xi = i.toDouble()
            sumX += xi; sumY += x[i]; sumXY += xi * x[i]; sumXX += xi * xi
        }
        val denom = nD * sumXX - sumX * sumX
        if (denom == 0.0) return removeMean(x)
        val a = (nD * sumXY - sumX * sumY) / denom
        val b = (sumY - a * sumX) / nD
        return DoubleArray(n) { x[it] - (a * it + b) }
    }
}
