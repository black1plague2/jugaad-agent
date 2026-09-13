package com.jugaad.agent.fl

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SampleStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun sample(seed: Int) = FloatArray(FlConstants.INPUT_DIM) { seed.toFloat() }

    @Test
    fun addPendingIncreasesPendingCount() {
        val store = SampleStore(tmp.root)
        store.addPending("s1", "asset1", sample(1))
        store.addPending("s2", "asset1", sample(2))
        assertEquals(2, store.pendingCount())
        assertTrue(store.labelled().isEmpty())
    }

    @Test
    fun labelMovesFromPendingToLabelled() {
        val store = SampleStore(tmp.root)
        store.addPending("s1", "asset1", sample(1))
        assertTrue(store.label("s1", 1, SampleSource.HUMAN))
        assertEquals(0, store.pendingCount())
        assertEquals(1, store.labelled().size)
        assertEquals(1, store.counts()[1])
    }

    @Test
    fun labelUnknownIdReturnsFalse() {
        val store = SampleStore(tmp.root)
        assertFalse(store.label("missing", 0))
    }

    @Test
    fun survivesReopen() {
        val store = SampleStore(tmp.root)
        store.addPending("s1", "asset1", sample(1))
        store.label("s1", 2, SampleSource.AUTO)
        store.addPending("s2", "asset1", sample(2))

        val reopened = SampleStore(tmp.root)
        assertEquals(1, reopened.labelled().size)
        assertEquals(1, reopened.pendingCount())
        assertEquals(1, reopened.counts()[2])
    }

    /** A v2-era file persisted 257-d samples (256 log-mel + accel); v3 heads need 260-d. */
    @Test
    fun padsLegacyShortSamplesToInputDimOnLoad() {
        val legacy = FloatArray(257) { i -> (i + 1).toFloat() }
        val line = """{"id":"legacy1","assetId":"asset1","x":[${
            legacy.joinToString(",")
        }],"label":1,"source":"HUMAN","ts":1700000000000}"""
        tmp.root.mkdirs()
        File(tmp.root, "samples.jsonl").writeText(line + "\n")

        val store = SampleStore(tmp.root)
        val loaded = store.labelled().single()
        assertEquals(FlConstants.INPUT_DIM, loaded.x.size)
        assertEquals(1, loaded.label)
        for (i in legacy.indices) assertEquals(legacy[i], loaded.x[i], 0f)
        for (i in legacy.size until FlConstants.INPUT_DIM) assertEquals(0f, loaded.x[i], 0f)
        assertEquals(1, store.counts()[1])
    }

    /** A v3-era line with none of the v4 fields (abs/absSensors/origin/machineTypeId) must still load. */
    @Test
    fun legacyLineWithoutV4FieldsLoadsWithNullsDefaulted() {
        val line = """{"id":"legacy2","assetId":"asset1","x":[${
            (1..FlConstants.INPUT_DIM).joinToString(",") { "0.0" }
        }],"label":0,"source":"AUTO","ts":1700000000000,"score":0.3}"""
        tmp.root.mkdirs()
        File(tmp.root, "samples.jsonl").writeText(line + "\n")

        val store = SampleStore(tmp.root)
        val loaded = store.labelled().single()
        assertEquals(0.3, loaded.score)
        assertNull(loaded.abs)
        assertNull(loaded.absSensors)
        assertNull(loaded.origin)
        assertNull(loaded.machineTypeId)
    }

    @Test
    fun addPendingWithAbsAndSensorsRoundTripsThroughReopen() {
        val store = SampleStore(tmp.root)
        val abs = FloatArray(256) { it.toFloat() * 0.1f }
        val absSensors = floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f)
        store.addPending("s1", "asset1", sample(1), 0.4, abs, absSensors, "coffee_vending")
        store.label("s1", 0, SampleSource.HUMAN)

        val reopened = SampleStore(tmp.root)
        val loaded = reopened.labelled().single()
        assertArrayEquals(abs, loaded.abs!!, 1e-6f)
        assertArrayEquals(absSensors, loaded.absSensors!!, 1e-6f)
        assertEquals("coffee_vending", loaded.machineTypeId)
        assertEquals(0.4, loaded.score)
    }

    @Test
    fun olderAddPendingOverloadsLeaveNewFieldsNull() {
        val store = SampleStore(tmp.root)
        store.addPending("s1", "asset1", sample(1))
        store.addPending("s2", "asset1", sample(2), 0.7)

        val s1 = store.pending().single { it.id == "s1" }
        val s2 = store.pending().single { it.id == "s2" }
        assertNull(s1.abs)
        assertNull(s1.score)
        assertNull(s2.abs)
        assertEquals(0.7, s2.score)
    }

    @Test
    fun forAssetReturnsOnlySamplesForThatAsset() {
        val store = SampleStore(tmp.root)
        store.addPending("s1", "assetA", sample(1))
        store.addPending("s2", "assetB", sample(2))
        store.addPending("s3", "assetA", sample(3))

        val forA = store.forAsset("assetA")
        assertEquals(2, forA.size)
        assertTrue(forA.all { it.assetId == "assetA" })
    }

    @Test
    fun healthyForAssetOnlyReturnsLabelZeroSamples() {
        val store = SampleStore(tmp.root)
        store.addPending("s1", "assetA", sample(1))
        store.addPending("s2", "assetA", sample(2))
        store.label("s1", 0, SampleSource.HUMAN)
        store.label("s2", 1, SampleSource.HUMAN)

        val healthy = store.healthyForAsset("assetA")
        assertEquals(1, healthy.size)
        assertEquals("s1", healthy.single().id)
    }

    @Test
    fun removeForAssetRemovesOnlyThatAssetsSamples() {
        val store = SampleStore(tmp.root)
        store.addPending("s1", "assetA", sample(1))
        store.addPending("s2", "assetA", sample(2))
        store.addPending("s3", "assetB", sample(3))
        store.label("s1", 0, SampleSource.HUMAN) // labelled
        // s2 stays pending (unlabelled)

        val removed = store.removeForAsset("assetA")

        assertEquals(2, removed)
        assertTrue(store.forAsset("assetA").isEmpty())
        assertEquals(1, store.forAsset("assetB").size)
    }

    @Test
    fun removeForAssetPersistsAcrossReopen() {
        val store = SampleStore(tmp.root)
        store.addPending("s1", "assetA", sample(1))
        store.addPending("s2", "assetB", sample(2))
        store.label("s1", 0, SampleSource.HUMAN)

        store.removeForAsset("assetA")

        val reopened = SampleStore(tmp.root)
        assertTrue(reopened.forAsset("assetA").isEmpty())
        assertEquals(1, reopened.forAsset("assetB").size)
    }

    @Test
    fun removeForAssetUnknownIdReturnsZeroAndChangesNothing() {
        val store = SampleStore(tmp.root)
        store.addPending("s1", "assetA", sample(1))

        val removed = store.removeForAsset("unknown")

        assertEquals(0, removed)
        assertEquals(1, store.forAsset("assetA").size)
    }
}
