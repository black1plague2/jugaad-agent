package com.jugaad.agent.domain.usecase

import com.jugaad.agent.core.config.AppConfig
import com.jugaad.agent.domain.model.Asset
import com.jugaad.agent.domain.model.Baseline
import com.jugaad.agent.domain.repository.AssetRepository
import com.jugaad.agent.fl.FlConstants
import com.jugaad.agent.fl.SampleSource
import com.jugaad.agent.fl.SampleStore
import com.jugaad.agent.ml.anomaly.Thresholds
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** D3/D4 regression: [CalibrateUseCase.healthyCount] must report the real labelled-healthy
 * count for an asset even when [CalibrateUseCase.observe] returns null because it is still
 * below `calibration.minHealthy` - the asset detail screen uses this to tell "zero samples"
 * apart from "some samples, not enough yet" (ui/assetdetail/AssetDetailScreen.kt). */
class CalibrateUseCaseTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun sample(seed: Int) = FloatArray(FlConstants.INPUT_DIM) { seed.toFloat() }

    private val noopAssets = object : AssetRepository {
        override fun observeAssets(): Flow<List<Asset>> = emptyFlow()
        override suspend fun getAsset(id: String): Asset? = null
        override suspend fun createAsset(name: String): Asset = throw UnsupportedOperationException()
        override suspend fun updateAsset(asset: Asset) {}
        override suspend fun deleteAsset(id: String) {}
        override suspend fun saveNameplatePhoto(assetId: String, jpeg: ByteArray): String = ""
        override suspend fun getBaseline(assetId: String): Baseline? = null
        override suspend fun saveBaseline(baseline: Baseline) {}
        override suspend fun updateThresholds(assetId: String, thresholds: Thresholds) {}
        override fun resolve(assetId: String, relativePath: String): File = File(relativePath)
    }

    private fun useCase(store: SampleStore, cfg: AppConfig = AppConfig()) =
        CalibrateUseCase(noopAssets, store, { cfg })

    @Test
    fun healthyCountIsZeroWithNoSamples() = runBlocking {
        val store = SampleStore(tmp.root)
        val uc = useCase(store)
        assertEquals(0, uc.healthyCount("asset1"))
        assertNull(uc.observe("asset1"))
    }

    @Test
    fun healthyCountReflectsLabelledHealthySamplesBelowMinHealthy() = runBlocking {
        val store = SampleStore(tmp.root)
        store.addPending("s1", "asset1", sample(1), 0.4)
        store.addPending("s2", "asset1", sample(2), 0.5)
        store.addPending("s3", "asset1", sample(3), 0.6)
        store.label("s1", 0, SampleSource.HUMAN)
        store.label("s2", 0, SampleSource.HUMAN)
        store.label("s3", 0, SampleSource.HUMAN)

        val uc = useCase(store, AppConfig(calibration = com.jugaad.agent.core.config.Calibration(minHealthy = 5)))
        // 3 labelled-healthy samples, fewer than minHealthy(5): observe() is null but the real
        // count is still 3, not 0 - this is the D3/D4 defect the UI must be able to show.
        assertEquals(3, uc.healthyCount("asset1"))
        assertNull(uc.observe("asset1"))
    }

    @Test
    fun healthyCountExcludesOtherAssetsAndNonHealthyLabels() = runBlocking {
        val store = SampleStore(tmp.root)
        store.addPending("s1", "asset1", sample(1), 0.4)
        store.addPending("s2", "asset1", sample(2), 0.5)
        store.addPending("s3", "assetOther", sample(3), 0.6)
        store.label("s1", 0, SampleSource.HUMAN)
        store.label("s2", 1, SampleSource.HUMAN)
        store.label("s3", 0, SampleSource.HUMAN)

        val uc = useCase(store)
        assertEquals(1, uc.healthyCount("asset1"))
    }
}
