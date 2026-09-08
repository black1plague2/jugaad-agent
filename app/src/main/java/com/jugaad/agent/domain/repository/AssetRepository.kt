package com.jugaad.agent.domain.repository

import com.jugaad.agent.domain.model.Asset
import com.jugaad.agent.domain.model.Baseline
import com.jugaad.agent.ml.anomaly.Thresholds
import kotlinx.coroutines.flow.Flow

interface AssetRepository {
    fun observeAssets(): Flow<List<Asset>>
    suspend fun getAsset(id: String): Asset?
    suspend fun createAsset(name: String): Asset
    suspend fun updateAsset(asset: Asset)
    suspend fun deleteAsset(id: String)

    /** Persist a nameplate photo (JPEG bytes) inside the asset folder; returns relative path. */
    suspend fun saveNameplatePhoto(assetId: String, jpeg: ByteArray): String

    suspend fun getBaseline(assetId: String): Baseline?
    suspend fun saveBaseline(baseline: Baseline)
    suspend fun updateThresholds(assetId: String, thresholds: Thresholds)

    /** Absolute file for a relative path stored on an [Asset] / [com.jugaad.agent.domain.model.Diagnosis]. */
    fun resolve(assetId: String, relativePath: String): java.io.File
}
