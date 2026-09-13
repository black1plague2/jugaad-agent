package com.jugaad.agent.fl

import com.jugaad.agent.core.config.AppConfig
import com.jugaad.agent.core.config.Sharing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Peer sample pool (v4 plan §5): caps, per-origin caps, oldest-first eviction, id dedupe. */
class SharedPoolTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun sample(id: String, origin: String?, ts: Long, assetId: String = "a1") = FlSample(
        id = id, assetId = assetId, x = FloatArray(FlConstants.INPUT_DIM), label = 0,
        source = SampleSource.HUMAN, ts = ts, score = null, abs = null, absSensors = null,
        origin = origin, machineTypeId = null,
    )

    private fun cfg(maxPool: Int = 100, maxPerOrigin: Int = 100): () -> AppConfig =
        { AppConfig(sharing = Sharing(enabled = true, maxPoolSamples = maxPool, maxPerOrigin = maxPerOrigin, batchSize = 200)) }

    @Test
    fun addAllSkipsSamplesWithNoOrigin() {
        val pool = SharedPool(tmp.newFolder(), cfg())
        val added = pool.addAll(listOf(sample("s1", origin = null, ts = 1L)))
        assertEquals(0, added)
        assertEquals(0, pool.count())
    }

    @Test
    fun addAllDedupesById() {
        val pool = SharedPool(tmp.newFolder(), cfg())
        assertEquals(1, pool.addAll(listOf(sample("s1", "peerA", ts = 1L))))
        assertEquals(0, pool.addAll(listOf(sample("s1", "peerA", ts = 1L)))) // same id again
        assertEquals(1, pool.count())
        assertEquals(setOf("s1"), pool.ids())
    }

    @Test
    fun allReturnsEverySampleCurrentlyHeld() {
        val pool = SharedPool(tmp.newFolder(), cfg())
        pool.addAll(listOf(sample("s1", "peerA", 1L), sample("s2", "peerB", 2L)))
        assertEquals(setOf("s1", "s2"), pool.all().map { it.id }.toSet())
    }

    @Test
    fun globalCapEvictsOldestSamplesFirst() {
        val pool = SharedPool(tmp.newFolder(), cfg(maxPool = 3, maxPerOrigin = 100))
        pool.addAll(
            listOf(
                sample("old", "peerA", ts = 1L),
                sample("mid", "peerA", ts = 2L),
                sample("new", "peerA", ts = 3L),
            ),
        )
        assertEquals(3, pool.count())

        pool.addAll(listOf(sample("newest", "peerA", ts = 4L)))

        assertEquals(3, pool.count())
        assertFalse(pool.ids().contains("old")) // oldest evicted
        assertTrue(pool.ids().containsAll(listOf("mid", "new", "newest")))
    }

    @Test
    fun perOriginCapEvictsOldestWithinThatOriginOnly() {
        val pool = SharedPool(tmp.newFolder(), cfg(maxPool = 100, maxPerOrigin = 2))
        pool.addAll(
            listOf(
                sample("a-old", "peerA", ts = 1L),
                sample("a-mid", "peerA", ts = 2L),
                sample("b-1", "peerB", ts = 5L),
            ),
        )
        pool.addAll(listOf(sample("a-new", "peerA", ts = 3L))) // 3rd from peerA -> evict a-old

        assertEquals(3, pool.count()) // 2 from peerA + 1 from peerB
        assertFalse(pool.ids().contains("a-old"))
        assertTrue(pool.ids().containsAll(listOf("a-mid", "a-new", "b-1")))
    }

    @Test
    fun countByOriginGroupsCorrectly() {
        val pool = SharedPool(tmp.newFolder(), cfg())
        pool.addAll(
            listOf(
                sample("a1", "peerA", 1L),
                sample("a2", "peerA", 2L),
                sample("b1", "peerB", 3L),
            ),
        )
        assertEquals(mapOf("peerA" to 2, "peerB" to 1), pool.countByOrigin())
    }

    @Test
    fun persistsAcrossReopenWithPeerSourceAndOrigin() {
        val dir = tmp.newFolder()
        SharedPool(dir, cfg()).addAll(listOf(sample("s1", "peerA", ts = 1L)))

        val reopened = SharedPool(dir, cfg())
        assertEquals(1, reopened.count())
        val loaded = reopened.all().single()
        assertEquals("s1", loaded.id)
        assertEquals("peerA", loaded.origin)
        assertEquals(SampleSource.PEER, loaded.source)
    }

    @Test
    fun removeForAssetRemovesOnlyThatAssetsSamples() {
        val pool = SharedPool(tmp.newFolder(), cfg())
        pool.addAll(
            listOf(
                sample("s1", "peerA", ts = 1L, assetId = "assetA"),
                sample("s2", "peerA", ts = 2L, assetId = "assetB"),
            ),
        )

        val removed = pool.removeForAsset("assetA")

        assertEquals(1, removed)
        assertEquals(setOf("s2"), pool.ids())
    }

    @Test
    fun removeForAssetPersistsAcrossReopen() {
        val dir = tmp.newFolder()
        SharedPool(dir, cfg()).addAll(
            listOf(
                sample("s1", "peerA", ts = 1L, assetId = "assetA"),
                sample("s2", "peerA", ts = 2L, assetId = "assetB"),
            ),
        )
        SharedPool(dir, cfg()).removeForAsset("assetA")

        val reopened = SharedPool(dir, cfg())
        assertEquals(setOf("s2"), reopened.ids())
    }

    @Test
    fun removeForAssetUnknownIdReturnsZeroAndChangesNothing() {
        val pool = SharedPool(tmp.newFolder(), cfg())
        pool.addAll(listOf(sample("s1", "peerA", ts = 1L, assetId = "assetA")))

        val removed = pool.removeForAsset("unknown")

        assertEquals(0, removed)
        assertEquals(setOf("s1"), pool.ids())
    }
}
