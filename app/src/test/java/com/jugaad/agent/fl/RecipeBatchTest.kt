package com.jugaad.agent.fl

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class RecipeBatchTest {

    private fun sample(id: String, label: Int) = FlSample(
        id = id,
        assetId = "a1",
        x = FloatArray(FlConstants.INPUT_DIM) { id.hashCode().toFloat() },
        label = label,
        source = SampleSource.HUMAN,
        ts = 0L,
    )

    @Test
    fun balancedBatchesContainEveryPresentClass() {
        val samples = (0 until 20).map { sample("s$it", it % 3) } // classes 0, 1, 2 all present
        val batches = RecipeBatching.buildBalancedBatches(samples, batchSize = 8, random = Random(1))

        assertTrue(batches.isNotEmpty())
        for (batch in batches) {
            assertEquals(8, batch.size)
            assertEquals(setOf(0, 1, 2), batch.map { it.label }.toSet())
        }
    }

    @Test
    fun noiseChangesRowsButNotSampleOrLabel() {
        val original = sample("a", label = 1)
        val random = java.util.Random(7)

        val noised = RecipeBatching.applyNoise(original.x, sigma = 0.15f, random = random)

        assertEquals(original.x.size, noised.size)
        assertNotEquals(original.x.toList(), noised.toList())
        // applyNoise must return a copy — the sample's own row and label are untouched.
        assertTrue(original.x.all { it == "a".hashCode().toFloat() })
        assertEquals(1, original.label)
    }

    @Test
    fun paddingRepeatsRowsWithinTheBatch() {
        val samples = (0 until 5).map { sample("s$it", 0) } // 5 rows -> one padded batch of 8
        val batches = RecipeBatching.buildShuffledPaddedBatches(samples, batchSize = 8, random = Random(1))

        assertEquals(1, batches.size)
        val batch = batches[0]
        assertEquals(8, batch.size)

        val ids = batch.map { it.id }
        // No id outside the original 5-row chunk, but all 5 rows are present (3 repeated to fill it).
        assertTrue(ids.all { id -> samples.any { it.id == id } })
        assertEquals(5, ids.toSet().size)
    }

    @Test
    fun shuffledBatchesNeedNoPaddingWhenSamplesDivideEvenly() {
        val samples = (0 until 16).map { sample("s$it", it % 3) }
        val batches = RecipeBatching.buildShuffledPaddedBatches(samples, batchSize = 8, random = Random(2))

        assertEquals(2, batches.size)
        assertEquals(16, batches.flatten().map { it.id }.toSet().size)
    }

    // --- focusUncertain: batches biased to low-confidence rows -------------------------

    @Test
    fun uncertainSamplingFavoursLowConfidenceRows() {
        val confident = sample("confident", 0) // max prob 0.99 -> weight ~0.11
        val uncertain = sample("uncertain", 0) // max prob 0.34 -> weight ~0.76
        val probsById = mapOf(
            "confident" to floatArrayOf(0.99f, 0.005f, 0.005f),
            "uncertain" to floatArrayOf(0.34f, 0.33f, 0.33f),
        )
        val batches = RecipeBatching.buildUncertainBatches(
            listOf(confident, uncertain), probsById, batchSize = 2000, random = Random(1),
        )
        val counts = batches.flatten().groupingBy { it.id }.eachCount()
        // Not deterministic in exact count, but the uncertain row's weight is ~7x the
        // confident row's, so over 2000 draws it should dominate heavily.
        assertTrue((counts["uncertain"] ?: 0) > (counts["confident"] ?: 0) * 2)
    }

    @Test
    fun uncertainSamplingTreatsAMissingProbabilityAsMaximallyUncertain() {
        val known = sample("known", 0)
        val unknown = sample("unknown", 0) // absent from probsById
        val probsById = mapOf("known" to floatArrayOf(1f, 0f, 0f)) // max prob 1.0 -> weight 0.1
        val batches = RecipeBatching.buildUncertainBatches(
            listOf(known, unknown), probsById, batchSize = 500, random = Random(2),
        )
        val counts = batches.flatten().groupingBy { it.id }.eachCount()
        assertTrue((counts["unknown"] ?: 0) > (counts["known"] ?: 0))
    }

    // --- selfDistill: soft rows for confident pending samples, one-hot for labelled ------

    @Test
    fun distillKeepsOneHotTargetsForLabelledSamples() {
        val labelled = listOf(sample("l0", 0), sample("l1", 2))
        val rows = RecipeBatching.buildDistillRows(labelled, emptyList(), emptyMap(), numClasses = 3)

        assertEquals(2, rows.size)
        assertArrayEquals(floatArrayOf(1f, 0f, 0f), rows[0].y, 1e-6f)
        assertArrayEquals(floatArrayOf(0f, 0f, 1f), rows[1].y, 1e-6f)
    }

    @Test
    fun distillAddsSoftRowsOnlyForConfidentPendingSamples() {
        val confidentPending = FlSample(
            id = "p0", assetId = "a1", x = FloatArray(FlConstants.INPUT_DIM), label = null,
            source = SampleSource.PENDING, ts = 0L,
        )
        val unsurePending = FlSample(
            id = "p1", assetId = "a1", x = FloatArray(FlConstants.INPUT_DIM), label = null,
            source = SampleSource.PENDING, ts = 0L,
        )
        val teacherProbs = mapOf(
            "p0" to floatArrayOf(0.95f, 0.03f, 0.02f), // clears the 0.9 threshold
            "p1" to floatArrayOf(0.5f, 0.3f, 0.2f), // does not
        )
        val rows = RecipeBatching.buildDistillRows(
            emptyList(), listOf(confidentPending, unsurePending), teacherProbs, numClasses = 3,
        )

        assertEquals(1, rows.size)
        assertArrayEquals(teacherProbs.getValue("p0"), rows[0].y, 1e-6f)
    }

    @Test
    fun rowBatchesPadAShortLastBatchAndCoverEveryRow() {
        val rows = (0 until 5).map { RecipeBatching.TrainingRow(FloatArray(4) { i -> (it * 10 + i).toFloat() }, floatArrayOf(1f, 0f, 0f)) }
        val batches = RecipeBatching.buildRowBatches(rows, batchSize = 8, random = Random(3))

        assertEquals(1, batches.size)
        assertEquals(8, batches[0].size)
        val distinctXs = batches[0].map { it.x.toList() }.toSet()
        assertEquals(5, distinctXs.size)
    }
}
