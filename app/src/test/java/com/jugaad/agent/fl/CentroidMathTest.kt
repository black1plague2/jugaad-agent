package com.jugaad.agent.fl

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.math.exp

/** Frozen-backbone centroid math (Decision 3, founder table row 4): means, softmax-over-distance inference, and FedAvg exactness. */
class CentroidMathTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val spec = FlVariants.byId("centroid")

    /** Deterministically picks ids that land in [SampleStore]'s TRAIN split (not the 25% held-out one), so [count] samples reliably reach [CentroidStrategy.train]. */
    private fun trainIds(prefix: String, count: Int): List<String> {
        val ids = ArrayList<String>()
        var i = 0
        while (ids.size < count) {
            val id = "$prefix$i"
            if (!SampleStore.isValidation(id)) ids += id
            i++
        }
        return ids
    }

    private fun uniform(value: Float) = FloatArray(FlConstants.INPUT_DIM) { value }

    @Test
    fun trainComputesPerClassMeanAndKeepsPreviousCentroidForEmptyClasses() = runBlocking {
        val dir = tmp.newFolder()
        val store = SampleStore(dir)
        val ids = trainIds("m", 3)
        // Class 0: values 2 and 4 -> mean 3. Class 1: no samples -> stays at its initial zero.
        store.addPending(ids[0], "a1", uniform(2f)); store.label(ids[0], 0)
        store.addPending(ids[1], "a1", uniform(4f)); store.label(ids[1], 0)
        store.addPending(ids[2], "a1", uniform(9f)); store.label(ids[2], 2)

        val strategy = CentroidStrategy(spec, { store.labelled() }, dir)
        strategy.train()

        val w = strategy.currentWeights()
        val dim = FlConstants.INPUT_DIM
        for (i in 0 until dim) assertEquals(3f, w[i], 1e-5f)
        for (i in dim until 2 * dim) assertEquals(0f, w[i], 1e-5f)
        for (i in 2 * dim until 3 * dim) assertEquals(9f, w[i], 1e-5f)
    }

    @Test
    fun inferIsSoftmaxOverNegativeScaledSquaredDistance() = runBlocking {
        val dir = tmp.newFolder()
        val store = SampleStore(dir)
        val ids = trainIds("s", 3)
        store.addPending(ids[0], "a1", uniform(-1f)); store.label(ids[0], 0)
        store.addPending(ids[1], "a1", uniform(0f)); store.label(ids[1], 1)
        store.addPending(ids[2], "a1", uniform(1f)); store.label(ids[2], 2)

        val strategy = CentroidStrategy(spec, { store.labelled() }, dir)
        strategy.train()

        val probs = strategy.infer(uniform(0f))
        // x is all zeros; each centroid is uniformly its class value, so
        // squaredDistance = dim * classValue^2 and logit = -0.5 * classValue^2 / dim * dim
        // = -0.5 * classValue^2, independent of FlConstants.INPUT_DIM.
        val expectedLogits = floatArrayOf(-0.5f, 0f, -0.5f)
        val max = expectedLogits.max()
        val exps = expectedLogits.map { exp((it - max).toDouble()).toFloat() }
        val sum = exps.sum()
        val expected = exps.map { it / sum }.toFloatArray()

        assertArrayEquals(expected, probs, 1e-4f)
        assertEquals(1, probs.indices.maxByOrNull { probs[it] }!!) // class 1 is the exact match
    }

    @Test
    fun fedAvgOfTwoCentroidsWeightedByNTrainEqualsThePooledMean() = runBlocking {
        val dirA = tmp.newFolder()
        val dirB = tmp.newFolder()
        val dirPooled = tmp.newFolder()

        val idsA = trainIds("a", 3)
        val idsB = trainIds("b", 2)
        val valuesA = listOf(2f, 4f, 6f)
        val valuesB = listOf(10f, 20f)

        val storeA = SampleStore(dirA)
        idsA.zip(valuesA).forEach { (id, v) -> storeA.addPending(id, "a1", uniform(v)); storeA.label(id, 0) }
        val storeB = SampleStore(dirB)
        idsB.zip(valuesB).forEach { (id, v) -> storeB.addPending(id, "a1", uniform(v)); storeB.label(id, 0) }
        val storePooled = SampleStore(dirPooled)
        (idsA.zip(valuesA) + idsB.zip(valuesB)).forEach { (id, v) ->
            storePooled.addPending(id, "a1", uniform(v))
            storePooled.label(id, 0)
        }

        val csA = CentroidStrategy(spec, { storeA.labelled() }, dirA).also { it.train() }
        val csB = CentroidStrategy(spec, { storeB.labelled() }, dirB).also { it.train() }
        val csPooled = CentroidStrategy(spec, { storePooled.labelled() }, dirPooled).also { it.train() }

        val nTrainA = csA.metrics.value.nTrain
        val nTrainB = csB.metrics.value.nTrain
        assertEquals(idsA.size, nTrainA)
        assertEquals(idsB.size, nTrainB)

        val merged = FedAvg.merge(listOf(csA.currentWeights() to nTrainA, csB.currentWeights() to nTrainB))
        assertArrayEquals(csPooled.currentWeights(), merged, 1e-4f)
    }
}
