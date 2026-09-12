package com.jugaad.agent.fl

/**
 * Per-cohort (experimental vs stable) network health (Decision 7, v3): are experimental
 * nodes trying other strategies costing the stable path anything? They shouldn't, since
 * every node — regardless of mode — trains and serves the same shared champion.
 */
object Cohorts {
    data class Stat(val nodes: Int, val meanChampValAcc: Float, val meanRound: Float)

    /** Count, mean champion held-out accuracy (only nodes reporting a valid one), and mean round, per [NodeMode]. */
    fun compute(state: NetworkState): Map<NodeMode, Stat> =
        state.nodes.groupBy { it.mode }.mapValues { (_, nodes) ->
            val withAcc = nodes.filter { it.champValAcc >= 0f }
            val meanAcc = if (withAcc.isEmpty()) -1f else withAcc.map { it.champValAcc }.average().toFloat()
            val meanRound = if (nodes.isEmpty()) 0f else nodes.map { it.champRound }.average().toFloat()
            Stat(nodes.size, meanAcc, meanRound)
        }
}
