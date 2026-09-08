package com.jugaad.agent.ml.anomaly

import com.jugaad.agent.core.Constants

/**
 * Per-asset, calibratable decision boundaries for the anomaly score.
 *
 *   score <= t1            -> Healthy
 *   t1 < score <= t2       -> Warning
 *   score > t2             -> Critical
 */
data class Thresholds(
    val t1: Double = Constants.DEFAULT_T1,
    val t2: Double = Constants.DEFAULT_T2,
) {
    init {
        require(t1 > 0 && t2 > t1) { "expected 0 < t1 < t2, got t1=$t1 t2=$t2" }
    }

    companion object {
        val DEFAULT = Thresholds()

        /** Never throws — bad persisted values fall back to [DEFAULT]. */
        fun safe(t1: Double, t2: Double): Thresholds =
            runCatching { Thresholds(t1, t2) }.getOrDefault(DEFAULT)
    }
}
