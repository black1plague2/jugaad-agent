package com.jugaad.agent.ui.assets

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jugaad.agent.di.ServiceLocator
import com.jugaad.agent.domain.model.Asset
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

    fun deleteAsset(id: String) = viewModelScope.launch {
        services.assetRepository.deleteAsset(id)
    }
}
