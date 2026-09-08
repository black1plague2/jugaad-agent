package com.jugaad.agent.ui.createasset

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jugaad.agent.di.ServiceLocator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class CreateAssetViewModel(private val services: ServiceLocator) : ViewModel() {

    data class State(
        val name: String = "",
        val photoJpeg: ByteArray? = null,
        val saving: Boolean = false,
        val createdAssetId: String? = null,
        val error: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    fun setName(v: String) { _state.value = _state.value.copy(name = v, error = null) }
    fun setPhoto(bytes: ByteArray?) { _state.value = _state.value.copy(photoJpeg = bytes) }

    fun create() {
        val s = _state.value
        if (s.name.isBlank()) { _state.value = s.copy(error = "Enter a name"); return }
        if (s.saving) return
        _state.value = s.copy(saving = true, error = null)
        viewModelScope.launch {
            runCatching {
                val asset = services.assetRepository.createAsset(s.name)
                s.photoJpeg?.let { services.assetRepository.saveNameplatePhoto(asset.id, it) }
                asset.id
            }.onSuccess { id ->
                _state.value = _state.value.copy(saving = false, createdAssetId = id)
            }.onFailure { e ->
                _state.value = _state.value.copy(saving = false, error = e.message ?: "Failed to create")
            }
        }
    }
}
