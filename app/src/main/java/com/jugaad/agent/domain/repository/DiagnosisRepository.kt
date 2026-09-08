package com.jugaad.agent.domain.repository

import com.jugaad.agent.domain.model.Diagnosis
import kotlinx.coroutines.flow.Flow

interface DiagnosisRepository {
    /** Newest first. */
    fun observeHistory(assetId: String): Flow<List<Diagnosis>>
    suspend fun getLatest(assetId: String): Diagnosis?
    suspend fun get(assetId: String, diagnosisId: String): Diagnosis?

    /** Persists the record and (optionally) its spectrogram PNG bytes. */
    suspend fun save(diagnosis: Diagnosis, spectrogramPng: ByteArray?): Diagnosis
}
