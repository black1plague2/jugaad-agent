package com.jugaad.agent.p2p

import android.content.Context
import com.jugaad.agent.core.Logx
import com.jugaad.agent.core.config.ConfigStore
import com.jugaad.agent.fl.FlRuntime
import com.jugaad.agent.fl.NodeConfig
import com.jugaad.agent.fl.NodeRole
import com.jugaad.agent.ui.services
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * On-demand client sync for callers (the ViewModel/AutoTrainer path, and [SyncWorker]'s
 * watchdog) that want to sync right now rather than wait for the periodic schedule.
 * The owner is, in order: the [host] the caller names, the owner of a formed WiFi Direct
 * group, or an owner advertising on the local WiFi network ([LanDiscovery]). Returns null
 * when this device is the owner, no owner can be found, or the FL runtime isn't ready.
 * Otherwise retries the whole session per [com.jugaad.agent.core.config.Sync.retryBackoffMs],
 * persists the outcome onto [com.jugaad.agent.fl.NodeConfig] (role/owner address/failure
 * streak), and triggers [Failover] once the failure streak and node ordering call for it.
 */
object SyncNow {
    /** Manual taps, [AutoJoin], [com.jugaad.agent.fl.AutoTrainer] and [SyncWorker] all land
     * here; one client session at a time keeps two merges from racing on the same weights. */
    private val lock = Mutex()

    suspend fun asClient(context: Context, host: String? = null): SyncResult? = lock.withLock {
        val flRuntime = context.services().flRuntime.value ?: return null
        val ownerAddress = host ?: resolveOwner(context, flRuntime) ?: return null
        Logx.i("fl sync: client target $ownerAddress")

        val coordinator = FedAvgCoordinator(RuntimePeer(flRuntime))
        val cfg = ConfigStore.effective.value

        var result = coordinator.runAsClient(ownerAddress)
        for (backoffMs in cfg.sync.retryBackoffMs) {
            if (result.succeeded()) break
            delay(backoffMs.toLong())
            result = coordinator.runAsClient(ownerAddress)
        }

        flRuntime.updateConfig { c -> nextConfig(c, ownerAddress, result) }
        SyncBus.last.value = result

        if (!result.succeeded() &&
            Failover.shouldTakeOver(flRuntime.config.value, flRuntime.network.value, System.currentTimeMillis(), cfg)
        ) {
            Failover.takeOver(context)
        }

        result
    }

    /** WiFi Direct group owner if a group is formed, else the owner advertising on this WiFi
     * network; null if this device is the owner or nobody can be found. */
    private suspend fun resolveOwner(context: Context, flRuntime: FlRuntime): String? {
        val manager = WifiDirectManager(context)
        manager.start()
        try {
            val group = withTimeoutOrNull(3000) { manager.group.first { it.formed } } ?: manager.group.value
            if (group.formed && group.ownerAddress != null) {
                if (!group.isGroupOwner) return group.ownerAddress
                if (SyncBus.serving.value) return null
                // A group this phone owns but no longer serves: fall through to the LAN path.
            }
        } finally {
            manager.stop()
        }
        if (SyncBus.serving.value) return null

        // The process-wide discovery is normally already running (AutoJoin starts it); give a
        // cold start a moment to hear the first advertisement.
        val me = flRuntime.config.value.deviceId
        val lan = context.services().lanDiscovery
        lan.startDiscovery()
        val peers = (withTimeoutOrNull(LAN_SCAN_MS) { lan.peers.first { list -> list.any { it.deviceId != me } } }
            ?: lan.peers.value).filterNot { it.deviceId == me }
        val pick = LanDiscovery.pickOwner(peers, flRuntime.config.value.lastOwnerAddress)
        Logx.i("fl sync: lan scan found ${peers.size} owner(s) ${peers.map { it.name }}, picked ${pick?.name}")
        return pick?.host
    }

    private const val LAN_SCAN_MS = 3000L

    /** Pure failure-count/reset transition, pulled out of [asClient] so it's unit-testable
     * without WiFi Direct or a real [com.jugaad.agent.fl.FlRuntime]. */
    internal fun nextConfig(current: NodeConfig, ownerAddress: String, result: SyncResult): NodeConfig =
        if (result.succeeded()) {
            current.copy(lastRole = NodeRole.CLIENT, lastOwnerAddress = ownerAddress, consecutiveSyncFailures = 0)
        } else {
            current.copy(consecutiveSyncFailures = current.consecutiveSyncFailures + 1)
        }
}

/** A session that never threw; [FedAvgCoordinator.runAsClient] swallows failures into this message shape. */
internal fun SyncResult.succeeded(): Boolean = !message.startsWith("fl sync: client failed")
