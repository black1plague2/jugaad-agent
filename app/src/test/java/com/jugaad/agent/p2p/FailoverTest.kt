package com.jugaad.agent.p2p

import com.jugaad.agent.core.config.AppConfig
import com.jugaad.agent.core.config.Sync
import com.jugaad.agent.fl.NetworkState
import com.jugaad.agent.fl.NodeCard
import com.jugaad.agent.fl.NodeConfig
import com.jugaad.agent.fl.NodeMode
import com.jugaad.agent.fl.NodeRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Truth table for [Failover.shouldTakeOver], a pure function over [NodeConfig]/[NetworkState]/[AppConfig]. */
class FailoverTest {

    private fun cfg(failoverAfterFailures: Int = 3, autoFailover: Boolean = true, staleMinutes: Int = 30) =
        AppConfig(sync = Sync(failoverAfterFailures = failoverAfterFailures, autoFailover = autoFailover, staleMinutes = staleMinutes))

    private fun card(deviceId: String, isOwner: Boolean, lastSeenMs: Long) = NodeCard(
        deviceId = deviceId, name = deviceId, mode = NodeMode.STABLE, challenger = null, isOwner = isOwner,
        champRound = 1, champValAcc = 0.9f, challValAcc = -1f, nTrain = 10, nVal = 5, lastSeenMs = lastSeenMs,
    )

    private fun nodeConfig(deviceId: String, failures: Int) = NodeConfig(
        deviceId = deviceId, name = deviceId, mode = NodeMode.STABLE, pinnedChallenger = null,
        autoTrain = false, autoSync = false, consecutiveSyncFailures = failures,
    )

    private fun syncResult(message: String) = SyncResult(
        role = SyncRole.CLIENT, peers = 1, variants = emptyList(), roundsBefore = emptyMap(), roundsAfter = emptyMap(),
        accepted = emptyMap(), valBefore = emptyMap(), valAfter = emptyMap(), promoted = null, message = message,
    )

    @Test
    fun belowFailureThresholdNeverTakesOver() {
        val network = NetworkState.empty().copy(nodes = listOf(card("owner01", isOwner = true, lastSeenMs = 0L)))
        val config = nodeConfig("aaa00001", failures = 2)

        assertFalse(Failover.shouldTakeOver(config, network, nowMs = 1000L, cfg = cfg(failoverAfterFailures = 3)))
    }

    @Test
    fun notLowestDeviceIdAmongFreshNodesDoesNotTakeOver() {
        val network = NetworkState.empty().copy(
            nodes = listOf(
                card("owner01", isOwner = true, lastSeenMs = 0L),
                card("aaa00001", isOwner = false, lastSeenMs = 1000L),
            ),
        )
        val config = nodeConfig("bbb00002", failures = 3)

        assertFalse(Failover.shouldTakeOver(config, network, nowMs = 1000L, cfg = cfg()))
    }

    @Test
    fun autoFailoverDisabledNeverTakesOver() {
        val network = NetworkState.empty().copy(nodes = listOf(card("owner01", isOwner = true, lastSeenMs = 0L)))
        val config = nodeConfig("aaa00001", failures = 5)

        assertFalse(Failover.shouldTakeOver(config, network, nowMs = 1000L, cfg = cfg(autoFailover = false)))
    }

    @Test
    fun lowestDeviceIdAmongFreshNodesTakesOver() {
        val network = NetworkState.empty().copy(
            nodes = listOf(
                card("owner01", isOwner = true, lastSeenMs = 0L),
                card("ccc00003", isOwner = false, lastSeenMs = 1000L),
            ),
        )
        val config = nodeConfig("aaa00001", failures = 3)

        assertTrue(Failover.shouldTakeOver(config, network, nowMs = 1000L, cfg = cfg()))
    }

    @Test
    fun staleNodeOutsideWindowIsExcludedFromCandidates() {
        // "bbb00002" is lower than self but stale (last seen far outside staleMinutes), so
        // it must not block this node's takeover.
        val network = NetworkState.empty().copy(
            nodes = listOf(
                card("owner01", isOwner = true, lastSeenMs = 0L),
                card("bbb00002", isOwner = false, lastSeenMs = 0L),
            ),
        )
        val config = nodeConfig("ccc00003", failures = 3)
        val nowMs = 40 * 60_000L // 40 minutes after bbb00002 was last seen

        assertTrue(Failover.shouldTakeOver(config, network, nowMs = nowMs, cfg = cfg(staleMinutes = 30)))
    }

    // --- SyncNow.nextConfig (the failure-count/reset transition every client sync path shares) ---

    @Test
    fun nextConfigIncrementsFailureStreakOnFailedSync() {
        val config = nodeConfig("aaa00001", failures = 2)
        val failed = syncResult("fl sync: client failed: ConnectException: ECONNREFUSED")

        val updated = SyncNow.nextConfig(config, "192.168.49.1", failed)

        assertEquals(3, updated.consecutiveSyncFailures)
        assertEquals(NodeRole.NONE, updated.lastRole)
    }

    @Test
    fun nextConfigResetsFailureStreakAndRecordsOwnerOnSuccessfulSync() {
        val config = nodeConfig("aaa00001", failures = 3)
        val ok = syncResult("fl sync: client variants=[], rounds {} -> {}, promoted=null")

        val updated = SyncNow.nextConfig(config, "192.168.49.1", ok)

        assertEquals(0, updated.consecutiveSyncFailures)
        assertEquals(NodeRole.CLIENT, updated.lastRole)
        assertEquals("192.168.49.1", updated.lastOwnerAddress)
    }
}
