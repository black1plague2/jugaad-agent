package com.jugaad.agent.fl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SampleStoreSplitTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun sample(seed: Int) = FloatArray(FlConstants.INPUT_DIM) { seed.toFloat() }

    @Test
    fun matchesContractFormula() {
        for (i in 0 until 100) {
            val id = "id-$i"
            val expected = (id.hashCode() and 0x7fffffff) % 4 == 0
            assertEquals(expected, SampleStore.isValidation(id))
        }
    }

    @Test
    fun isDeterministicForTheSameId() {
        val id = "sample-42"
        assertEquals(SampleStore.isValidation(id), SampleStore.isValidation(id))
    }

    @Test
    fun trainAndValPartitionAllLabelledSamplesWithNoOverlap() {
        val store = SampleStore(tmp.root)
        val labels = intArrayOf(0, 1, 2)
        for (i in 0 until 40) {
            val id = "s$i"
            store.addPending(id, "asset1", sample(i))
            store.label(id, labels[i % labels.size])
        }

        val train = store.labelledTrain()
        val validation = store.labelledVal()

        assertEquals(store.labelled().size, train.size + validation.size)
        assertEquals(40, train.size + validation.size)

        val trainIds = train.map { it.id }.toSet()
        val valIds = validation.map { it.id }.toSet()
        assertTrue(trainIds.intersect(valIds).isEmpty())

        for (s in validation) assertTrue(SampleStore.isValidation(s.id))
        for (s in train) assertTrue(!SampleStore.isValidation(s.id))
    }
}
