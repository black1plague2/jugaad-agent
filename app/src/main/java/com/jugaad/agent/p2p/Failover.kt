package com.jugaad.agent.p2p

import android.content.Context
import com.jugaad.agent.core.Logx
import com.jugaad.agent.core.config.AppConfig
import com.jugaad.agent.fl.EventType
import com.jugaad.agent.fl.NetworkState
import com.jugaad.agent.fl.NodeConfig
import com.jugaad.agent.fl.NodeRole
import com.jugaad.agent.ui.services
import kotlinx.coroutines.delay

/**
 * Self-healing owner failover (v4 plan §4): when the group owner has gone unreachable
 * for [AppConfig.Sync.failoverAfterFailures] consecutive client syncs, the node with the
 * lowest [NodeConfig.deviceId] among those still seen within [AppConfig.Sync.staleMinutes]
 * (excluding the previous owner) takes over — tears down whatever group it's in, forms a
 * fresh one, and starts serving. [takeOver] is also the manual "Promote this node to
 * owner" path.
 */
object Failover {

    private const val STEP_DELAY_MS = 500L

    /** Pure decision so it can be table-tested without WiFi Direct or a real FlRuntime. */
    fun shouldTakeOver(config: NodeConfig, network: NetworkState, nowMs: Long, cfg: AppConfig): Boolean {
        if (!cfg.sync.autoFailover) return false
        if (config.consecutiveSyncFailures < cfg.sync.failoverAfterFailures) return false

        val staleMs = cfg.sync.staleMinutes * 60_000L
        val previousOwnerId = network.nodes.firstOrNull { it.isOwner }?.deviceId

        val candidateIds = network.nodes
            .filter { it.deviceId != previousOwnerId && nowMs - it.lastSeenMs <= staleMs }
            .map { it.deviceId }
            .toMutableSet()
        candidateIds += config.deviceId

        return candidateIds.min() == config.deviceId
    }

    suspend fun takeOver(context: Context): Boolean {
        val flRuntime = context.services().flRuntime.value
        if (flRuntime == null) {
            Logx.w("Failover: flRuntime not ready yet, cannot take over")
            return false
        }

        val manager = WifiDirectManager(context)
        manager.start()
        try {
            manager.removeGroup()
            delay(STEP_DELAY_MS)
            manager.createGroup()
            delay(STEP_DELAY_MS)
        } finally {
            manager.stop()
        }

        FlSyncService.start(context)
        flRuntime.updateConfig { it.copy(lastRole = NodeRole.OWNER) }
        flRuntime.addEvent(EventType.FAILOVER, "owner unreachable, ${flRuntime.config.value.name} took over")
        return true
    }
}
