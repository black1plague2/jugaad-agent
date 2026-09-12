package com.jugaad.agent.ui.assets

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jugaad.agent.di.ServiceLocator
import com.jugaad.agent.domain.model.Asset
import com.jugaad.agent.domain.model.MachineStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class AssetListViewModel(private val services: ServiceLocator) : ViewModel() {

    val assets: StateFlow<List<Asset>> =
        services.assetRepository.observeAssets()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val engineStatus = services.engineStatus
    val socName = services.socInfo.displayName

    /** Derived display state for the Equipment list's last-status StatusChip: the most
     *  recent measurement's status per asset, looked up lazily as assets show up. */
    private val _lastStatus = MutableStateFlow<Map<String, MachineStatus>>(emptyMap())
    val lastStatus: StateFlow<Map<String, MachineStatus>> = _lastStatus

    init {
        viewModelScope.launch {
            assets.collect { list ->
                list.forEach { asset ->
                    if (_lastStatus.value.containsKey(asset.id)) return@forEach
                    launch {
                        val status = services.diagnosisRepository.getLatest(asset.id)?.status ?: return@launch
                        _lastStatus.value = _lastStatus.value + (asset.id to status)
                    }
                }
            }
        }
    }

    fun deleteAsset(id: String) = viewModelScope.launch {
        services.assetRepository.deleteAsset(id)
    }
}
