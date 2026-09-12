package com.jugaad.agent.fl

import com.jugaad.agent.core.config.ConfigStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * Composition holder exposed by ServiceLocator as `val flRuntime: StateFlow<FlRuntime?>`.
 * Owns this node's champion/challenger selection on top of the per-variant
 * [Strategy]s, and persists [config]/[network] under `dir`. [pool] holds peer-contributed
 * samples (v4 plan §5) and [recovery] is the startup self-healing report (v4 §4) —
 * both computed by [build]/[ServiceLocator] before this runtime exists.
 */
class FlRuntime(
    val store: SampleStore,
    val trainers: Map<String, Strategy>,
    val config: StateFlow<NodeConfig>,
    val network: StateFlow<NetworkState>,
    private val dir: File,
    val pool: SharedPool,
    val recovery: Recovery.Report,
) {
    private val _championId = MutableStateFlow(network.value.championId)
    val championId: StateFlow<String> = _championId

    /** Bumps whenever [pool] receives new peer samples; [SampleStore.revision] covers this node's own. */
    val dataRevision: StateFlow<Int> get() = pool.revision

    /** This node's own labelled samples plus every peer sample in [pool] (v4 plan §4/§5). */
    fun trainingSamples(): List<FlSample> = store.labelled() + pool.all()

    /** "What the champion listens to" (v4 §6), grouped by acoustic band + sensor. */
    fun importance(): List<FeatureImportance.Group> {
        val id = championId.value
        val trainer = trainers[id] ?: return emptyList()
        return FeatureImportance.compute(trainer.currentWeights(), FlVariants.byId(id), ConfigStore.effective.value)
    }

    private val _challengerId = MutableStateFlow(resolveChallengerId())
    val challengerId: StateFlow<String?> = _challengerId

    private fun resolveChallengerId(): String? =
        config.value.pinnedChallenger ?: network.value.assignments[config.value.deviceId]

    /** Champion + challenger (only when [NodeConfig.mode] is EXPERIMENTAL), limited to installed variants. */
    fun heldVariantIds(): List<String> {
        val ids = LinkedHashSet<String>()
        ids += championId.value
        if (config.value.mode == NodeMode.EXPERIMENTAL) {
            challengerId.value?.let { ids += it }
        }
        return ids.filter { trainers.containsKey(it) }
    }

    fun trainer(id: String): Strategy =
        trainers[id] ?: error("FlRuntime.trainer: no trainer registered for variant '$id'")

    suspend fun trainHeld(): List<VariantMetrics> = heldVariantIds().map { trainer(it).train() }

    fun updateConfig(f: (NodeConfig) -> NodeConfig) {
        val updated = f(config.value)
        @Suppress("UNCHECKED_CAST")
        (config as MutableStateFlow<NodeConfig>).value = updated
        NodeConfigIO.save(dir, updated)
        _challengerId.value = updated.pinnedChallenger ?: network.value.assignments[updated.deviceId]
    }

    /** Persists, switches the champion, and adopts this node's network assignment unless pinned. */
    fun applyNetwork(state: NetworkState) {
        persistAndPublish(state)
        _championId.value = state.championId
        if (config.value.pinnedChallenger == null) {
            _challengerId.value = state.assignments[config.value.deviceId]
        }
    }

    fun localNodeCard(isOwner: Boolean): NodeCard {
        val cfg = config.value
        val champMetrics = trainers[championId.value]?.metrics?.value
        val challId = challengerId.value
        val challMetrics = challId?.let { trainers[it]?.metrics?.value }
        return NodeCard(
            deviceId = cfg.deviceId,
            name = cfg.name,
            mode = cfg.mode,
            challenger = challId,
            isOwner = isOwner,
            champRound = champMetrics?.round ?: 0,
            champValAcc = champMetrics?.valAcc ?: -1f,
            challValAcc = challMetrics?.valAcc ?: -1f,
            nTrain = champMetrics?.nTrain ?: 0,
            nVal = champMetrics?.nVal ?: 0,
            lastSeenMs = System.currentTimeMillis(),
        )
    }

    /** Local-only events (TRAIN etc.), merged into this node's copy of `network.events`. */
    fun addEvent(type: EventType, text: String) {
        val current = network.value
        val event = NetworkEvent(System.currentTimeMillis(), type, text)
        persistAndPublish(current.copy(events = (current.events + event).takeLast(50)))
    }

    private fun persistAndPublish(state: NetworkState) {
        @Suppress("UNCHECKED_CAST")
        (network as MutableStateFlow<NetworkState>).value = state
        NetworkStateIO.save(dir, state)
    }

    companion object {
        /**
         * Builds one [Strategy] per [specs] entry: [CentroidStrategy] for
         * [VariantKind.CENTROID] (no TFLite asset needed), [VariantTrainer] otherwise —
         * skipped when its asset isn't in [mlpAssets] (Decision 3, v3). [threads] caps
         * each [FlModel]'s interpreter thread count (see [TrainBudget]). Every strategy
         * trains on `store.labelled() + pool.all()` (v4 §4), not the raw [store], so a
         * peer sample received over a sync counts too. [recovery] is [Recovery.repair]'s
         * report from before this runtime was built — ServiceLocator's job, not this one's.
         */
        fun build(
            specs: List<VariantSpec>,
            mlpAssets: Map<String, File>,
            store: SampleStore,
            config: StateFlow<NodeConfig>,
            network: StateFlow<NetworkState>,
            dir: File,
            threads: Int,
            recovery: Recovery.Report,
        ): FlRuntime {
            val pool = SharedPool(dir) { ConfigStore.effective.value }
            val samplesProvider: () -> List<FlSample> = { store.labelled() + pool.all() }
            val pendingProvider: () -> List<FlSample> = { store.pending() }

            val trainers = LinkedHashMap<String, Strategy>()
            for (spec in specs) {
                when (spec.kind) {
                    VariantKind.CENTROID -> trainers[spec.id] = CentroidStrategy(spec, samplesProvider, dir)
                    VariantKind.MLP -> {
                        val asset = spec.asset ?: continue
                        val assetFile = mlpAssets[File(asset).name] ?: continue
                        trainers[spec.id] = VariantTrainer(spec, FlModel(assetFile, spec, threads), samplesProvider, pendingProvider, dir)
                    }
                }
            }
            return FlRuntime(store, trainers, config, network, dir, pool, recovery)
        }
    }
}
