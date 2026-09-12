package com.jugaad.agent.fl

import com.jugaad.agent.domain.model.FaultClass
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Proves the real on-device training stack (not a reimplementation) actually learns the moment
 * a second class exists, and that [isSingleClassTrainingSet] correctly distinguishes a genuine
 * two-class set from the fleet's current degenerate single-class (all-HEALTHY) reality.
 *
 * [VariantTrainer] wraps [FlModel], which opens a real `org.tensorflow.lite.Interpreter` on a
 * `.tflite` asset — that native graph cannot load on a plain JVM unit test (no Android runtime,
 * no desktop LiteRT native lib on the classpath here). [CentroidStrategy] is the variant that
 * genuinely executes off-device: it is already exercised on the JVM by [CentroidMathTest] with
 * no mocking, so it is the real code path used below for the training assertions. The
 * single-class detector [isSingleClassTrainingSet] lives in `VariantTrainer.kt` independent of
 * any interpreter, so it is called directly against real [SampleStore] output — that half of the
 * proof exercises the actual `VariantTrainer` degenerate-set check, not a reimplementation of it.
 */
class TwoClassLearningIntegrationTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val spec = FlVariants.byId("centroid")

    /** Deterministically picks ids landing in the TRAIN split (not the 25% held-out one). Mirrors [CentroidMathTest.trainIds]. */
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

    /** Deterministically picks ids landing in the VALIDATION split. */
    private fun valIds(prefix: String, count: Int): List<String> {
        val ids = ArrayList<String>()
        var i = 0
        while (ids.size < count) {
            val id = "$prefix$i"
            if (SampleStore.isValidation(id)) ids += id
            i++
        }
        return ids
    }

    /** A feature vector with the same value across all [FlConstants.INPUT_DIM] dims. */
    private fun uniform(value: Float) = FloatArray(FlConstants.INPUT_DIM) { value }

    @Test
    fun learnsASeparableSecondClassWithRealTrainValSplitAndInference() = runBlocking {
        val dir = tmp.newFolder()
        val store = SampleStore(dir)

        // Class 0 (HEALTHY) cluster around ~5.0-6.0, class 1 (ROTOR_IMBALANCE) cluster around
        // ~15.0-16.0: widely separable across all 260 dims. Both clusters are kept well away
        // from zero deliberately: FlConstants.N_CLASSES is 3, so CentroidStrategy always carries
        // an (unused, still-zero) centroid for class 2, and a cluster too close to zero would be
        // pulled toward that stale centroid instead of its own. 6 train + 4 val samples per class
        // comfortably clears acceptGuard.minTrain=5 (12 >= 5) and training.minVal=4 (8 >= 4), on
        // both sides of the split.
        val healthyTrain = trainIds("h", 6)
        val rotorTrain = trainIds("r", 6)
        val healthyVal = valIds("hv", 4)
        val rotorVal = valIds("rv", 4)

        val healthyTrainValues = listOf(5.0f, 5.2f, 5.4f, 5.6f, 5.8f, 6.0f)
        val rotorTrainValues = listOf(15.0f, 15.2f, 15.4f, 15.6f, 15.8f, 16.0f)
        val healthyValValues = listOf(5.1f, 5.3f, 5.5f, 5.7f)
        val rotorValValues = listOf(15.1f, 15.3f, 15.5f, 15.7f)

        healthyTrain.zip(healthyTrainValues).forEach { (id, v) ->
            store.addPending(id, "asset1", uniform(v)); store.label(id, FaultClass.HEALTHY.index)
        }
        rotorTrain.zip(rotorTrainValues).forEach { (id, v) ->
            store.addPending(id, "asset1", uniform(v)); store.label(id, FaultClass.ROTOR_IMBALANCE.index)
        }
        healthyVal.zip(healthyValValues).forEach { (id, v) ->
            store.addPending(id, "asset1", uniform(v)); store.label(id, FaultClass.HEALTHY.index)
        }
        rotorVal.zip(rotorValValues).forEach { (id, v) ->
            store.addPending(id, "asset1", uniform(v)); store.label(id, FaultClass.ROTOR_IMBALANCE.index)
        }

        val allSamples = store.labelled()
        val trainSplit = allSamples.trainSplit()
        val valSplit = allSamples.valSplit()
        assertEquals(12, trainSplit.size)
        assertEquals(8, valSplit.size)

        // The real VariantTrainer.kt degenerate-set check must NOT fire on this genuine two-class set.
        assertFalse(isSingleClassTrainingSet(trainSplit))

        // Train through the real, JVM-executable strategy (no reimplementation of centroid math).
        val strategy = CentroidStrategy(spec, { store.labelled() }, dir)
        val metrics = strategy.train()

        assertTrue("nTrain should be > 0", metrics.nTrain > 0)
        assertTrue("nVal should be > 0", metrics.nVal > 0)
        assertNotEquals("valAcc must be a real number, not the -1 sentinel", -1f, metrics.valAcc)

        // Held-out accuracy on a cleanly separable two-class set should be well above chance (0.5).
        assertTrue("expected held-out accuracy well above chance, got ${metrics.valAcc}", metrics.valAcc > 0.9f)

        // Inference on fresh held-out vectors (not in the training or validation set at all).
        val healthyProbs = strategy.infer(uniform(5.45f))
        val rotorProbs = strategy.infer(uniform(15.45f))
        val healthyPred = healthyProbs.indices.maxByOrNull { healthyProbs[it] }
        val rotorPred = rotorProbs.indices.maxByOrNull { rotorProbs[it] }
        assertEquals(FaultClass.HEALTHY.index, healthyPred)
        assertEquals(FaultClass.ROTOR_IMBALANCE.index, rotorPred)
    }

    @Test
    fun singleClassTrainingSetIsDetectedAsDegenerateAndTrainAccIsTheVacuousOne() = runBlocking {
        val dir = tmp.newFolder()
        val store = SampleStore(dir)

        // Mirrors the fleet's real data: every label is HEALTHY/0. Same sample count as the
        // two-class test above so the comparison is apples-to-apples.
        val train = trainIds("h", 6)
        val validation = valIds("hv", 4)
        val trainValues = listOf(5.0f, 5.2f, 5.4f, 5.6f, 5.8f, 6.0f)
        val valValues = listOf(5.1f, 5.3f, 5.5f, 5.7f)

        train.zip(trainValues).forEach { (id, v) ->
            store.addPending(id, "asset1", uniform(v)); store.label(id, FaultClass.HEALTHY.index)
        }
        validation.zip(valValues).forEach { (id, v) ->
            store.addPending(id, "asset1", uniform(v)); store.label(id, FaultClass.HEALTHY.index)
        }

        val trainSplit = store.labelled().trainSplit()
        assertEquals(6, trainSplit.size)

        // The real degenerate-set check MUST fire here.
        assertTrue(isSingleClassTrainingSet(trainSplit))

        val strategy = CentroidStrategy(spec, { store.labelled() }, dir)
        val metrics = strategy.train()

        // trainAcc reads a vacuous 1.0: a model that only ever answers the one class it has ever
        // seen is trivially "correct" on every training example. This is the degenerate case the
        // single-class warning exists to flag, not a real learning signal.
        assertEquals(1.0f, metrics.trainAcc, 1e-6f)
    }
}
