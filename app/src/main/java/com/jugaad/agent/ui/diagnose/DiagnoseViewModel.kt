package com.jugaad.agent.ui.diagnose

import android.Manifest
import androidx.annotation.RequiresPermission
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jugaad.agent.core.Outcome
import com.jugaad.agent.di.ServiceLocator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class DiagnoseViewModel(
    private val services: ServiceLocator,
    private val assetId: String,
) : ViewModel() {

    sealed interface Phase {
        data object Idle : Phase
        data object Capturing : Phase
        data object Analyzing : Phase
        data class Done(val diagnosisId: String) : Phase
        data class Error(val message: String) : Phase
    }

    val progress = services.captureCoordinator.progress
    private val _phase = MutableStateFlow<Phase>(Phase.Idle)
    val phase: StateFlow<Phase> = _phase

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun start() {
        if (_phase.value == Phase.Capturing || _phase.value == Phase.Analyzing) return
        _phase.value = Phase.Capturing
        viewModelScope.launch {
            when (val r = services.diagnoseUseCase().run(assetId)) {
                is Outcome.Ok -> _phase.value = Phase.Done(r.value.diagnosis.id)
                is Outcome.Err -> _phase.value = Phase.Error(r.message)
            }
        }
    }

    fun markAnalyzing() {
        if (_phase.value == Phase.Capturing) _phase.value = Phase.Analyzing
    }

    fun reset() { _phase.value = Phase.Idle }
}
