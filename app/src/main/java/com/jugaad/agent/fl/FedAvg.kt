package com.jugaad.agent.fl

/** Federated averaging: merges each phone's weights, weighted by its local sample count. */
object FedAvg {
    /**
     * @return the merged weights, or null when every contributor reports zero trained samples
     * (totalN <= 0). A round with no data carries no information: weighting by n/totalN would
     * divide by zero into NaN, so the caller must skip the variant on null instead of writing
     * zeros/NaN over good weights.
     */
    fun merge(contributions: List<Pair<FloatArray, Int>>): FloatArray? {
        require(contributions.isNotEmpty()) { "FedAvg.merge: contributions must not be empty" }
        val len = contributions[0].first.size
        contributions.forEach { (w, _) ->
            require(w.size == len) { "FedAvg.merge: length mismatch, expected $len got ${w.size}" }
        }
        val totalN = contributions.sumOf { it.second }
        if (totalN <= 0) return null
        val out = FloatArray(len)
        for ((w, n) in contributions) {
            val weight = n.toFloat() / totalN
            for (i in 0 until len) out[i] += w[i] * weight
        }
        return out
    }
}
