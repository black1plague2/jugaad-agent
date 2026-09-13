package com.jugaad.agent.p2p

import android.content.Context
import com.jugaad.agent.core.Logx
import com.jugaad.agent.core.config.ConfigStore
import com.jugaad.agent.fl.FlRuntime
import com.jugaad.agent.fl.NodeRole
import com.jugaad.agent.p2p.LanDiscovery.LanPeer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Joins the owner without any tap: while this phone is not serving and an owner is advertised
 * on this WiFi network, it syncs with that owner right away and again every
 * [com.jugaad.agent.core.config.Sync.autoJoinIntervalMs]. A new or changed owner triggers an
 * immediate sync; a failed sync waits one interval before the next attempt (and counts toward
 * [Failover] like every other client sync). If mDNS momentarily loses the owner's advertisement
 * (the router drops the record even though the owner is still up) and we haven't failed over
 * yet, we keep syncing directly to the last known owner address on the normal interval rather
 * than going silent. Manual syncs and the background scheduler share
 * [SyncNow]'s lock, so at most one client session runs at a time. Status text for the Devices
 * tab goes to [SyncBus.autoJoin].
 */
class AutoJoin(
    private val runtime: FlRuntime,
    private val scope: CoroutineScope,
    private val context: Context,
    private val lan: LanDiscovery,
) {
    sealed interface Step {
        data class Sync(val peer: LanPeer) : Step
        /** No owner advertised right now, but the last owner we successfully synced with
         * hasn't failed enough to hand off to [Failover] yet; keep trying it directly. */
        data class SyncLastOwner(val host: String) : Step
        /** No owner advertised, the streak already reached the failover threshold, and
         * [SyncLastOwner] already had its shot (v13 plan §8, H7): retry a full failover attempt
         * (ping the last owner, stand down if it answers, else consider taking over) rather than
         * waiting forever with no owner anywhere in the fleet. */
        data class TryFailover(val lastOwnerHost: String) : Step
        data class Wait(val status: String, val ms: Long) : Step
    }

    private var job: Job? = null
    private var lastHost: String? = null
    private var lastSyncAt = 0L
    /** Whether the previous attempt (of any kind) failed (v13 follow-up §2): drives a shorter
     * [FAILED_RETRY_MS] retry instead of the full [com.jugaad.agent.core.config.Sync.autoJoinIntervalMs]. */
    private var lastAttemptFailed = false
    /** Consecutive [Step.TryFailover] attempts in a row that found no live owner at all (v13
     * follow-up §3): separate from [com.jugaad.agent.fl.NodeConfig.consecutiveSyncFailures],
     * which can already be inflated by unrelated earlier failures, so the streak-override in
     * [Failover.shouldTakeOverAfterFailedRetry] only fires after this node's own two retries. */
    private var noLiveOwnerAttempts = 0

    fun start() {
        if (job != null) return
        lan.startDiscovery()
        job = scope.launch {
            while (isActive) {
                val cfg = ConfigStore.effective.value.sync
                val me = runtime.config.value
                val peers = lan.peers.value.filterNot { it.deviceId == me.deviceId }
                // Several owners advertised: ping each so plan() can pick the lowest-id one that
                // actually answers rather than trusting whichever mDNS record resolved (v13 §4).
                // Pinging is pointless with 0 or 1 peer (plan()'s existing fallback already
                // covers those), so skip the network round trips then.
                val liveOwnerIds: Set<String> = if (peers.size > 1) {
                    val ids = mutableSetOf<String>()
                    for (p in peers) {
                        if (OwnerProbe.ping(p.host, p.port) != null) ids.add(p.deviceId)
                    }
                    ids
                } else {
                    emptySet()
                }
                val step = plan(
                    enabled = cfg.autoJoin,
                    serving = SyncBus.serving.value,
                    peers = peers,
                    lastRole = me.lastRole,
                    lastOwnerAddress = me.lastOwnerAddress,
                    consecutiveSyncFailures = me.consecutiveSyncFailures,
                    failoverAfterFailures = cfg.failoverAfterFailures,
                    lastHost = lastHost,
                    lastSyncAt = lastSyncAt,
                    now = System.currentTimeMillis(),
                    intervalMs = cfg.autoJoinIntervalMs.toLong(),
                    liveOwnerIds = liveOwnerIds,
                    lastAttemptFailed = lastAttemptFailed,
                )
                when (step) {
                    is Step.Sync -> {
                        SyncBus.autoJoin.value = "Joining ${step.peer.name}"
                        Logx.i("auto-join: syncing with '${step.peer.name}' at ${step.peer.host}")
                        val result = runCatching { SyncNow.asClient(context, step.peer.host) }
                            .onFailure { Logx.w("auto-join: sync threw", it) }
                            .getOrNull()
                        lastHost = step.peer.host
                        lastSyncAt = System.currentTimeMillis()
                        lastAttemptFailed = result?.succeeded() != true
                        noLiveOwnerAttempts = 0
                        if (result?.succeeded() == true) {
                            SyncBus.autoJoin.value = "Joined ${step.peer.name}"
                        } else {
                            SyncBus.autoJoin.value = "Could not reach ${step.peer.name}, retrying later"
                            Logx.w("auto-join: sync with '${step.peer.name}' failed: ${result?.message}")
                        }
                    }
                    is Step.SyncLastOwner -> {
                        SyncBus.autoJoin.value = "Joining last owner"
                        Logx.i("auto-join: owner not advertised, trying last owner ${step.host}")
                        val result = runCatching { SyncNow.asClient(context, step.host) }
                            .onFailure { Logx.w("auto-join: sync threw", it) }
                            .getOrNull()
                        lastHost = step.host
                        lastSyncAt = System.currentTimeMillis()
                        lastAttemptFailed = result?.succeeded() != true
                        noLiveOwnerAttempts = 0
                        if (result?.succeeded() == true) {
                            SyncBus.autoJoin.value = "Joined ${step.host}"
                        } else {
                            SyncBus.autoJoin.value = "Could not reach last owner, retrying later"
                            Logx.w("auto-join: sync with last owner '${step.host}' failed: ${result?.message}")
                        }
                    }
                    is Step.TryFailover -> {
                        Logx.i("failover: streak ${me.consecutiveSyncFailures} past threshold, retrying")
                        val liveId = OwnerProbe.ping(step.lastOwnerHost, SyncProtocol.port())
                        lastHost = step.lastOwnerHost
                        lastSyncAt = System.currentTimeMillis()
                        if (liveId != null) {
                            noLiveOwnerAttempts = 0
                            Logx.i("failover: owner $liveId/${step.lastOwnerHost} is alive, not taking over")
                            runtime.updateConfig { c ->
                                c.copy(lastRole = NodeRole.CLIENT, lastOwnerAddress = step.lastOwnerHost, consecutiveSyncFailures = 0)
                            }
                            SyncBus.autoJoin.value = "Joining last owner"
                            val result = runCatching { SyncNow.asClient(context, step.lastOwnerHost) }
                                .onFailure { Logx.w("auto-join: sync threw", it) }
                                .getOrNull()
                            lastAttemptFailed = result?.succeeded() != true
                            SyncBus.autoJoin.value = if (result?.succeeded() == true) {
                                "Joined ${step.lastOwnerHost}"
                            } else {
                                "Could not reach last owner, retrying later"
                            }
                        } else {
                            noLiveOwnerAttempts += 1
                            val bumped = me.consecutiveSyncFailures + 1
                            runtime.updateConfig { it.copy(consecutiveSyncFailures = bumped) }
                            val fullCfg = ConfigStore.effective.value
                            val takeoverNow = Failover.shouldTakeOver(runtime.config.value, runtime.network.value, System.currentTimeMillis(), fullCfg)
                            val isLowest = Failover.isLowestCandidate(runtime.config.value, runtime.network.value, System.currentTimeMillis(), fullCfg.sync.staleMinutes)
                            if (Failover.shouldTakeOverAfterFailedRetry(takeoverNow, isLowest, noLiveOwnerAttempts)) {
                                Logx.i("failover: no owner appeared, taking over")
                                val took = Failover.takeOver(context)
                                lastAttemptFailed = !took
                                if (took) noLiveOwnerAttempts = 0
                                SyncBus.autoJoin.value = if (took) "Taking over as owner" else "Takeover failed, retrying later"
                            } else {
                                lastAttemptFailed = true
                                SyncBus.autoJoin.value = "No owner reachable, retrying failover soon"
                            }
                        }
                    }
                    is Step.Wait -> {
                        SyncBus.autoJoin.value = step.status
                        delay(step.ms)
                    }
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    companion object {
        /** Shorter retry after any failed attempt (v13 follow-up §2), instead of the full
         * [com.jugaad.agent.core.config.Sync.autoJoinIntervalMs]: a dead/frozen owner should not
         * cost a full interval on top of the fail-fast ping in [SyncNow.asClient]. */
        const val FAILED_RETRY_MS = 15_000L

        /** Pure decision for one loop turn; [peers] must already exclude this device. */
        fun plan(
            enabled: Boolean,
            serving: Boolean,
            peers: List<LanPeer>,
            lastOwnerAddress: String?,
            lastHost: String?,
            lastSyncAt: Long,
            now: Long,
            intervalMs: Long,
            lastRole: NodeRole = NodeRole.NONE,
            consecutiveSyncFailures: Int = 0,
            failoverAfterFailures: Int = Int.MAX_VALUE,
            /** deviceIds of advertised owners the caller already confirmed alive via
             * [OwnerProbe] (v13 plan §4); empty means nobody was pinged (or nobody answered). */
            liveOwnerIds: Set<String> = emptySet(),
            /** Whether the previous attempt (of any kind) failed (v13 follow-up §2): retries
             * after [FAILED_RETRY_MS] instead of [intervalMs] until one succeeds. */
            lastAttemptFailed: Boolean = false,
        ): Step {
            if (!enabled) return Step.Wait("Auto-join is off", 5000)
            if (serving) return Step.Wait("Serving; other phones on this WiFi join this one", 5000)
            val retryMs = if (lastAttemptFailed) FAILED_RETRY_MS else intervalMs
            if (peers.isEmpty()) {
                // Nobody is advertising right now (mDNS/NSD lost events are flaky on some
                // routers), but we have a known-good owner we haven't given up on: keep syncing
                // to it directly on the normal interval instead of going silent. A truly dead
                // owner still racks up consecutiveSyncFailures via SyncNow and hands off to
                // Failover exactly as before.
                if (lastRole == NodeRole.CLIENT && lastOwnerAddress != null) {
                    val remaining = lastSyncAt + retryMs - now
                    if (consecutiveSyncFailures < failoverAfterFailures) {
                        return if (lastOwnerAddress != lastHost || remaining <= 0) {
                            Step.SyncLastOwner(lastOwnerAddress)
                        } else {
                            Step.Wait("Waiting for an owner on this WiFi", minOf(3000L, remaining))
                        }
                    }
                    // Past the threshold: SyncLastOwner already had its shot without success.
                    // Retry a full failover attempt at most once per interval (v13 plan §8)
                    // instead of waiting forever with no owner in the fleet (H7).
                    return if (remaining <= 0) {
                        Step.TryFailover(lastOwnerAddress)
                    } else {
                        Step.Wait("No owner reachable, retrying failover soon", minOf(3000L, remaining))
                    }
                }
                return Step.Wait("Waiting for an owner on this WiFi", 3000)
            }
            val pick = LanDiscovery.pickOwner(peers, lastOwnerAddress, liveOwnerIds)
                ?: return Step.Wait("${peers.size} owners visible, pick one under Nearby", 5000)
            val remaining = lastSyncAt + retryMs - now
            if (pick.host != lastHost || remaining <= 0) return Step.Sync(pick)
            return Step.Wait("Joined ${pick.name}, next sync in ${(remaining + 999) / 1000} s", minOf(5000L, remaining))
        }
    }
}
