package com.jugaad.agent.p2p

import android.content.Context
import com.jugaad.agent.core.Logx
import com.jugaad.agent.core.config.AppConfig
import com.jugaad.agent.fl.EventType
import com.jugaad.agent.fl.NetworkState
import com.jugaad.agent.fl.NodeConfig
import com.jugaad.agent.fl.NodeRole
import com.jugaad.agent.ui.services
import kotlinx.coroutines.CancellationException
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
        return isLowestCandidate(config, network, nowMs, cfg.sync.staleMinutes)
    }

    /** Whether [config]'s deviceId is the lowest among still-fresh nodes (excluding the previous
     * owner), regardless of the failure streak or [AppConfig.Sync.autoFailover] (v13 follow-up
     * §3): factored out of [shouldTakeOver] so [shouldTakeOverAfterFailedRetry] can ask this on
     * its own, to keep its streak override from firing for a node that would take over anyway. */
    fun isLowestCandidate(config: NodeConfig, network: NetworkState, nowMs: Long, staleMinutes: Int): Boolean {
        val staleMs = staleMinutes * 60_000L
        val previousOwnerId = network.nodes.firstOrNull { it.isOwner }?.deviceId

        val candidateIds = network.nodes
            .filter { it.deviceId != previousOwnerId && nowMs - it.lastSeenMs <= staleMs }
            .map { it.deviceId }
            .toMutableSet()
        candidateIds += config.deviceId

        return candidateIds.min() == config.deviceId
    }

    /**
     * Pure decision for an owner already serving ([FlSyncService]'s loop): every phone that is
     * itself an owner advertises on [LanDiscovery] (only [FlSyncService.onStartCommand] calls
     * `advertise`), so [otherAdvertisedOwners] is exactly the set of other currently-serving
     * owners' deviceIds this scan found. [FlSyncService.checkStepDown] only calls this once a
     * candidate has answered [OwnerProbe.ping] on two consecutive checks, so a frozen owner
     * (mDNS record still resolvable, process not) never appears here. The lowest deviceId across
     * that set and [myId] keeps serving, the same rule [shouldTakeOver] uses, so every phone
     * evaluating it independently converges on one survivor without a round of messages.
     */
    fun shouldStepDown(myId: String, otherAdvertisedOwners: Collection<String>): Boolean =
        otherAdvertisedOwners.any { it < myId }

    /** Result of [decideTakeover]: whether to take over, or which live owner to stand down for. */
    sealed interface TakeoverDecision {
        object TakeOver : TakeoverDecision
        data class StandDown(val ownerId: String, val ownerHost: String) : TakeoverDecision
    }

    /**
     * Pure takeover gate (v13 plan §3): [SyncNow] pings the last owner and every other
     * advertised owner before calling [takeOver], and hands the results here rather than
     * trusting the failure streak or an mDNS record alone (H1-H3 in the v13 plan). Takes over
     * only when nobody answered; otherwise stands down to the lowest-id live owner found (the
     * last owner and any advertised owner are both candidates).
     */
    fun decideTakeover(
        lastOwnerHost: String?,
        lastOwnerAliveId: String?,
        advertisedLive: Map<String, String>,
    ): TakeoverDecision {
        val live = advertisedLive.toMutableMap()
        if (lastOwnerHost != null && lastOwnerAliveId != null) live[lastOwnerHost] = lastOwnerAliveId
        if (live.isEmpty()) return TakeoverDecision.TakeOver
        val (host, id) = live.entries.minBy { it.value }.toPair()
        return TakeoverDecision.StandDown(ownerId = id, ownerHost = host)
    }

    /**
     * v13 follow-up §3 (reducing simultaneous takeovers seen in on-device S4): once a failover
     * retry's own ping came back empty (no live owner anywhere), whether to take over anyway.
     * [shouldTakeOverNow] is [shouldTakeOver]'s own node-ordering verdict; the lowest candidate
     * always takes over there, at the ordinary threshold, and never needs this override. The
     * override exists only for a node that already knows it is [isLowestCandidate] == false: it
     * waits for two of its own separate failover retries in a row to see no live owner
     * ([noLiveOwnerAttempts] >= 2) before taking over anyway, so the fleet doesn't get stuck
     * forever if the true lowest candidate never reappears, but a merely-inflated overall
     * failure streak (from unrelated earlier failures) can't trigger it instantly. Pure so the
     * override is table-tested on its own.
     */
    fun shouldTakeOverAfterFailedRetry(shouldTakeOverNow: Boolean, isLowestCandidate: Boolean, noLiveOwnerAttempts: Int): Boolean =
        shouldTakeOverNow || (!isLowestCandidate && noLiveOwnerAttempts >= 2)

    /**
     * Requests OWNER mode in-process via [FlSyncService.requestOwner] first (v13 plan §7): if
     * the mesh service is already running this never touches `startForegroundService`, so a
     * background caller (a failover retry inside [AutoJoin]'s loop) never risks
     * `ForegroundServiceStartNotAllowedException` (H6). If the service isn't running yet and
     * starting it is refused, this returns false without persisting OWNER or touching the WiFi
     * Direct group. Never throws.
     */
    suspend fun takeOver(context: Context): Boolean {
        val flRuntime = context.services().flRuntime.value
        if (flRuntime == null) {
            Logx.w("Failover: flRuntime not ready yet, cannot take over")
            return false
        }
        if (!FlSyncService.requestOwner(context)) return false

        return try {
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

            flRuntime.updateConfig { it.copy(lastRole = NodeRole.OWNER, consecutiveSyncFailures = 0) }
            flRuntime.addEvent(EventType.FAILOVER, "owner unreachable, ${flRuntime.config.value.name} took over")
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logx.w("Failover: takeOver failed", e)
            false
        }
    }
}
