package com.jugaad.agent.data.repository

import com.jugaad.agent.core.Constants
import com.jugaad.agent.core.Logx
import com.jugaad.agent.data.model.AssetDto
import com.jugaad.agent.data.model.BaselineDto
import com.jugaad.agent.data.storage.JsonFileStore
import com.jugaad.agent.domain.model.Asset
import com.jugaad.agent.domain.model.Baseline
import com.jugaad.agent.domain.repository.AssetRepository
import com.jugaad.agent.ml.anomaly.Thresholds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

class AssetRepositoryImpl(
    private val store: JsonFileStore,
    scope: CoroutineScope,
) : AssetRepository {

    private val assets = MutableStateFlow<List<Asset>>(emptyList())

    init {
        scope.launch { assets.value = scanAssets() }
    }

    override fun observeAssets(): Flow<List<Asset>> = assets.asStateFlow()

    override suspend fun getAsset(id: String): Asset? =
        assets.value.firstOrNull { it.id == id } ?: readAsset(id)

    override suspend fun createAsset(name: String): Asset {
        val id = UUID.randomUUID().toString().take(8)
        val asset = Asset(id = id, name = name.trim().ifBlank { "Asset $id" }, createdAtMs = System.currentTimeMillis())
        store.write(assetFile(id), AssetDto.from(asset))
        refresh()
        Logx.i("createAsset id=$id name=${asset.name}")
        return asset
    }

    override suspend fun updateAsset(asset: Asset) {
        store.write(assetFile(asset.id), AssetDto.from(asset))
        refresh()
    }

    override suspend fun deleteAsset(id: String) {
        store.delete(store.dir(Constants.ASSETS_DIR, id))
        refresh()
    }

    override suspend fun saveNameplatePhoto(assetId: String, jpeg: ByteArray): String {
        val rel = "nameplate.jpg"
        store.writeBytes(store.file(Constants.ASSETS_DIR, assetId, rel), jpeg)
        readAsset(assetId)?.let { updateAsset(it.copy(nameplatePhoto = rel)) }
        return rel
    }

    override suspend fun getBaseline(assetId: String): Baseline? =
        store.readOrNull<BaselineDto>(baselineFile(assetId))?.toDomain()

    override suspend fun saveBaseline(baseline: Baseline) {
        store.write(baselineFile(baseline.assetId), BaselineDto.from(baseline))
        readAsset(baseline.assetId)?.let { updateAsset(it.copy(hasBaseline = true)) }
        Logx.i("saveBaseline asset=${baseline.assetId} spread=${baseline.spread}")
    }

    override suspend fun updateThresholds(assetId: String, thresholds: Thresholds) {
        readAsset(assetId)?.let { updateAsset(it.copy(thresholds = thresholds)) }
    }

    override fun resolve(assetId: String, relativePath: String): File =
        store.file(Constants.ASSETS_DIR, assetId, relativePath)

    // --- internals ---------------------------------------------------------

    private suspend fun refresh() { assets.value = scanAssets() }

    private suspend fun scanAssets(): List<Asset> {
        val parent = store.dir(Constants.ASSETS_DIR)
        return store.listDirs(parent)
            .mapNotNull { store.readOrNull<AssetDto>(File(it, "asset.json"))?.toDomain() }
            .sortedByDescending { it.createdAtMs }
    }

    private suspend fun readAsset(id: String): Asset? =
        store.readOrNull<AssetDto>(assetFile(id))?.toDomain()

    private fun assetFile(id: String) = store.file(Constants.ASSETS_DIR, id, "asset.json")
    private fun baselineFile(id: String) = store.file(Constants.ASSETS_DIR, id, "baseline.json")
}
