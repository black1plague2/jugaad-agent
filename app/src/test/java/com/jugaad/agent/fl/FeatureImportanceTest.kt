package com.jugaad.agent.fl

import com.jugaad.agent.core.config.AppConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** "What the champion listens to" (v4 plan §6): band/sensor grouping and normalisation. */
class FeatureImportanceTest {

    private val cfg = AppConfig()

    @Test
    fun oneDominantColumnMakesItsGroupDominateAndSharesSumToOneHundred() {
        val spec = FlVariants.byId("base")
        val hidden = spec.layers[1]
        val w = FloatArray(spec.weightCount)
        val bigDim = 256 // accel delta
        for (j in 0 until hidden) w[bigDim * hidden + j] = 1000f

        val groups = FeatureImportance.compute(w, spec, cfg)

        assertEquals("accel", groups[0].name) // sorted descending -> dominant first
        assertTrue(groups[0].share > 95f)
        assertEquals(100f, groups.sumOf { it.share.toDouble() }.toFloat(), 0.5f)
    }

    @Test
    fun lowestMelDimGroupsAsLowBand() {
        val spec = FlVariants.byId("base")
        val hidden = spec.layers[1]
        val w = FloatArray(spec.weightCount)
        for (j in 0 until hidden) w[0 * hidden + j] = 500f // dim 0: lowest-frequency mel bin (mean half)

        val groups = FeatureImportance.compute(w, spec, cfg)

        assertEquals("low", groups[0].name)
    }

    @Test
    fun highestMelDimGroupsAsVeryHighBand() {
        val spec = FlVariants.byId("base")
        val hidden = spec.layers[1]
        val w = FloatArray(spec.weightCount)
        for (j in 0 until hidden) w[127 * hidden + j] = 500f // dim 127: highest-frequency mel bin (mean half)

        val groups = FeatureImportance.compute(w, spec, cfg)

        assertEquals("veryHigh", groups[0].name)
    }

    @Test
    fun meanAndStdHalvesOfTheSameBandFoldIntoOneGroup() {
        val spec = FlVariants.byId("base")
        val hidden = spec.layers[1]
        val w = FloatArray(spec.weightCount)
        for (j in 0 until hidden) {
            w[0 * hidden + j] = 500f // mean half, band of dim 0
            w[128 * hidden + j] = 500f // std half, same band (dim 0 + 128)
        }

        val groups = FeatureImportance.compute(w, spec, cfg)

        assertEquals("low", groups[0].name)
        assertEquals(1, groups.count { it.share > 1f }) // every other group is ~0
    }

    @Test
    fun centroidKindReadsTheClassMajorLayout() {
        val spec = FlVariants.byId("centroid")
        val dim = FlConstants.INPUT_DIM
        val w = FloatArray(spec.weightCount)
        val bigDim = 259 // magRms delta
        for (c in 0 until FlConstants.N_CLASSES) w[c * dim + bigDim] = 50f

        val groups = FeatureImportance.compute(w, spec, cfg)

        assertEquals("magRms", groups[0].name)
    }

    @Test
    fun allZeroWeightsYieldZeroSharesNotNaN() {
        val spec = FlVariants.byId("base")
        val w = FloatArray(spec.weightCount)

        val groups = FeatureImportance.compute(w, spec, cfg)

        assertTrue(groups.isNotEmpty())
        assertTrue(groups.all { it.share == 0f })
    }
}
