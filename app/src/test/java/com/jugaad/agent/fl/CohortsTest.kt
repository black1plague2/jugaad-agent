package com.jugaad.agent.fl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CohortsTest {

    private fun node(id: String, mode: NodeMode, champValAcc: Float, champRound: Int) = NodeCard(
        deviceId = id, name = "phone-$id", mode = mode, challenger = null, isOwner = false,
        champRound = champRound, champValAcc = champValAcc, challValAcc = -1f, nTrain = 0, nVal = 0, lastSeenMs = 0L,
    )

    @Test
    fun computesCountMeanAccuracyAndMeanRoundPerMode() {
        val state = NetworkState.empty("base").copy(
            nodes = listOf(
                node("e1", NodeMode.EXPERIMENTAL, champValAcc = 0.8f, champRound = 2),
                node("e2", NodeMode.EXPERIMENTAL, champValAcc = 0.6f, champRound = 4),
                node("s1", NodeMode.STABLE, champValAcc = 0.9f, champRound = 3),
            ),
        )
        val cohorts = Cohorts.compute(state)

        val experimental = cohorts.getValue(NodeMode.EXPERIMENTAL)
        assertEquals(2, experimental.nodes)
        assertEquals(0.7f, experimental.meanChampValAcc, 1e-4f)
        assertEquals(3f, experimental.meanRound, 1e-4f)

        val stable = cohorts.getValue(NodeMode.STABLE)
        assertEquals(1, stable.nodes)
        assertEquals(0.9f, stable.meanChampValAcc, 1e-4f)
        assertEquals(3f, stable.meanRound, 1e-4f)
    }

    @Test
    fun ignoresNodesWithNoValidAccuracyWhenAveragingButStillCountsThem() {
        val state = NetworkState.empty("base").copy(
            nodes = listOf(
                node("e1", NodeMode.EXPERIMENTAL, champValAcc = -1f, champRound = 1),
                node("e2", NodeMode.EXPERIMENTAL, champValAcc = 0.5f, champRound = 5),
            ),
        )
        val experimental = Cohorts.compute(state).getValue(NodeMode.EXPERIMENTAL)
        assertEquals(2, experimental.nodes)
        assertEquals(0.5f, experimental.meanChampValAcc, 1e-4f)
        assertEquals(3f, experimental.meanRound, 1e-4f)
    }

    @Test
    fun reportsMinusOneMeanAccuracyWhenNoNodeHasValidData() {
        val state = NetworkState.empty("base").copy(
            nodes = listOf(node("e1", NodeMode.EXPERIMENTAL, champValAcc = -1f, champRound = 0)),
        )
        val experimental = Cohorts.compute(state).getValue(NodeMode.EXPERIMENTAL)
        assertEquals(-1f, experimental.meanChampValAcc, 1e-6f)
    }

    @Test
    fun returnsNoEntryForAModeWithNoNodes() {
        val state = NetworkState.empty("base")
        assertTrue(Cohorts.compute(state).isEmpty())
    }
}
