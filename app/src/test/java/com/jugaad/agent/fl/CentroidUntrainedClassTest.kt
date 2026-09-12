package com.jugaad.agent.fl

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * D11 regression coverage: an untrained class's all-zero centroid must never win a prediction or
 * carry softmax probability mass, even though it sits exactly at the origin the baseline-relative
 * feature space naturally clusters healthy samples around. See CentroidStrategy.isTrained.
 */
class CentroidUntrainedClassTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val spec = FlVariants.byId("centroid")
    private val dim = FlConstants.INPUT_DIM

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

    private fun uniform(value: Float) = FloatArray(dim) { value }

    @Test
    fun untrainedZeroCentroidNeverWinsPredictionEvenAtTheOrigin() = runBlocking {
        val dir = tmp.newFolder()
        val store = SampleStore(dir)
        // Only class 0 ever gets samples, and its cluster sits at zero -- exactly where the
        // never-trained classes 1 and 2 still have their initial all-zero centroids. Before the
        // D11 fix this is a three-way tie broken by class index, which is class 0 anyway; the
        // real bug shows up on the classify() probability mass and on a genuinely different
        // untrained-vs-origin case, both asserted below.
        val ids = trainIds("z", 5)
        ids.forEach { id -> store.addPending(id, "a1", uniform(0f)); store.label(id, 0) }

        val strategy = CentroidStrategy(spec, { store.labelled() }, dir)
        strategy.train()

        // A query exactly at the origin must resolve to the one class that was ever trained,
        // not fall through to (or tie with) an untrained class.
        val probs = strategy.infer(uniform(0f))
        val predicted = probs.indices.maxByOrNull { probs[it] }
        assertEquals(0, predicted)
        // Classes 1 and 2 were never trained: they must carry no probability mass at all.
        assertEquals(0f, probs[1], 0f)
        assertEquals(0f, probs[2], 0f)
    }

    @Test
    fun classTrainedOnlyViaMergedPeerWeightsCountsAsTrained() = runBlocking {
        val dir = tmp.newFolder()
        val store = SampleStore(dir) // no local samples at all
        val strategy = CentroidStrategy(spec, { store.labelled() }, dir)

        // Simulate a FedAvg merge where a peer has trained class 1 (centered far from the
        // origin) but this node never has. applyMerged is the only way peer-only training
        // reaches this strategy, exactly as FedAvg delivers it in production.
        val merged = FloatArray(spec.weightCount)
        val base = 1 * dim
        for (i in 0 until dim) merged[base + i] = 20f
        strategy.applyMerged(merged, newRound = 1)

        val probs = strategy.infer(uniform(20f))
        val predicted = probs.indices.maxByOrNull { probs[it] }
        assertEquals(1, predicted)
        // Untrained classes 0 and 2 must still carry no probability mass post-merge.
        assertEquals(0f, probs[0], 0f)
        assertEquals(0f, probs[2], 0f)
    }

    @Test
    fun softmaxGivesUntrainedClassesNoProbabilityMassRegardlessOfDistance() = runBlocking {
        val dir = tmp.newFolder()
        val store = SampleStore(dir)
        val ids = trainIds("t", 5)
        // Train class 0 far from the origin so an origin-ish query is actually closer to the
        // untrained (zero) centroids by raw distance -- the exact trap D11 describes.
        ids.forEach { id -> store.addPending(id, "a1", uniform(50f)); store.label(id, 0) }
        val strategy = CentroidStrategy(spec, { store.labelled() }, dir)
        strategy.train()

        val probs = strategy.infer(uniform(0f))
        assertEquals(1f, probs[0], 1e-6f) // only trained class in the race, gets all the mass
        assertEquals(0f, probs[1], 0f)
        assertEquals(0f, probs[2], 0f)
        val sum = probs.sum()
        assertEquals(1f, sum, 1e-6f)
    }

    @Test
    fun noClassTrainedYetIsSafeAndReturnsNoProbabilityMass() {
        val dir = tmp.newFolder()
        val store = SampleStore(dir)
        val strategy = CentroidStrategy(spec, { store.labelled() }, dir)

        val probs = strategy.infer(uniform(0f))
        assertEquals(FlConstants.N_CLASSES, probs.size)
        for (p in probs) assertEquals(0f, p, 0f)
        // Sanity: it's a real fresh-process read too, not something only valid pre-train.
        assertTrue(probs.none { it.isNaN() })
    }
}
