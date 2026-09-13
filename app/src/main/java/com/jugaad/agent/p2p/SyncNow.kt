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

        // Ping before the first attempt and before each retry (v13 follow-up §1): a session
        // against a dead/frozen owner otherwise burns connectTimeout+readTimeout on every one of
        // retryBackoffMs's attempts (30 s+ apiece on-device), which is most of what made S4's
        // recovery take 9 minutes. A ping that fails stops the whole call right here with one
        // recorded failure rather than working through the remaining retries.
        var result = pingThenRun(ownerAddress, coordinator)
        if (!result.isPingFailure()) {
            for (backoffMs in cfg.sync.retryBackoffMs) {
                if (result.succeeded()) break
                delay(backoffMs.toLong())
                result = pingThenRun(ownerAddress, coordinator)
                if (result.isPingFailure()) break
            }
        }

        flRuntime.updateConfig { c -> nextConfig(c, ownerAddress, result) }
        SyncBus.last.value = result

        if (!result.succeeded() &&
            Failover.shouldTakeOver(flRuntime.config.value, flRuntime.network.value, System.currentTimeMillis(), cfg)
        ) {
            // The failure streak and node ordering say take over, but neither proves the current
            // owner is actually dead (H1-H3 in the v13 plan): ping it and every other advertised
            // owner first, and only take over if nobody answers.
            when (val decision = probeBeforeTakeover(context, flRuntime)) {
                Failover.TakeoverDecision.TakeOver -> Failover.takeOver(context)
                is Failover.TakeoverDecision.StandDown -> {
                    Logx.i("failover: owner ${decision.ownerId}/${decision.ownerHost} is alive, not taking over")
                    flRuntime.updateConfig { c ->
                        c.copy(lastRole = NodeRole.CLIENT, lastOwnerAddress = decision.ownerHost, consecutiveSyncFailures = 0)
                    }
                }
            }
        }

        result
    }

    /** Pings [NodeConfig.lastOwnerAddress] and every other advertised owner (v13 plan §3),
     * then hands the results to the pure [Failover.decideTakeover]. */
    private suspend fun probeBeforeTakeover(context: Context, flRuntime: FlRuntime): Failover.TakeoverDecision {
        val me = flRuntime.config.value
        val lastOwnerHost = me.lastOwnerAddress
        val lastOwnerAliveId = lastOwnerHost?.let { OwnerProbe.ping(it, SyncProtocol.port()) }

        val lan = context.services().lanDiscovery
        val others = lan.peers.value.filterNot { it.deviceId == me.deviceId }
        val advertisedLive = mutableMapOf<String, String>()
        for (p in others) {
            OwnerProbe.ping(p.host, p.port)?.let { advertisedLive[p.host] = it }
        }

        return Failover.decideTakeover(lastOwnerHost, lastOwnerAliveId, advertisedLive)
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
        // Several owners advertised: ping each so the lowest-id one that actually answers wins,
        // rather than trusting whichever mDNS record happened to resolve (v13 plan §4).
        val liveOwnerIds: Set<String> = if (peers.size > 1) {
            val ids = mutableSetOf<String>()
            for (p in peers) {
                if (OwnerProbe.ping(p.host, p.port) != null) ids.add(p.deviceId)
            }
            ids
        } else {
            emptySet()
        }
        val pick = LanDiscovery.pickOwner(peers, flRuntime.config.value.lastOwnerAddress, liveOwnerIds)
        Logx.i("fl sync: lan scan found ${peers.size} owner(s) ${peers.map { it.name }}, picked ${pick?.name}")
        return pick?.host
    }

    private const val LAN_SCAN_MS = 3000L

    /** Pings [ownerAddress] first (v13 follow-up §1); only runs the real session if it answers.
     * Logs and returns [pingFailedResult] otherwise, so the caller can tell a fail-fast result
     * apart from an ordinary session failure via [isPingFailure]. */
    private suspend fun pingThenRun(ownerAddress: String, coordinator: FedAvgCoordinator): SyncResult {
        if (OwnerProbe.ping(ownerAddress, SyncProtocol.port()) == null) {
            Logx.w("fl sync: owner $ownerAddress did not answer ping, failing fast")
            return pingFailedResult(ownerAddress)
        }
        return coordinator.runAsClient(ownerAddress)
    }

    /** Pure failure-count/reset transition, pulled out of [asClient] so it's unit-testable
     * without WiFi Direct or a real [com.jugaad.agent.fl.FlRuntime]. */
    internal fun nextConfig(current: NodeConfig, ownerAddress: String, result: SyncResult): NodeConfig =
        if (result.succeeded()) {
            current.copy(lastRole = NodeRole.CLIENT, lastOwnerAddress = ownerAddress, consecutiveSyncFailures = 0)
        } else {
            current.copy(consecutiveSyncFailures = current.consecutiveSyncFailures + 1)
        }

    /** Pure failure-shaped result for the ping-fail-fast path (v13 follow-up §1), pulled out so
     * it's unit-testable on its own: shaped exactly like [FedAvgCoordinator.runAsClient]'s own
     * swallowed-exception failure so [succeeded] and [nextConfig] treat it identically to a real
     * session failure. */
    internal fun pingFailedResult(host: String): SyncResult = SyncResult(
        role = SyncRole.CLIENT, peers = 1, variants = emptyList(), roundsBefore = emptyMap(), roundsAfter = emptyMap(),
        accepted = emptyMap(), valBefore = emptyMap(), valAfter = emptyMap(), promoted = null,
        message = "fl sync: client failed: owner $host did not answer ping",
    )
}

/** A session that never threw; [FedAvgCoordinator.runAsClient] swallows failures into this message shape. */
internal fun SyncResult.succeeded(): Boolean = !message.startsWith("fl sync: client failed")

/** True for [SyncNow.pingFailedResult]'s shape specifically, so a caller retrying can tell "the
 * owner didn't even answer a ping" apart from an ordinary failed session. */
internal fun SyncResult.isPingFailure(): Boolean = message.contains("did not answer ping")
