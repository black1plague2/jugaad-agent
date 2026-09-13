package com.jugaad.agent.ui.assetdetail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jugaad.agent.core.Outcome
import com.jugaad.agent.di.ServiceLocator
import com.jugaad.agent.domain.model.Asset
import com.jugaad.agent.domain.model.Baseline
import com.jugaad.agent.domain.model.Diagnosis
import com.jugaad.agent.domain.model.Sensitivity
import com.jugaad.agent.domain.usecase.CalibrationRecord
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

    // Calibrate thresholds / drift / reference-refresh (v4). The section is disabled in the
    // UI when the FL sample store hasn't finished loading yet (calibrationAvailable).
    val calibration = MutableStateFlow<CalibrationRecord?>(null)
    val calibrating = MutableStateFlow(false)
    val calibrateMessage = MutableStateFlow<String?>(null)
    // This asset's own labelled-healthy sample count, kept even when it is below
    // calibration.minHealthy and calibration.value is therefore still null - lets the UI tell
    // "zero samples" apart from "some samples, not enough yet".
    val healthyCount = MutableStateFlow(0)
    val canRefresh = MutableStateFlow(0)
    val refreshing = MutableStateFlow(false)
    val calibrationAvailable: Boolean get() = services.flRuntime.value?.store != null

    init {
        refresh()
        refreshCalibration()
        refreshBaselineEligibility()
    }

    fun refresh() {
        viewModelScope.launch {
            baseline.value = services.assetRepository.getBaseline(assetId)
            latest.value = services.diagnosisRepository.getLatest(assetId)
        }
    }

    fun refreshCalibration() {
        viewModelScope.launch {
            val useCase = services.calibrateUseCase()
            calibration.value = useCase?.observe(assetId)
            healthyCount.value = useCase?.healthyCount(assetId) ?: 0
        }
    }

    fun refreshBaselineEligibility() {
        viewModelScope.launch {
            canRefresh.value = services.refreshBaselineUseCase()?.canRefresh(assetId) ?: 0
        }
    }

    fun calibrate() {
        if (calibrating.value) return
        val useCase = services.calibrateUseCase() ?: return
        calibrating.value = true
        viewModelScope.launch {
            when (val outcome = useCase.apply(assetId)) {
                is Outcome.Ok -> {
                    calibration.value = outcome.value
                    healthyCount.value = outcome.value.nHealthy
                    calibrateMessage.value = "Thresholds updated: T1 %.1f, T2 %.1f".format(outcome.value.t1, outcome.value.t2)
                }
                is Outcome.Err -> calibrateMessage.value = "Calibration failed: ${outcome.message}"
            }
            calibrating.value = false
        }
    }

    fun refreshBaseline() {
        if (refreshing.value) return
        val useCase = services.refreshBaselineUseCase() ?: return
        refreshing.value = true
        viewModelScope.launch {
            when (val outcome = useCase.refresh(assetId)) {
                is Outcome.Ok -> {
                    baseline.value = outcome.value
                    calibrateMessage.value = "Reference measurement refreshed"
                    refreshBaselineEligibility()
                }
                is Outcome.Err -> calibrateMessage.value = "Refresh failed: ${outcome.message}"
            }
            refreshing.value = false
        }
    }

    fun dismissCalibrateMessage() {
        calibrateMessage.value = null
    }

    fun updateThresholds(t1: Double, t2: Double) {
        viewModelScope.launch {
            runCatching { Thresholds(t1, t2) }.getOrNull()?.let {
                services.assetRepository.updateThresholds(assetId, it)
            }
        }
    }

    fun setBenchTest(v: Boolean) {
        viewModelScope.launch {
            asset.value?.let { services.assetRepository.updateAsset(it.copy(benchTest = v)) }
        }
    }

    /** Changing sensitivity rescales this asset's thresholds to the new profile's default. */
    fun setSensitivity(v: Sensitivity) {
        viewModelScope.launch {
            asset.value?.let {
                services.assetRepository.updateAsset(
                    it.copy(sensitivity = v, thresholds = Thresholds.forSensitivity(v)),
                )
            }
        }
    }

    fun deleteAsset(onDone: () -> Unit) {
        viewModelScope.launch {
            services.deleteAssetAndSamples(assetId)
            onDone()
        }
    }
}
