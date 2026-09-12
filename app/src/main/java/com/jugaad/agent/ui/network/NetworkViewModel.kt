package com.jugaad.agent.ui.network

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jugaad.agent.core.config.AppConfig
import com.jugaad.agent.core.config.ConfigStore
import com.jugaad.agent.di.ServiceLocator
import com.jugaad.agent.domain.model.Diagnosis
import com.jugaad.agent.domain.model.FaultClass
import com.jugaad.agent.domain.model.MachineStatus
import com.jugaad.agent.fl.FeatureImportance
import com.jugaad.agent.fl.FlRuntime
import com.jugaad.agent.fl.FlVariants
import com.jugaad.agent.fl.NetworkState
import com.jugaad.agent.fl.NodeCard
import com.jugaad.agent.fl.NodeConfig
import com.jugaad.agent.fl.NodeMode
import com.jugaad.agent.fl.Recovery
import com.jugaad.agent.fl.TrainBudget
import com.jugaad.agent.fl.VariantMetrics
import com.jugaad.agent.p2p.Failover
import com.jugaad.agent.p2p.FlSyncService
import com.jugaad.agent.p2p.LanDiscovery
import com.jugaad.agent.p2p.SyncBus
import com.jugaad.agent.p2p.SyncNow
import com.jugaad.agent.p2p.SyncResult
import com.jugaad.agent.p2p.SyncScheduler
import com.jugaad.agent.p2p.WifiDirectManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Newest [Diagnosis] across every asset (per Network's "Latest reading" heatmap card), with its
 * spectrogram already decoded the same way [com.jugaad.agent.ui.result.ResultViewModel] does. */
data class LatestReading(val assetName: String, val status: MachineStatus, val timestampMs: Long, val png: Bitmap?)

data class NetworkUiState(
    val modelReady: Boolean = false,
    val config: NodeConfig? = null,
    val network: NetworkState? = null,
    val championId: String = FlVariants.CHAMPION_DEFAULT,
    val challengerId: String? = null,
    val heldIds: List<String> = emptyList(),
    val heldMetrics: Map<String, VariantMetrics> = emptyMap(),
    val trainCounts: IntArray = IntArray(FaultClass.entries.size),
    val valCounts: IntArray = IntArray(FaultClass.entries.size),
    val peerCounts: IntArray = IntArray(FaultClass.entries.size),
    val pending: Int = 0,
    val confusion: Array<IntArray>? = null,
    val displayNodes: List<NodeCard> = emptyList(),
    val peers: List<WifiDirectManager.Peer> = emptyList(),
    /** Owners advertising on the local WiFi network, by node name (this device filtered out). */
    val lanPeers: List<LanDiscovery.LanPeer> = emptyList(),
    /** [SyncBus.autoJoin] status line (waiting / joining / joined / off). */
    val autoJoin: String? = null,
    val group: WifiDirectManager.GroupInfo = WifiDirectManager.GroupInfo(false, false, null),
    val serving: Boolean = false,
    val lastSync: SyncResult? = null,
    val schedulerEnabled: Boolean = false,
    val busy: Boolean = false,
    val message: String? = null,
    val deviceSnapshot: TrainBudget.Snapshot? = null,
    val appConfig: AppConfig? = null,
    val importance: List<FeatureImportance.Group> = emptyList(),
    val poolCount: Int = 0,
    val poolByOrigin: Map<String, Int> = emptyMap(),
    val recoveryReport: Recovery.Report? = null,
)

private data class DatasetCounts(val train: IntArray, val validation: IntArray, val pending: Int, val peer: IntArray)

/**
 * Drives the Federated network screen: champion/challenger state from
 * [ServiceLocator.flRuntime] (network standings, node registry, per-variant metrics,
 * local dataset/confusion), plus WiFi Direct peer/group state and sync actions. Owns the
 * single [WifiDirectManager] for this screen's lifetime.
 */
class NetworkViewModel(
    private val services: ServiceLocator,
    private val appContext: Context,
) : ViewModel() {

    private val manager = WifiDirectManager(appContext)
    private val lan = services.lanDiscovery

    private val _ui = MutableStateFlow(NetworkUiState(schedulerEnabled = SyncScheduler.isEnabled(appContext)))
    val ui: StateFlow<NetworkUiState> = _ui

    private val _latestReading = MutableStateFlow<LatestReading?>(null)
    val latestReading: StateFlow<LatestReading?> = _latestReading

    private var metricsJob: Job? = null

    init {
        manager.start()
        lan.startDiscovery()
        refreshLatestReading()

        viewModelScope.launch {
            manager.peers.collect { list -> _ui.value = _ui.value.copy(peers = list) }
        }
        viewModelScope.launch {
            combine(lan.peers, services.flRuntime.flatMapLatest { it?.config ?: flowOf(null) }) { list, cfg ->
                list.filterNot { it.deviceId == cfg?.deviceId }
            }.collect { list -> _ui.value = _ui.value.copy(lanPeers = list) }
        }
        viewModelScope.launch {
            manager.group.collect { g ->
                _ui.value = _ui.value.copy(group = g)
                recomputeDisplayNodes()
            }
        }
        viewModelScope.launch {
            SyncBus.serving.collect { s -> _ui.value = _ui.value.copy(serving = s) }
        }
        viewModelScope.launch {
            SyncBus.autoJoin.collect { s -> _ui.value = _ui.value.copy(autoJoin = s) }
        }
        viewModelScope.launch {
            SyncBus.last.collect { r -> _ui.value = _ui.value.copy(lastSync = r) }
        }
        viewModelScope.launch {
            services.flRuntime.collect { runtime ->
                _ui.value = _ui.value.copy(modelReady = runtime != null, recoveryReport = runtime?.recovery)
                if (runtime != null) {
                    refreshDataset()
                    refreshHeldIds()
                    recomputeDisplayNodes()
                }
            }
        }
        viewModelScope.launch {
            ConfigStore.effective.collect { c -> _ui.value = _ui.value.copy(appConfig = c) }
        }
        viewModelScope.launch {
            services.flRuntime.flatMapLatest { it?.config ?: flowOf(null) }.collect { c ->
                _ui.value = _ui.value.copy(config = c)
                refreshHeldIds()
            }
        }
        viewModelScope.launch {
            services.flRuntime.flatMapLatest { it?.network ?: flowOf(null) }.collect { n ->
                _ui.value = _ui.value.copy(network = n)
                recomputeDisplayNodes()
            }
        }
        viewModelScope.launch {
            services.flRuntime.flatMapLatest { it?.championId ?: flowOf(FlVariants.CHAMPION_DEFAULT) }.collect { id ->
                _ui.value = _ui.value.copy(championId = id)
                refreshConfusion()
                refreshHeldIds()
            }
        }
        viewModelScope.launch {
            services.flRuntime.flatMapLatest { it?.challengerId ?: flowOf(null) }.collect { id ->
                _ui.value = _ui.value.copy(challengerId = id)
                refreshHeldIds()
            }
        }
        viewModelScope.launch {
            services.flRuntime.flatMapLatest { it?.store?.revision ?: flowOf(0) }.collect { refreshDataset() }
        }

        // Device resource snapshot (SoC/cores/thermal/battery/memory/per-variant training cost),
        // refreshed every 10 s while this screen (and thus this ViewModel) is alive.
        viewModelScope.launch {
            while (true) {
                _ui.value = _ui.value.copy(deviceSnapshot = TrainBudget.snapshot(appContext))
                delay(10_000L)
            }
        }
    }

    // --- Latest reading (Network tab heatmap card) --------------------------

    /** Newest [Diagnosis] across every asset, with its spectrogram PNG decoded the same way
     * [com.jugaad.agent.ui.result.ResultViewModel] resolves `spectrogramPng` to a file. */
    private fun refreshLatestReading() {
        viewModelScope.launch {
            val reading = withContext(Dispatchers.IO) {
                val assets = runCatching { services.assetRepository.observeAssets().first() }.getOrDefault(emptyList())
                var bestAssetName: String? = null
                var bestDiagnosis: Diagnosis? = null
                for (asset in assets) {
                    val d = runCatching { services.diagnosisRepository.getLatest(asset.id) }.getOrNull() ?: continue
                    val current = bestDiagnosis
                    if (current == null || d.timestampMs > current.timestampMs) {
                        bestDiagnosis = d
                        bestAssetName = asset.name
                    }
                }
                val diagnosis = bestDiagnosis ?: return@withContext null
                val bmp = diagnosis.spectrogramPng?.let { rel ->
                    val f = services.assetRepository.resolve(diagnosis.assetId, rel)
                    if (f.exists()) BitmapFactory.decodeFile(f.path) else null
                }
                LatestReading(assetName = bestAssetName ?: "Asset", status = diagnosis.status, timestampMs = diagnosis.timestampMs, png = bmp)
            }
            _latestReading.value = reading
        }
    }

    // --- Dataset / confusion -----------------------------------------------

    private fun refreshDataset() {
        val runtime = services.flRuntime.value ?: return
        viewModelScope.launch {
            val counts = withContext(Dispatchers.Default) {
                val tc = IntArray(FaultClass.entries.size)
                runtime.store.labelledTrain().forEach { s -> s.label?.let { l -> if (l in tc.indices) tc[l]++ } }
                val vc = IntArray(FaultClass.entries.size)
                runtime.store.labelledVal().forEach { s -> s.label?.let { l -> if (l in vc.indices) vc[l]++ } }
                val pc = IntArray(FaultClass.entries.size)
                runtime.pool.all().forEach { s -> s.label?.let { l -> if (l in pc.indices) pc[l]++ } }
                DatasetCounts(tc, vc, runtime.store.pendingCount(), pc)
            }
            _ui.value = _ui.value.copy(
                trainCounts = counts.train,
                valCounts = counts.validation,
                pending = counts.pending,
                peerCounts = counts.peer,
            )
        }
        refreshConfusion()
        refreshImportance()
        refreshPool()
    }

    private fun refreshImportance() {
        val runtime = services.flRuntime.value ?: return
        viewModelScope.launch {
            val groups = withContext(Dispatchers.Default) { runCatching { runtime.importance() }.getOrDefault(emptyList()) }
            _ui.value = _ui.value.copy(importance = groups)
        }
    }

    private fun refreshPool() {
        val runtime = services.flRuntime.value ?: return
        viewModelScope.launch {
            val (count, byOrigin) = withContext(Dispatchers.Default) {
                runtime.pool.count() to runtime.pool.countByOrigin()
            }
            _ui.value = _ui.value.copy(poolCount = count, poolByOrigin = byOrigin)
        }
    }

    private fun refreshConfusion() {
        val runtime = services.flRuntime.value ?: return
        val championId = _ui.value.championId
        viewModelScope.launch {
            val matrix = withContext(Dispatchers.Default) {
                runCatching { runtime.trainer(championId).confusionVal() }.getOrNull()
            }
            _ui.value = _ui.value.copy(confusion = matrix)
        }
    }

    private fun refreshHeldIds() {
        val runtime = services.flRuntime.value ?: return
        val ids = runtime.heldVariantIds()
        _ui.value = _ui.value.copy(heldIds = ids)
        metricsJob?.cancel()
        if (ids.isEmpty()) return
        metricsJob = viewModelScope.launch {
            val flows = ids.map { id -> runtime.trainer(id).metrics }
            combine(flows) { arr -> arr.associateBy { it.variantId } }.collect { map ->
                _ui.value = _ui.value.copy(heldMetrics = map)
            }
        }
    }

    private fun recomputeDisplayNodes() {
        val runtime = services.flRuntime.value ?: return
        val net = _ui.value.network ?: return
        val self = runtime.localNodeCard(isOwner = _ui.value.group.isGroupOwner)
        val nodes = if (net.nodes.any { it.deviceId == self.deviceId }) net.nodes else net.nodes + self
        _ui.value = _ui.value.copy(displayNodes = nodes)
    }

    // --- Node identity / config ---------------------------------------------

    private fun withRuntime(block: (FlRuntime) -> Unit) {
        services.flRuntime.value?.let(block)
    }

    fun setName(name: String) = withRuntime { it.updateConfig { c -> c.copy(name = name) } }
    fun setMode(mode: NodeMode) = withRuntime { it.updateConfig { c -> c.copy(mode = mode) } }
    fun setChallenger(id: String?) = withRuntime { it.updateConfig { c -> c.copy(pinnedChallenger = id) } }
    fun setAutoTrain(enabled: Boolean) = withRuntime { it.updateConfig { c -> c.copy(autoTrain = enabled) } }
    fun setAutoSync(enabled: Boolean) = withRuntime { it.updateConfig { c -> c.copy(autoSync = enabled) } }
    fun setShareSamples(enabled: Boolean) = withRuntime { it.updateConfig { c -> c.copy(shareSamples = enabled) } }

    fun resetConfigOverrides() {
        ConfigStore.resetOverrides(appContext)
    }

    fun promoteToOwner() {
        if (_ui.value.busy) return
        _ui.value = _ui.value.copy(busy = true)
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { Failover.takeOver(appContext) }
            }.onSuccess { ok ->
                _ui.value = _ui.value.copy(busy = false, message = if (ok) "Promoted to owner" else "Promotion failed")
            }.onFailure { e ->
                _ui.value = _ui.value.copy(busy = false, message = "Promotion failed: ${e.message}")
            }
        }
    }

    // --- Training ------------------------------------------------------------

    fun trainNow() {
        if (_ui.value.busy) return
        val runtime = services.flRuntime.value ?: return
        _ui.value = _ui.value.copy(busy = true)
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.Default) { runtime.trainHeld() }
            }.onSuccess { results ->
                val summary = results.joinToString(", ") { "${it.variantId} round ${it.round}" }
                _ui.value = _ui.value.copy(busy = false, message = "Trained: $summary")
                refreshConfusion()
                refreshImportance()
            }.onFailure { e ->
                _ui.value = _ui.value.copy(busy = false, message = "Training failed: ${e.message}")
            }
        }
    }

    // --- Peers / group ---------------------------------------------------------

    fun discover() {
        manager.discover()
        lan.stopDiscovery()
        lan.startDiscovery()
    }
    fun connect(address: String) = manager.connect(address)
    fun createGroup() = manager.createGroup()
    fun removeGroup() = manager.removeGroup()

    // --- Sync ------------------------------------------------------------------

    fun startServing() = FlSyncService.start(appContext)
    fun stopServing() = FlSyncService.stop(appContext)

    /** Routed through [SyncNow.asClient] (not a direct [com.jugaad.agent.p2p.FedAvgCoordinator]
     * call) so a manual tap counts failures/triggers failover exactly like the scheduler does. */
    fun syncNow() = launchSync(null)

    /** Sync with one owner picked by node name from the Nearby list (its LAN address). */
    fun syncWith(host: String) = launchSync(host)

    private fun launchSync(host: String?) {
        if (_ui.value.busy) return
        if (services.flRuntime.value == null) return
        if (_ui.value.serving) {
            _ui.value = _ui.value.copy(message = "This device is the owner; other phones sync to it")
            return
        }
        _ui.value = _ui.value.copy(busy = true)
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { SyncNow.asClient(appContext, host) }
            }.onSuccess { result ->
                // SyncNow.asClient already publishes to SyncBus.last, which this ViewModel
                // observes (see init), so lastSync updates on its own.
                val message = result?.message
                    ?: "No owner found: no WiFi Direct group and nobody is serving on this WiFi"
                _ui.value = _ui.value.copy(busy = false, message = message)
                refreshDataset()
            }.onFailure { e ->
                _ui.value = _ui.value.copy(busy = false, message = "Sync failed: ${e.message}")
            }
        }
    }

    fun toggleScheduler(enabled: Boolean) {
        if (enabled) SyncScheduler.enable(appContext) else SyncScheduler.disable(appContext)
        _ui.value = _ui.value.copy(schedulerEnabled = enabled)
    }

    fun dismissMessage() {
        _ui.value = _ui.value.copy(message = null)
    }

    /** Manual refresh for the shell's top-bar action: re-reads dataset/importance/pool/confusion
     * and the device snapshot immediately rather than waiting for the next scheduled tick. */
    fun refresh() {
        refreshDataset()
        refreshHeldIds()
        recomputeDisplayNodes()
        refreshLatestReading()
        _ui.value = _ui.value.copy(deviceSnapshot = TrainBudget.snapshot(appContext))
    }

    override fun onCleared() {
        // lan is process-wide (AutoJoin keeps browsing); only the WiFi Direct receiver is ours.
        manager.stop()
        super.onCleared()
    }
}
