package com.jugaad.agent.ui.createasset

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jugaad.agent.core.config.MachineCatalog
import com.jugaad.agent.core.config.MachineType
import com.jugaad.agent.di.ServiceLocator
import com.jugaad.agent.domain.model.Sensitivity
import com.jugaad.agent.ml.anomaly.Thresholds
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

private const val GENERIC_MACHINE_TYPE_ID = "generic"

class CreateAssetViewModel(private val services: ServiceLocator) : ViewModel() {

    data class State(
        val name: String = "",
        val photoJpeg: ByteArray? = null,
        val machineQuery: String = "",
        val machineResults: List<MachineType> = emptyList(),
        val selectedMachineTypeId: String = GENERIC_MACHINE_TYPE_ID,
        val selectedMachineTypeLabel: String = "Other rotating machine",
        val sensitivity: Sensitivity = Sensitivity.STANDARD,
        val benchTest: Boolean = false,
        val saving: Boolean = false,
        val createdAssetId: String? = null,
        val error: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    fun setName(v: String) { _state.value = _state.value.copy(name = v, error = null) }
    fun setPhoto(bytes: ByteArray?) { _state.value = _state.value.copy(photoJpeg = bytes) }
    fun setBenchTest(v: Boolean) { _state.value = _state.value.copy(benchTest = v) }
    fun setSensitivity(v: Sensitivity) { _state.value = _state.value.copy(sensitivity = v) }

    fun setMachineQuery(query: String) {
        val results = if (query.isBlank()) emptyList() else runCatching { MachineCatalog.search(query) }.getOrDefault(emptyList())
        _state.value = _state.value.copy(machineQuery = query, machineResults = results)
    }

    fun selectMachineType(type: MachineType) {
        _state.value = _state.value.copy(
            selectedMachineTypeId = type.id,
            selectedMachineTypeLabel = type.label,
            // Pre-fill from the machine type's default; the user can still override it below.
            sensitivity = type.resolvedSensitivity,
            machineQuery = "",
            machineResults = emptyList(),
        )
    }

    fun create() {
        val s = _state.value
        if (s.name.isBlank()) { _state.value = s.copy(error = "Enter a name"); return }
        if (s.saving) return
        _state.value = s.copy(saving = true, error = null)
        viewModelScope.launch {
            runCatching {
                val asset = services.assetRepository.createAsset(s.name)
                var updated = asset
                if (s.selectedMachineTypeId != GENERIC_MACHINE_TYPE_ID) {
                    updated = updated.copy(machineTypeId = s.selectedMachineTypeId)
                }
                if (s.benchTest) {
                    updated = updated.copy(benchTest = true)
                }
                if (s.sensitivity != Sensitivity.STANDARD) {
                    updated = updated.copy(sensitivity = s.sensitivity, thresholds = Thresholds.forSensitivity(s.sensitivity))
                }
                if (updated != asset) {
                    services.assetRepository.updateAsset(updated)
                }
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
