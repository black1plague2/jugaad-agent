package com.jugaad.agent.p2p

import android.content.Context
import com.jugaad.agent.core.config.ConfigStore
import com.jugaad.agent.fl.NodeConfig
import com.jugaad.agent.fl.NodeRole
import com.jugaad.agent.ui.services
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * On-demand client sync for callers (the ViewModel/AutoTrainer path, and [SyncWorker]'s
 * watchdog) that want to sync right now rather than wait for the periodic schedule.
 * Resolves the existing WiFi Direct group; a no-op (returns null) if this device is the
 * group owner, no group is formed yet, or the FL runtime isn't ready. Otherwise retries
 * the whole session per [com.jugaad.agent.core.config.Sync.retryBackoffMs], persists the
 * outcome onto [com.jugaad.agent.fl.NodeConfig] (role/owner address/failure streak), and
 * triggers [Failover] once the failure streak and node ordering call for it (v4 plan §4).
 */
object SyncNow {
    suspend fun asClient(context: Context): SyncResult? {
        val manager = WifiDirectManager(context)
        manager.start()
        try {
            val group = withTimeoutOrNull(3000) {
                manager.group.first { it.formed }
            } ?: manager.group.value

            val flRuntime = context.services().flRuntime.value
            val ownerAddress = group.ownerAddress
            if (!group.formed || group.isGroupOwner || ownerAddress == null || flRuntime == null) {
                return null
            }

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

            return result
        } finally {
            manager.stop()
        }
    }

    /** A session that never threw; [FedAvgCoordinator.runAsClient] swallows failures into this message shape. */
    private fun SyncResult.succeeded(): Boolean = !message.startsWith("fl sync: client failed")

    /** Pure failure-count/reset transition, pulled out of [asClient] so it's unit-testable
     * without WiFi Direct or a real [com.jugaad.agent.fl.FlRuntime]. */
    internal fun nextConfig(current: NodeConfig, ownerAddress: String, result: SyncResult): NodeConfig =
        if (result.succeeded()) {
            current.copy(lastRole = NodeRole.CLIENT, lastOwnerAddress = ownerAddress, consecutiveSyncFailures = 0)
        } else {
            current.copy(consecutiveSyncFailures = current.consecutiveSyncFailures + 1)
        }
}
