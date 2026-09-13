package com.jugaad.agent.di

import android.content.Context
import com.jugaad.agent.BuildConfig
import com.jugaad.agent.core.Constants
import com.jugaad.agent.core.Logx
import com.jugaad.agent.core.config.ConfigStore
import com.jugaad.agent.core.config.MachineCatalog
import com.jugaad.agent.data.repository.AssetRepositoryImpl
import com.jugaad.agent.data.repository.DiagnosisRepositoryImpl
import com.jugaad.agent.data.storage.JsonFileStore
import com.jugaad.agent.domain.model.InferenceBackend
import com.jugaad.agent.domain.repository.AssetRepository
import com.jugaad.agent.domain.repository.DiagnosisRepository
import com.jugaad.agent.domain.usecase.CalibrateUseCase
import com.jugaad.agent.domain.usecase.CaptureBaselineUseCase
import com.jugaad.agent.domain.usecase.DiagnoseUseCase
import com.jugaad.agent.domain.usecase.RefreshBaselineUseCase
import com.jugaad.agent.fl.AutoTrainer
import com.jugaad.agent.p2p.AutoJoin
import com.jugaad.agent.p2p.LanDiscovery
import com.jugaad.agent.fl.EventType
import com.jugaad.agent.fl.FlRuntime
import com.jugaad.agent.fl.FlVariants
import com.jugaad.agent.fl.LiteRtFaultClassifier
import com.jugaad.agent.fl.NetworkStateIO
import com.jugaad.agent.fl.NodeConfigIO
import com.jugaad.agent.fl.NodeRole
import com.jugaad.agent.fl.Recovery
import com.jugaad.agent.fl.SampleStore
import com.jugaad.agent.fl.TrainBudget
import com.jugaad.agent.fl.VariantKind
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
import com.jugaad.agent.p2p.FlSyncService
import com.jugaad.agent.sensor.AudioCapture
import com.jugaad.agent.sensor.CaptureCoordinator
import com.jugaad.agent.sensor.ImuCapture
import com.jugaad.agent.sensor.MotionCapture
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

    init {
        ConfigStore.load(app)
        MachineCatalog.load(app)
    }

    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val store = JsonFileStore(app.filesDir)

    val socInfo = SocDetector.info

    val assetRepository: AssetRepository = AssetRepositoryImpl(store, appScope)
    val diagnosisRepository: DiagnosisRepository = DiagnosisRepositoryImpl(store)

    private val audioCapture = AudioCapture()
    val imuCapture = ImuCapture(app) // still used directly by ChecklistScreen.isRestingStill()
    val captureCoordinator = CaptureCoordinator(audioCapture, MotionCapture(app))

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

    private val _flRuntime = MutableStateFlow<FlRuntime?>(null)
    val flRuntime: StateFlow<FlRuntime?> = _flRuntime

    /** One mDNS advertiser/browser for the process: AutoJoin, the sync service and the Devices tab share it. */
    val lanDiscovery: LanDiscovery by lazy { LanDiscovery(appContext) }

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

            var executorchLoaded = false
            if (BuildConfig.EXECUTORCH_ENABLED) {
                val et = ExecuTorchFaultClassifier(installed.cnnXnnpack, installed.cnnQnn)
                if (et.load()) {
                    executorchLoaded = true
                    _classifier.value = et
                    engineStatus.value = engineStatus.value.copy(
                        cnnReady = true,
                        cnnBackendLabel = et.backend.longLabel,
                    )
                } else {
                    Logx.i("ExecuTorch not loaded — keeping HeuristicFaultClassifier")
                }
            }

            if (installed.flHeads.isNotEmpty()) {
                runCatching {
                    // Self-healing (v4 §4): repair any corrupt fl/*.json or asset json, and
                    // quarantine wrong-length weight files, before anything tries to load them.
                    val recovery = Recovery.repair(appContext.filesDir)

                    val flDir = File(appContext.filesDir, "fl")
                    val nodeConfig = MutableStateFlow(NodeConfigIO.load(flDir))
                    val networkState = MutableStateFlow(NetworkStateIO.load(flDir, FlVariants.CHAMPION_DEFAULT))
                    val sampleStore = SampleStore(flDir)
                    val threads = TrainBudget.snapshot(appContext).threads

                    val runtime = FlRuntime.build(
                        FlVariants.ALL, installed.flHeads, sampleStore, nodeConfig, networkState, flDir, threads, recovery,
                    )
                    AutoTrainer(runtime, appScope, appContext).start()
                    AutoJoin(runtime, appScope, appContext, lanDiscovery).start()
                    _flRuntime.value = runtime

                    for (name in recovery.repairedFiles) runtime.addEvent(EventType.RECOVER, "repaired corrupt file: $name")
                    for (name in recovery.staleWeights) runtime.addEvent(EventType.RECOVER, "quarantined stale weights: $name")

                    if (!executorchLoaded) {
                        _classifier.value = LiteRtFaultClassifier(runtime)
                        engineStatus.value = engineStatus.value.copy(
                            cnnReady = true,
                            cnnBackendLabel = InferenceBackend.LITERT.longLabel,
                        )
                    }
                    val mlpIds = FlVariants.ALL.filter { it.kind == VariantKind.MLP }.map { it.id }
                    Logx.i("LiteRT heads loaded: ${mlpIds.joinToString(", ")}; strategies: ${runtime.trainers.keys.joinToString(", ")}")

                    // Self-healing role restore (v4 §4, extended by v13 §7): resume serving if
                    // this node was the group owner before it last stopped (crash/reboot).
                    // Otherwise, if auto-join is on, still bring the mesh service up in CLIENT
                    // mode from this foreground app-launch path, so a later in-background
                    // takeover (AutoJoin's failover retry) only ever needs an in-process mode
                    // flip and never calls startForegroundService itself (H6).
                    if (nodeConfig.value.lastRole == NodeRole.OWNER) {
                        FlSyncService.start(appContext)
                    } else if (ConfigStore.effective.value.sync.autoJoin) {
                        FlSyncService.ensureClientRunning(appContext)
                    }
                }.onFailure { t -> Logx.w("LiteRT heads failed to load — FL disabled this session", t) }
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

    /**
     * Deletes an asset and every training sample it produced on this phone, so a deleted
     * piece of equipment stops training the local models (and, via a future sync, the
     * fleet) the moment it's removed. Both AssetListViewModel.deleteAsset and
     * AssetDetailViewModel.deleteAsset route through this single function so the two
     * delete entry points cannot diverge. Safe to call before the FL runtime has warmed
     * up — the asset is still deleted, just with 0/0 samples removed.
     *
     * Cross-phone retraction — telling peers to drop the samples this asset already
     * shared into their pools — is out of scope; those copies remain on other nodes.
     */
    suspend fun deleteAssetAndSamples(assetId: String) {
        val runtime = _flRuntime.value
        val own = runtime?.store?.removeForAsset(assetId) ?: 0
        val pooled = runtime?.pool?.removeForAsset(assetId) ?: 0
        assetRepository.deleteAsset(assetId)
        Logx.i("asset delete: removed $own own and $pooled pooled samples for $assetId")
    }

    fun captureBaselineUseCase() = CaptureBaselineUseCase(
        coordinator = captureCoordinator,
        features = featureExtractor,
        scorer = anomalyScorer,
        assets = assetRepository,
    )

    /** Null until the FL runtime (and thus a [SampleStore] to calibrate against) is warmed up. */
    fun calibrateUseCase(): CalibrateUseCase? {
        val sampleStore = _flRuntime.value?.store ?: return null
        return CalibrateUseCase(assetRepository, sampleStore) { ConfigStore.effective.value }
    }

    /** Null until the FL runtime is warmed up — same precondition as [calibrateUseCase]. */
    fun refreshBaselineUseCase(): RefreshBaselineUseCase? {
        val sampleStore = _flRuntime.value?.store ?: return null
        return RefreshBaselineUseCase(assetRepository, sampleStore) { ConfigStore.effective.value }
    }

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
        sampleStore = _flRuntime.value?.store,
        cfg = { ConfigStore.effective.value },
        catalog = { MachineCatalog.byId(it) },
        calibrate = calibrateUseCase(),
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
