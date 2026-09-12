package com.jugaad.agent.fl

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Degenerate single-class training set detection (D4): [isSingleClassTrainingSet]. */
class VariantTrainerSingleClassTest {

    private fun sample(id: String, label: Int) = FlSample(
        id = id, assetId = "asset1", x = FloatArray(FlConstants.INPUT_DIM), label = label,
        source = SampleSource.HUMAN, ts = 0L,
    )

    @Test
    fun firesWhenEveryTrainingSampleSharesOneClass() {
        val trainSamples = listOf(sample("a", 0), sample("b", 0), sample("c", 0))
        assertTrue(isSingleClassTrainingSet(trainSamples))
    }

    @Test
    fun doesNotFireOnATwoClassTrainingSet() {
        val trainSamples = listOf(sample("a", 0), sample("b", 0), sample("c", 1))
        assertFalse(isSingleClassTrainingSet(trainSamples))
    }

    @Test
    fun doesNotFireOnAnEmptyTrainingSet() {
        assertFalse(isSingleClassTrainingSet(emptyList()))
    }
}
