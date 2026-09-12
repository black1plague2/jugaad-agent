package com.jugaad.agent.ui.result

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jugaad.agent.core.Logx
import com.jugaad.agent.di.ServiceLocator
import com.jugaad.agent.domain.model.Diagnosis
import com.jugaad.agent.domain.model.FaultClass
import com.jugaad.agent.viz.ReportRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class ResultViewModel(
    private val services: ServiceLocator,
    private val assetId: String,
    private val diagnosisId: String,
) : ViewModel() {

    data class State(
        val loading: Boolean = true,
        val diagnosis: Diagnosis? = null,
        val assetName: String = "",
        val spectrogram: Bitmap? = null,
        val heuristicClassifier: Boolean = true,
        val flRuntimeReady: Boolean = false,
        val labelSaved: Boolean = false,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    init {
        viewModelScope.launch {
            val d = services.diagnosisRepository.get(assetId, diagnosisId)
            val asset = services.assetRepository.getAsset(assetId)
            val bmp = d?.spectrogramPng?.let { rel ->
                withContext(Dispatchers.IO) {
                    val f = services.assetRepository.resolve(assetId, rel)
                    if (f.exists()) BitmapFactory.decodeFile(f.path) else null
                }
            }
            _state.value = _state.value.copy(
                loading = false,
                diagnosis = d,
                assetName = asset?.name ?: "Asset",
                spectrogram = bmp,
                heuristicClassifier = !services.engineStatus.value.cnnReady,
            )
        }
        viewModelScope.launch {
            services.flRuntime.collect { runtime ->
                _state.value = _state.value.copy(flRuntimeReady = runtime != null)
            }
        }
    }

    fun confirmLabel(faultClass: FaultClass) {
        val store = services.flRuntime.value?.store ?: return
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                store.label(diagnosisId, faultClass.index)
            }
            if (ok) {
                _state.value = _state.value.copy(labelSaved = true)
            }
        }
    }

    fun shareReport(context: Context) {
        val s = _state.value
        val d = s.diagnosis ?: return
        viewModelScope.launch {
            runCatching {
                val out = File(services.reportsDir, "report_${d.id}.png")
                withContext(Dispatchers.IO) {
                    ReportRenderer.render(out, s.assetName, d, s.spectrogram)
                }
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", out)
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "image/png"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, "Jugaad Agent report, ${s.assetName}")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(
                    Intent.createChooser(send, "Share report").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }.onFailure { Logx.e("shareReport failed", it) }
        }
    }
}
