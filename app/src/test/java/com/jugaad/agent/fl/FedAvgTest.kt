package com.jugaad.agent.fl

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class FedAvgTest {

    @Test
    fun weightedAverageOfTwoKnownVectors() {
        val a = floatArrayOf(0f, 10f) to 1
        val b = floatArrayOf(4f, 20f) to 3
        // (0*1 + 4*3)/4 = 3.0 ; (10*1 + 20*3)/4 = 17.5
        val merged = FedAvg.merge(listOf(a, b))
        assertArrayEquals(floatArrayOf(3f, 17.5f), merged, 1e-6f)
    }

    @Test
    fun emptyContributionsThrows() {
        assertThrows(IllegalArgumentException::class.java) {
            FedAvg.merge(emptyList())
        }
    }

    @Test
    fun lengthMismatchThrows() {
        assertThrows(IllegalArgumentException::class.java) {
            FedAvg.merge(listOf(floatArrayOf(1f, 2f) to 1, floatArrayOf(1f) to 1))
        }
    }
}
