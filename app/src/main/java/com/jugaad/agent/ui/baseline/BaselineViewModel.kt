package com.jugaad.agent.ui.baseline

import androidx.annotation.RequiresPermission
import android.Manifest
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jugaad.agent.core.Outcome
import com.jugaad.agent.di.ServiceLocator
import com.jugaad.agent.domain.usecase.CaptureBaselineUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class BaselineViewModel(
    private val services: ServiceLocator,
    private val assetId: String,
) : ViewModel() {

    sealed interface Phase {
        data object Idle : Phase
        data class Capturing(val clip: Int, val total: Int) : Phase
        data class Processing(val clip: Int, val total: Int) : Phase
        data class Done(val spread: Double, val imuIndex: Double) : Phase
        data class Error(val message: String) : Phase
    }

    val progress = services.captureCoordinator.progress
    private val _phase = MutableStateFlow<Phase>(Phase.Idle)
    val phase: StateFlow<Phase> = _phase

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun start() {
        if (_phase.value is Phase.Capturing || _phase.value is Phase.Processing) return
        _phase.value = Phase.Capturing(1, 3)
        viewModelScope.launch {
            val result = services.captureBaselineUseCase().run(assetId) { ev ->
                _phase.value = when (ev) {
                    is CaptureBaselineUseCase.Event.ClipStarted -> Phase.Capturing(ev.index, ev.total)
                    is CaptureBaselineUseCase.Event.ClipDone -> Phase.Processing(ev.index, ev.total)
                    is CaptureBaselineUseCase.Event.Finished ->
                        Phase.Done(ev.baseline.spread, ev.baseline.imuIndexMean)
                    is CaptureBaselineUseCase.Event.Failed -> Phase.Error(ev.message)
                }
            }
            if (result is Outcome.Err && _phase.value !is Phase.Error) {
                _phase.value = Phase.Error(result.message)
            }
        }
    }

    fun reset() { _phase.value = Phase.Idle }
}
