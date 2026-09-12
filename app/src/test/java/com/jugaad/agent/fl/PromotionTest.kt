package com.jugaad.agent.fl

import com.jugaad.agent.core.config.AppConfig
import com.jugaad.agent.core.config.ConfigStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PromotionTest {

    /** [Promotion.update] reads [ConfigStore.effective] at call time — never leak a test's override. */
    @After
    fun resetConfig() {
        ConfigStore.applyPolicy(ConfigStore.policyOf(AppConfig()))
    }

    private fun emptyState(championId: String = "base") = NetworkState.empty(championId)

    private fun node(
        deviceId: String,
        mode: NodeMode = NodeMode.EXPERIMENTAL,
        challenger: String? = null,
    ) = NodeCard(
        deviceId = deviceId, name = "phone-$deviceId", mode = mode, challenger = challenger,
        isOwner = false, champRound = 0, champValAcc = -1f, challValAcc = -1f, nTrain = 0, nVal = 0, lastSeenMs = 0L,
    )

    @Test
    fun netAccIsWeightedByNVal() {
        val reports = listOf(
            Promotion.Report("d1", "small", nVal = 2, valAcc = 0.5f),
            Promotion.Report("d2", "small", nVal = 6, valAcc = 0.9f),
        )
        // (0.5*2 + 0.9*6) / 8 = 0.8
        val result = Promotion.update(emptyState(), reports, mapOf("small" to 1), nowMs = 1000L)
        val standing = result.standings["small"]!!
        assertEquals(0.8f, standing.netAcc, 1e-4f)
        assertEquals(8, standing.nVal)
        assertEquals(2, standing.nodes)
    }

    @Test
    fun belowEligibilityThresholdNoWinEvenIfAheadOfChampion() {
        val champReports = listOf(Promotion.Report("owner", "base", nVal = 8, valAcc = 0.5f))
        val challengerReports = listOf(Promotion.Report("d1", "small", nVal = 5, valAcc = 0.95f)) // nVal < 8
        val result = Promotion.update(emptyState(), champReports + challengerReports, mapOf("base" to 1, "small" to 1), nowMs = 1000L)
        assertEquals(0, result.standings["small"]!!.wins)
        assertEquals("base", result.championId)
    }

    @Test
    fun winsAccumulateAcrossRoundsAndResetWhenStreakBreaks() {
        var state = emptyState()
        val champReports = listOf(Promotion.Report("owner", "base", nVal = 8, valAcc = 0.60f))
        val winningChallenger = listOf(Promotion.Report("d1", "small", nVal = 8, valAcc = 0.70f)) // +0.10, beats 0.03 margin

        state = Promotion.update(state, champReports + winningChallenger, mapOf("base" to 1, "small" to 1), nowMs = 1000L)
        assertEquals(1, state.standings["small"]!!.wins)
        assertEquals("base", state.championId)

        val losingChallenger = listOf(Promotion.Report("d1", "small", nVal = 8, valAcc = 0.55f))
        state = Promotion.update(state, champReports + losingChallenger, mapOf("base" to 2, "small" to 2), nowMs = 2000L)
        assertEquals(0, state.standings["small"]!!.wins)
    }

    @Test
    fun secondConsecutiveWinPromotesAndEmitsEvent() {
        var state = emptyState()
        val champReports = listOf(Promotion.Report("owner", "base", nVal = 8, valAcc = 0.60f))
        val winningChallenger = listOf(Promotion.Report("d1", "small", nVal = 8, valAcc = 0.70f))

        state = Promotion.update(state, champReports + winningChallenger, mapOf("base" to 1, "small" to 1), nowMs = 1000L)
        assertEquals(1, state.standings["small"]!!.wins)

        state = Promotion.update(state, champReports + winningChallenger, mapOf("base" to 2, "small" to 2), nowMs = 2000L)

        assertEquals("small", state.championId)
        assertEquals(0, state.standings["small"]!!.wins)
        assertTrue(state.events.any { it.type == EventType.PROMOTE && it.text.contains("small") })
    }

    @Test
    fun assignPicksLeastPopulatedNonChampionChallenger() {
        // base is champion; small/deep already have nodes, noise/balanced are empty (tied at 0).
        val state = emptyState().copy(assignments = mapOf("d1" to "small", "d2" to "small", "d3" to "deep"))
        val result = Promotion.assign(state, node("d4"))

        val assigned = result.assignments["d4"]
        assertTrue(assigned == "noise" || assigned == "balanced")
        assertTrue(result.events.any { it.type == EventType.ASSIGN })
    }

    @Test
    fun assignSkipsStableModeAndAlreadyAssignedNodes() {
        val stableResult = Promotion.assign(emptyState(), node("d5", mode = NodeMode.STABLE))
        assertTrue(stableResult.assignments.isEmpty())

        val alreadyHasChallenger = Promotion.assign(emptyState(), node("d6", challenger = "deep"))
        assertTrue(alreadyHasChallenger.assignments.isEmpty())
    }

    // --- v3: trainMs / score / usesUnlabelled on VariantStanding -----------------------

    @Test
    fun trainMsIsWeightedByNValAcrossReports() {
        val reports = listOf(
            Promotion.Report("d1", "small", nVal = 2, valAcc = 0.5f, trainMs = 100L),
            Promotion.Report("d2", "small", nVal = 6, valAcc = 0.9f, trainMs = 300L),
        )
        // (100*2 + 300*6) / 8 = 250
        val result = Promotion.update(emptyState(), reports, mapOf("small" to 1), nowMs = 1000L)
        assertEquals(250f, result.standings["small"]!!.trainMs, 1e-3f)
    }

    @Test
    fun championScoreIsAlwaysZeroRegardlessOfItsOwnTrainingCost() {
        val champReports = listOf(Promotion.Report("owner", "base", nVal = 8, valAcc = 0.6f, trainMs = 5000L))
        val result = Promotion.update(emptyState(), champReports, mapOf("base" to 1), nowMs = 1000L)
        assertEquals(0f, result.standings["base"]!!.score, 1e-6f)
    }

    @Test
    fun nonChampionScoreMatchesStrategyRankFormulaAndUsesUnlabelledFlag() {
        val champReports = listOf(Promotion.Report("owner", "base", nVal = 8, valAcc = 0.6f, trainMs = 0L))
        val distillReports = listOf(Promotion.Report("d1", "distill", nVal = 8, valAcc = 0.6f, trainMs = 1000L))
        val result = Promotion.update(
            emptyState(), champReports + distillReports, mapOf("base" to 1, "distill" to 1), nowMs = 1000L,
        )

        val standing = result.standings["distill"]!!
        val expected = StrategyRank.score(netAcc = 0.6f, champAcc = 0.6f, trainMs = 1000f, usesUnlabelled = true)
        assertEquals(expected, standing.score, 1e-4f)
        assertTrue(standing.usesUnlabelled)
    }

    // --- v4: promotion.margin (and friends) come from AppConfig, read at call time --------

    @Test
    fun marginComesFromConfigAtCallTime() {
        // A wide-open custom config: +0.10 clears the default 0.03 margin but not this one.
        val wideMargin = AppConfig(promotion = com.jugaad.agent.core.config.Promotion(margin = 0.20, winsRequired = 2, minNVal = 8, historyLen = 8))
        ConfigStore.applyPolicy(ConfigStore.policyOf(wideMargin))

        val champReports = listOf(Promotion.Report("owner", "base", nVal = 8, valAcc = 0.60f))
        val challengerReports = listOf(Promotion.Report("d1", "small", nVal = 8, valAcc = 0.70f)) // +0.10
        val result = Promotion.update(emptyState(), champReports + challengerReports, mapOf("base" to 1, "small" to 1), nowMs = 1000L)

        assertEquals(0, result.standings["small"]!!.wins) // 0.20 margin not cleared
    }

    @Test
    fun minNValComesFromConfigAtCallTime() {
        // Lower the eligibility floor below the report's nVal so a small report now counts.
        val looseFloor = AppConfig(promotion = com.jugaad.agent.core.config.Promotion(margin = 0.03, winsRequired = 2, minNVal = 3, historyLen = 8))
        ConfigStore.applyPolicy(ConfigStore.policyOf(looseFloor))

        val champReports = listOf(Promotion.Report("owner", "base", nVal = 8, valAcc = 0.5f))
        val challengerReports = listOf(Promotion.Report("d1", "small", nVal = 5, valAcc = 0.95f)) // nVal 5 >= floor 3 now
        val result = Promotion.update(emptyState(), champReports + challengerReports, mapOf("base" to 1, "small" to 1), nowMs = 1000L)

        assertEquals(1, result.standings["small"]!!.wins)
    }
}
