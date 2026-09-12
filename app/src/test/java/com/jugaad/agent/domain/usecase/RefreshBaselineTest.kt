package com.jugaad.agent.domain.usecase

import com.jugaad.agent.core.Outcome
import com.jugaad.agent.core.config.AppConfig
import com.jugaad.agent.core.config.Calibration
import com.jugaad.agent.domain.model.Asset
import com.jugaad.agent.domain.model.Baseline
import com.jugaad.agent.domain.repository.AssetRepository
import com.jugaad.agent.fl.SampleSource
import com.jugaad.agent.fl.SampleStore
import com.jugaad.agent.ml.anomaly.Thresholds
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RefreshBaselineTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private class FakeAssetRepository : AssetRepository {
        var saved: Baseline? = null
        override fun observeAssets(): Flow<List<Asset>> = flowOf(emptyList())
        override suspend fun getAsset(id: String): Asset? = null
        override suspend fun createAsset(name: String): Asset = throw UnsupportedOperationException()
        override suspend fun updateAsset(asset: Asset) {}
        override suspend fun deleteAsset(id: String) {}
        override suspend fun saveNameplatePhoto(assetId: String, jpeg: ByteArray): String = ""
        override suspend fun getBaseline(assetId: String): Baseline? = saved
        override suspend fun saveBaseline(baseline: Baseline) { saved = baseline }
        override suspend fun updateThresholds(assetId: String, thresholds: Thresholds) {}
        override fun resolve(assetId: String, relativePath: String): File = File("unused/$assetId/$relativePath")
    }

    private fun cfg(refreshMinHealthy: Int = 3) = AppConfig(calibration = Calibration(refreshMinHealthy = refreshMinHealthy))

    private fun store() = SampleStore(tmp.root)

    /** Labels a healthy sample with the given absolute acoustic + sensor features. */
    private fun addHealthy(store: SampleStore, id: String, assetId: String, abs: FloatArray, absSensors: FloatArray) {
        store.addPending(id, assetId, FloatArray(260), 0.1, abs, absSensors, null)
        store.label(id, 0, SampleSource.HUMAN)
    }

    @Test
    fun canRefreshCountsOnlyHealthySamplesWithAbsAndSensors() = runBlocking {
        val s = store()
        addHealthy(s, "h1", "a1", floatArrayOf(1f, 0f, 0f), floatArrayOf(1f, 2f, 3f, 4f))
        // Healthy but missing abs -> not eligible.
        s.addPending("h2", "a1", FloatArray(260), 0.1, null, floatArrayOf(1f, 2f, 3f, 4f), null)
        s.label("h2", 0, SampleSource.HUMAN)
        // Faulty with abs -> not eligible (not healthy).
        s.addPending("f1", "a1", FloatArray(260), 3.0, floatArrayOf(0f, 1f, 0f), floatArrayOf(1f, 2f, 3f, 4f), null)
        s.label("f1", 1, SampleSource.HUMAN)

        val useCase = RefreshBaselineUseCase(FakeAssetRepository(), s) { cfg() }
        assertEquals(1, useCase.canRefresh("a1"))
    }

    @Test
    fun refreshFailsBelowRefreshMinHealthy() = runBlocking {
        val s = store()
        addHealthy(s, "h1", "a1", floatArrayOf(1f, 0f, 0f), floatArrayOf(1f, 2f, 3f, 4f))
        val useCase = RefreshBaselineUseCase(FakeAssetRepository(), s) { cfg(refreshMinHealthy = 3) }

        val result = useCase.refresh("a1")
        assertTrue(result is Outcome.Err)
    }

    @Test
    fun refreshComputesMeanAndCosineSpreadFromSyntheticVectors() = runBlocking {
        val s = store()
        // Three orthonormal-ish vectors: mean cosine distance to their centroid is identical
        // for all three by symmetry, so spread and rawStd are analytically known.
        addHealthy(s, "h1", "a1", floatArrayOf(1f, 0f, 0f), floatArrayOf(1f, 2f, 3f, 4f))
        addHealthy(s, "h2", "a1", floatArrayOf(0f, 1f, 0f), floatArrayOf(3f, 2f, 1f, 4f))
        addHealthy(s, "h3", "a1", floatArrayOf(0f, 0f, 1f), floatArrayOf(2f, 2f, 2f, 4f))

        val repo = FakeAssetRepository()
        val useCase = RefreshBaselineUseCase(repo, s) { cfg(refreshMinHealthy = 3) }

        val result = useCase.refresh("a1")
        val baseline = result.getOrNull() ?: error("expected Outcome.Ok, got $result")

        assertEquals(1f / 3f, baseline.meanFeature[0], 1e-4f)
        assertEquals(1f / 3f, baseline.meanFeature[1], 1e-4f)
        assertEquals(1f / 3f, baseline.meanFeature[2], 1e-4f)

        // Each vector is equidistant (cosine) from the mean by symmetry: dot = 1/3, |v|=1,
        // |mean| = sqrt(1/3), cos = (1/3)/sqrt(1/3) = sqrt(1/3) ~ 0.57735 -> distance ~0.42265.
        assertEquals(0.42265, baseline.spread, 1e-3)
        // All three distances are identical -> population std is exactly 0, floored to spreadFloor.
        assertEquals(0.02, baseline.rawStd, 1e-9)

        assertEquals(2.0, baseline.imuIndexMean, 1e-9)
        assertEquals(2.0, baseline.gyroIndexMean, 1e-9)
        assertEquals(2.0, baseline.magIndexMean, 1e-9)
        assertEquals(4.0, baseline.magRmsMean, 1e-9)
        assertEquals(0.8165, baseline.imuIndexStd, 1e-3)
        assertEquals(0.0, baseline.gyroIndexStd, 1e-9)
        assertEquals(0.8165, baseline.magIndexStd, 1e-3)
        assertEquals(0.0, baseline.magRmsStd, 1e-9)
        assertEquals(3, baseline.clipCount)

        assertTrue("expected the new baseline to be persisted via saveBaseline", repo.saved != null)
        assertEquals("a1", repo.saved?.assetId)
    }

    @Test
    fun canRefreshIsZeroWhenNoSamples() = runBlocking {
        val repo = FakeAssetRepository()
        val useCase = RefreshBaselineUseCase(repo, store()) { cfg() }
        assertEquals(0, useCase.canRefresh("unknown"))
        assertNull(repo.saved)
    }
}
