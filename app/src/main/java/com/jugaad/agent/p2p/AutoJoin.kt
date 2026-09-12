package com.jugaad.agent.p2p

import android.content.Context
import com.jugaad.agent.core.Logx
import com.jugaad.agent.core.config.ConfigStore
import com.jugaad.agent.fl.FlRuntime
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
 * [Failover] like every other client sync). Manual syncs and the background scheduler share
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
        data class Wait(val status: String, val ms: Long) : Step
    }

    private var job: Job? = null
    private var lastHost: String? = null
    private var lastSyncAt = 0L

    fun start() {
        if (job != null) return
        lan.startDiscovery()
        job = scope.launch {
            while (isActive) {
                val cfg = ConfigStore.effective.value.sync
                val me = runtime.config.value
                val peers = lan.peers.value.filterNot { it.deviceId == me.deviceId }
                val step = plan(
                    enabled = cfg.autoJoin,
                    serving = SyncBus.serving.value,
                    peers = peers,
                    lastOwnerAddress = me.lastOwnerAddress,
                    lastHost = lastHost,
                    lastSyncAt = lastSyncAt,
                    now = System.currentTimeMillis(),
                    intervalMs = cfg.autoJoinIntervalMs.toLong(),
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
                        if (result?.succeeded() == true) {
                            SyncBus.autoJoin.value = "Joined ${step.peer.name}"
                        } else {
                            SyncBus.autoJoin.value = "Could not reach ${step.peer.name}, retrying later"
                            Logx.w("auto-join: sync with '${step.peer.name}' failed: ${result?.message}")
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
        ): Step {
            if (!enabled) return Step.Wait("Auto-join is off", 5000)
            if (serving) return Step.Wait("Serving; other phones on this WiFi join this one", 5000)
            if (peers.isEmpty()) return Step.Wait("Waiting for an owner on this WiFi", 3000)
            val pick = LanDiscovery.pickOwner(peers, lastOwnerAddress)
                ?: return Step.Wait("${peers.size} owners visible, pick one under Nearby", 5000)
            val remaining = lastSyncAt + intervalMs - now
            if (pick.host != lastHost || remaining <= 0) return Step.Sync(pick)
            return Step.Wait("Joined ${pick.name}, next sync in ${(remaining + 999) / 1000} s", minOf(5000L, remaining))
        }
    }
}
