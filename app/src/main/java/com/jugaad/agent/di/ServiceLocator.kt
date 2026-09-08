package com.jugaad.agent.di

import android.content.Context
import com.jugaad.agent.BuildConfig
import com.jugaad.agent.core.Constants
import com.jugaad.agent.core.Logx
import com.jugaad.agent.data.repository.AssetRepositoryImpl
import com.jugaad.agent.data.repository.DiagnosisRepositoryImpl
import com.jugaad.agent.data.storage.JsonFileStore
import com.jugaad.agent.domain.repository.AssetRepository
import com.jugaad.agent.domain.repository.DiagnosisRepository
import com.jugaad.agent.domain.usecase.CaptureBaselineUseCase
import com.jugaad.agent.domain.usecase.DiagnoseUseCase
import com.jugaad.agent.ml.FeatureExtractor
import com.jugaad.agent.ml.ModelInstaller
import com.jugaad.agent.ml.advisor.GemmaAdvisor
import com.jugaad.agent.ml.advisor.MaintenanceAdvisor
import com.jugaad.agent.ml.advisor.TemplateAdvisor
import com.jugaad.agent.ml.anomaly.AnomalyScorer
import com.jugaad.agent.ml.classifier.ExecuTorchFaultClassifier
import com.jugaad.agent.ml.classifier.FaultClassifier
import com.jugaad.agent.ml.classifier.HeuristicFaultClassifier
import com.jugaad.agent.ml.executorch.SocDetector
import com.jugaad.agent.sensor.AudioCapture
import com.jugaad.agent.sensor.CaptureCoordinator
import com.jugaad.agent.sensor.ImuCapture
import com.jugaad.agent.viz.AndroidSpectrogramRenderer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * Hand-rolled DI. The graph is small and the hackathon spec explicitly wants a
 * readable, framework-light project, so no Hilt/Koin.
 *
 * The optional ML engines (ExecuTorch CNN, Gemma advisor) are loaded off the main
 * thread in [warmUp]; until then — and forever, if the flags are off or the model
 * files are missing — the app runs the heuristic + template path.
 */
class ServiceLocator private constructor(app: Context) {

    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val store = JsonFileStore(app.filesDir)

    val socInfo = SocDetector.info

    val assetRepository: AssetRepository = AssetRepositoryImpl(store, appScope)
    val diagnosisRepository: DiagnosisRepository = DiagnosisRepositoryImpl(store)

    private val audioCapture = AudioCapture()
    val imuCapture = ImuCapture(app)
    val captureCoordinator = CaptureCoordinator(audioCapture, imuCapture)

    private val featureExtractor = FeatureExtractor()
    private val anomalyScorer = AnomalyScorer()
    private val pngRenderer = AndroidSpectrogramRenderer()

    private val modelInstaller = ModelInstaller(app)
    private val appContext = app.applicationContext

    // --- Swappable engines ------------------------------------------------
    private val _classifier = MutableStateFlow<FaultClassifier>(HeuristicFaultClassifier())
    val classifier: StateFlow<FaultClassifier> = _classifier

    private val _advisor = MutableStateFlow<MaintenanceAdvisor>(TemplateAdvisor())
    val advisor: StateFlow<MaintenanceAdvisor> = _advisor

    private val templateAdvisor = TemplateAdvisor()

    val engineStatus = MutableStateFlow(EngineStatus())

    data class EngineStatus(
        val cnnReady: Boolean = false,
        val cnnBackendLabel: String = "Heuristic",
        val gemmaReady: Boolean = false,
        val warmedUp: Boolean = false,
    )

    fun warmUp() {
        appScope.launch {
            val installed = modelInstaller.install()

            if (BuildConfig.EXECUTORCH_ENABLED) {
                val et = ExecuTorchFaultClassifier(installed.cnnXnnpack, installed.cnnQnn)
                if (et.load()) {
                    _classifier.value = et
                    engineStatus.value = engineStatus.value.copy(
                        cnnReady = true,
                        cnnBackendLabel = et.backend.longLabel,
                    )
                } else {
                    Logx.i("ExecuTorch not loaded — keeping HeuristicFaultClassifier")
                }
            }

            if (BuildConfig.GEMMA_ENABLED) {
                val gemma = GemmaAdvisor(appContext, installed.gemma)
                if (gemma.load()) {
                    _advisor.value = gemma
                    engineStatus.value = engineStatus.value.copy(gemmaReady = true)
                }
            }

            engineStatus.value = engineStatus.value.copy(warmedUp = true)
            Logx.i("warmUp done: $engineStatus")
        }
    }

    fun captureBaselineUseCase() = CaptureBaselineUseCase(
        coordinator = captureCoordinator,
        features = featureExtractor,
        scorer = anomalyScorer,
        assets = assetRepository,
    )

    fun diagnoseUseCase() = DiagnoseUseCase(
        coordinator = captureCoordinator,
        features = featureExtractor,
        scorer = anomalyScorer,
        classifier = _classifier.value,
        advisor = _advisor.value,
        fallbackAdvisor = templateAdvisor,
        assets = assetRepository,
        history = diagnosisRepository,
        pngRenderer = pngRenderer,
    )

    val reportsDir: File by lazy { File(appContext.cacheDir, Constants.REPORTS_DIR).apply { mkdirs() } }

    companion object {
        @Volatile private var instance: ServiceLocator? = null
        fun get(context: Context): ServiceLocator =
            instance ?: synchronized(this) {
                instance ?: ServiceLocator(context.applicationContext).also { instance = it }
            }
    }
}
