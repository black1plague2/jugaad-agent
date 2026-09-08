package com.jugaad.agent.data.repository

import com.jugaad.agent.core.Constants
import com.jugaad.agent.data.model.DiagnosisDto
import com.jugaad.agent.data.storage.JsonFileStore
import com.jugaad.agent.domain.model.Diagnosis
import com.jugaad.agent.domain.repository.DiagnosisRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import java.io.File

class DiagnosisRepositoryImpl(
    private val store: JsonFileStore,
) : DiagnosisRepository {

    /** Bumped after every save so Compose collectors re-read the folder. */
    private val revision = MutableStateFlow(0L)

    override fun observeHistory(assetId: String): Flow<List<Diagnosis>> =
        revision.map { withContext(Dispatchers.IO) { readHistory(assetId) } }

    override suspend fun getLatest(assetId: String): Diagnosis? =
        withContext(Dispatchers.IO) { readHistory(assetId).firstOrNull() }

    override suspend fun get(assetId: String, diagnosisId: String): Diagnosis? =
        store.readOrNull<DiagnosisDto>(historyDir(assetId).resolve("$diagnosisId.json"))?.toDomain()

    override suspend fun save(diagnosis: Diagnosis, spectrogramPng: ByteArray?): Diagnosis {
        val dir = historyDir(diagnosis.assetId)
        var record = diagnosis
        if (spectrogramPng != null) {
            val rel = "history/${diagnosis.id}.png"
            store.writeBytes(store.file(Constants.ASSETS_DIR, diagnosis.assetId, rel), spectrogramPng)
            record = record.copy(spectrogramPng = rel)
        }
        store.write(dir.resolve("${diagnosis.id}.json"), DiagnosisDto.from(record))
        revision.value = System.nanoTime()
        return record
    }

    private fun readHistory(assetId: String): List<Diagnosis> =
        store.listFiles(historyDir(assetId), "json")
            .mapNotNull { f -> runCatching { store.json.decodeFromString<DiagnosisDto>(f.readText()) }.getOrNull() }
            .map { it.toDomain() }
            .sortedByDescending { it.timestampMs }

    private fun historyDir(assetId: String): File =
        store.dir(Constants.ASSETS_DIR, assetId, "history")
}
