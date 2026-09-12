package com.jugaad.agent.p2p

import com.jugaad.agent.core.config.ConfigStore
import com.jugaad.agent.fl.EventType
import com.jugaad.agent.fl.FlRuntime
import com.jugaad.agent.fl.FlSample
import com.jugaad.agent.fl.NetworkState
import com.jugaad.agent.fl.NodeConfig
import com.jugaad.agent.fl.SampleSource
import com.jugaad.agent.fl.VariantMetrics

/**
 * Seam between [FedAvgCoordinator] and the real fl-package runtime: lets the
 * coordinator's merge/accept-guard/promotion/sample-sharing logic be unit-tested with
 * a fake, no TFLite involved.
 */
interface FlPeer {
    val config: NodeConfig
    val network: NetworkState
    fun heldVariantIds(): List<String>
    fun metrics(id: String): VariantMetrics
    fun weights(id: String): FloatArray
    fun evaluateVal(id: String, w: FloatArray?): Float
    fun applyMerged(id: String, w: FloatArray, round: Int)
    fun applyNetwork(s: NetworkState)
    fun hasVariant(id: String): Boolean

    /** Applies an owner's replicated policy to this node's [ConfigStore]; true if it changed anything. */
    fun applyPolicy(policy: Map<String, String>): Boolean

    /** Local-only informational/failover events, merged into this node's copy of `network.events`. */
    fun addEvent(type: EventType, text: String)

    /** `sync.sharing.enabled && config.shareSamples` (v4 plan §5). */
    fun sharingEnabled(): Boolean

    /** This node's current [com.jugaad.agent.core.config.Features.schemaVersion]. */
    fun featureSchemaVersion(): Int

    /** This node's own labelled sample ids union its shared-pool ids, offered to a peer. */
    fun knownSampleIds(): Set<String>

    /** This node's own samples (labelled + pool) matching [ids], in wire form. */
    fun samplesFor(ids: Set<String>): List<SyncProtocol.FlSampleWire>

    /** Pool + own labelled samples not in [known], capped at [limit], in wire form. */
    fun poolSamplesExcept(known: Set<String>, limit: Int): List<SyncProtocol.FlSampleWire>

    /** Adds [samples] to this node's shared pool; @return how many were newly added. */
    fun acceptShared(samples: List<SyncProtocol.FlSampleWire>): Int
}

class RuntimePeer(private val runtime: FlRuntime) : FlPeer {
    override val config: NodeConfig get() = runtime.config.value
    override val network: NetworkState get() = runtime.network.value
    override fun heldVariantIds(): List<String> = runtime.heldVariantIds()
    override fun metrics(id: String): VariantMetrics = runtime.trainer(id).metrics.value
    override fun weights(id: String): FloatArray = runtime.trainer(id).currentWeights()
    override fun evaluateVal(id: String, w: FloatArray?): Float = runtime.trainer(id).evaluateVal(w)
    override fun applyMerged(id: String, w: FloatArray, round: Int) = runtime.trainer(id).applyMerged(w, round)
    override fun applyNetwork(s: NetworkState) = runtime.applyNetwork(s)
    override fun hasVariant(id: String): Boolean = runtime.trainers.containsKey(id)

    override fun applyPolicy(policy: Map<String, String>): Boolean = ConfigStore.applyPolicy(policy)

    override fun addEvent(type: EventType, text: String) = runtime.addEvent(type, text)

    override fun sharingEnabled(): Boolean =
        ConfigStore.effective.value.sharing.enabled && config.shareSamples

    override fun featureSchemaVersion(): Int = ConfigStore.effective.value.features.schemaVersion

    override fun knownSampleIds(): Set<String> =
        runtime.store.labelled().map { it.id }.toSet() + runtime.pool.ids()

    override fun samplesFor(ids: Set<String>): List<SyncProtocol.FlSampleWire> =
        (runtime.store.labelled() + runtime.pool.all())
            .filter { it.id in ids }
            .map { it.toWire(config.deviceId) }

    override fun poolSamplesExcept(known: Set<String>, limit: Int): List<SyncProtocol.FlSampleWire> =
        (runtime.pool.all() + runtime.store.labelled())
            .filter { it.id !in known }
            .take(limit)
            .map { it.toWire(config.deviceId) }

    override fun acceptShared(samples: List<SyncProtocol.FlSampleWire>): Int =
        runtime.pool.addAll(samples.map { it.toSample() })
}

/** [FlSample.origin] falls back to [selfDeviceId] for locally-captured (never-shared) samples. */
private fun FlSample.toWire(selfDeviceId: String) = SyncProtocol.FlSampleWire(
    id = id,
    origin = origin ?: selfDeviceId,
    assetId = assetId,
    machineTypeId = machineTypeId,
    x = x,
    label = label ?: 0,
    score = score,
    ts = ts,
)

private fun SyncProtocol.FlSampleWire.toSample() = FlSample(
    id = id,
    assetId = assetId,
    x = x,
    label = label,
    source = SampleSource.PEER,
    ts = ts,
    score = score,
    abs = null,
    absSensors = null,
    origin = origin,
    machineTypeId = machineTypeId,
)
