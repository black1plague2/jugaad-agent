package com.jugaad.agent.ui.assets

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jugaad.agent.di.ServiceLocator
import com.jugaad.agent.domain.model.Asset
import com.jugaad.agent.domain.model.MachineStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class AssetListViewModel(private val services: ServiceLocator) : ViewModel() {

    val assets: StateFlow<List<Asset>> =
        services.assetRepository.observeAssets()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val engineStatus = services.engineStatus
    val socName = services.socInfo.displayName

    /** Derived display state for the Equipment list's last-status StatusChip: the most
     *  recent measurement's status per asset. Refetched on every [assets] emission (and on
     *  [refresh]) so a new reading updates the chip without needing a process restart, since
     *  AssetListScreen itself never calls back in to trigger a reload. */
    private val _lastStatus = MutableStateFlow<Map<String, MachineStatus>>(emptyMap())
    val lastStatus: StateFlow<Map<String, MachineStatus>> = _lastStatus

    init {
        // AssetListScreen.kt (owned by another agent) never calls refresh(), so this has to
        // be self-refreshing: re-poll periodically in addition to reacting to the asset list
        // itself changing, otherwise a new reading on an existing asset never updates its chip.
        viewModelScope.launch {
            assets.collect { list -> refreshStatuses(list) }
        }
        viewModelScope.launch {
            while (true) {
                delay(5_000)
                refreshStatuses(assets.value)
            }
        }
    }

    /** Re-fetches the latest status for every known asset. Safe to call repeatedly (e.g. from
     *  the screen's resume/lifecycle callback) - it just re-reads, it never skips an asset id. */
    fun refresh() = viewModelScope.launch {
        refreshStatuses(assets.value)
    }

    private fun refreshStatuses(list: List<Asset>) {
        list.forEach { asset ->
            viewModelScope.launch {
                val status = services.diagnosisRepository.getLatest(asset.id)?.status ?: return@launch
                // update{} makes this read-modify-write atomic; plain `.value = .value + ...`
                // drops entries when many of these per-asset coroutines race each other.
                _lastStatus.update { it + (asset.id to status) }
            }
        }
    }

    fun deleteAsset(id: String) = viewModelScope.launch {
        services.deleteAssetAndSamples(id)
    }
}
