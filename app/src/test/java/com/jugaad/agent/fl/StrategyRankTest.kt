package com.jugaad.agent.fl

import org.junit.Assert.assertEquals
import org.junit.Test

class StrategyRankTest {

    @Test
    fun scoreCombinesAccuracyGainTrainingCostAndUnlabelledBonus() {
        // 100*(0.7-0.6) - 0.002*500 + 2*0 = 10 - 1 + 0 = 9
        assertEquals(9f, StrategyRank.score(netAcc = 0.7f, champAcc = 0.6f, trainMs = 500f, usesUnlabelled = false), 1e-4f)
        // Same accuracy/cost, but the unlabelled bonus adds 2.
        assertEquals(11f, StrategyRank.score(netAcc = 0.7f, champAcc = 0.6f, trainMs = 500f, usesUnlabelled = true), 1e-4f)
    }

    @Test
    fun scorePenalizesTrainingCostEvenWithNoAccuracyChange() {
        val score = StrategyRank.score(netAcc = 0.6f, champAcc = 0.6f, trainMs = 1000f, usesUnlabelled = false)
        assertEquals(-2f, score, 1e-4f)
    }

    @Test
    fun rankSortsStandingsDescendingByScore() {
        val state = NetworkState.empty("base").copy(
            standings = mapOf(
                "base" to standing("base", score = 0f),
                "small" to standing("small", score = 5f),
                "deep" to standing("deep", score = -3f),
                "distill" to standing("distill", score = 12f),
            ),
        )
        val ranked = StrategyRank.rank(state)
        assertEquals(listOf("distill", "small", "base", "deep"), ranked.map { it.variantId })
    }

    private fun standing(id: String, score: Float) = VariantStanding(
        variantId = id, netAcc = 0.5f, nVal = 10, nodes = 1, history = emptyList(), wins = 0, round = 1,
        trainMs = 0f, score = score, usesUnlabelled = false,
    )
}
