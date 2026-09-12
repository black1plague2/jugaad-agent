package com.jugaad.agent.fl

import org.junit.Assert.assertEquals
import org.junit.Test

class EarlyStoppingTest {

    @Test
    fun picksTheLowestLossEpochAndStopsAfterPatienceEpochsWithNoImprovement() {
        // Best at epoch 1 (0.5); epochs 2..6 are five non-improving epochs in a row (patience 5),
        // so training stops after epoch 6 without ever seeing epoch 7's 0.4.
        val valLosses = listOf(1.0f, 0.5f, 0.6f, 0.6f, 0.6f, 0.6f, 0.6f, 0.4f)
        val result = EarlyStopping.run(valLosses, patience = 5)
        assertEquals(1, result.bestEpoch)
        assertEquals(7, result.stopEpoch)
    }

    @Test
    fun neverStopsWhileLossKeepsImproving() {
        val valLosses = listOf(1.0f, 0.9f, 0.8f, 0.7f, 0.6f)
        val result = EarlyStopping.run(valLosses, patience = 5)
        assertEquals(4, result.bestEpoch)
        assertEquals(valLosses.size, result.stopEpoch)
    }

    @Test
    fun stopsExactlyAtPatienceBoundaryWhenTheFirstEpochIsTheOnlyImprovement() {
        val valLosses = listOf(0.5f, 0.6f, 0.6f, 0.6f, 0.6f, 0.6f)
        val result = EarlyStopping.run(valLosses, patience = 5)
        assertEquals(0, result.bestEpoch)
        assertEquals(6, result.stopEpoch)
    }

    @Test
    fun stepReturnsStopOnlyOncePatienceIsExhausted() {
        var state = EarlyStopping.initial()
        var stop = false
        val losses = listOf(0.5f, 0.6f, 0.6f, 0.6f)
        for ((epoch, loss) in losses.withIndex()) {
            val (next, s) = EarlyStopping.step(state, epoch, loss, patience = 3)
            state = next
            stop = s
            if (stop) break
        }
        assertEquals(true, stop)
        assertEquals(0, state.bestEpoch)
        assertEquals(3, state.noImprovement)
    }
}
