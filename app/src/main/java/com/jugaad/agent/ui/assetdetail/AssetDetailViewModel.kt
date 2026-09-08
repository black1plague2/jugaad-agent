package com.jugaad.agent.ui.assetdetail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jugaad.agent.di.ServiceLocator
import com.jugaad.agent.domain.model.Asset
import com.jugaad.agent.domain.model.Baseline
import com.jugaad.agent.domain.model.Diagnosis
import com.jugaad.agent.ml.anomaly.Thresholds
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class AssetDetailViewModel(
    private val services: ServiceLocator,
    private val assetId: String,
) : ViewModel() {

    val asset: StateFlow<Asset?> =
        services.assetRepository.observeAssets()
            .map { list -> list.firstOrNull { it.id == assetId } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val baseline = MutableStateFlow<Baseline?>(null)
    val latest = MutableStateFlow<Diagnosis?>(null)

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            baseline.value = services.assetRepository.getBaseline(assetId)
            latest.value = services.diagnosisRepository.getLatest(assetId)
        }
    }

    fun updateThresholds(t1: Double, t2: Double) {
        viewModelScope.launch {
            runCatching { Thresholds(t1, t2) }.getOrNull()?.let {
                services.assetRepository.updateThresholds(assetId, it)
            }
        }
    }

    fun deleteAsset(onDone: () -> Unit) {
        viewModelScope.launch {
            services.assetRepository.deleteAsset(assetId)
            onDone()
        }
    }
}
