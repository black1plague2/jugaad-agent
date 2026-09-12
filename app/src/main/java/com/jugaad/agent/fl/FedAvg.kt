package com.jugaad.agent.fl

/** Federated averaging: merges each phone's weights, weighted by its local sample count. */
object FedAvg {
    fun merge(contributions: List<Pair<FloatArray, Int>>): FloatArray {
        require(contributions.isNotEmpty()) { "FedAvg.merge: contributions must not be empty" }
        val len = contributions[0].first.size
        contributions.forEach { (w, _) ->
            require(w.size == len) { "FedAvg.merge: length mismatch, expected $len got ${w.size}" }
        }
        val totalN = contributions.sumOf { it.second }
        val out = FloatArray(len)
        for ((w, n) in contributions) {
            val weight = n.toFloat() / totalN
            for (i in 0 until len) out[i] += w[i] * weight
        }
        return out
    }
}
