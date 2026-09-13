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

    // --- SyncNow.pingFailedResult / isPingFailure (v13 follow-up §1: fail-fast on a dead ping,
    // shaped so nextConfig treats it exactly like an ordinary failed session) ---

    @Test
    fun pingFailedResultCountsAsAFailedSyncAndBumpsTheStreak() {
        val failed = SyncNow.pingFailedResult("192.168.49.1")

        assertFalse(failed.succeeded())
        assertTrue(failed.isPingFailure())
        val updated = SyncNow.nextConfig(nodeConfig("aaa00001", failures = 2), "192.168.49.1", failed)
        assertEquals(3, updated.consecutiveSyncFailures)
    }

    @Test
    fun ordinarySessionFailureIsNotAPingFailure() {
        assertFalse(syncResult("fl sync: client failed: ConnectException: ECONNREFUSED").isPingFailure())
    }

    // --- Failover.shouldStepDown (two simultaneous owners resolve to the lower deviceId) ---

    @Test
    fun stepsDownToLowerIdPeer() {
        assertTrue(Failover.shouldStepDown("fd22b465", listOf("a7830d3e")))
    }

    @Test
    fun doesNotStepDownForHigherIdPeerOnly() {
        assertFalse(Failover.shouldStepDown("a7830d3e", listOf("fd22b465")))
    }

    @Test
    fun doesNotStepDownWithNoOtherOwners() {
        assertFalse(Failover.shouldStepDown("a7830d3e", emptyList()))
    }

    // --- Failover.decideTakeover (v13 plan §3: never take over if anybody answers a ping) ---

    @Test
    fun nobodyAliveTakesOver() {
        val decision = Failover.decideTakeover(lastOwnerHost = null, lastOwnerAliveId = null, advertisedLive = emptyMap())
        assertEquals(Failover.TakeoverDecision.TakeOver, decision)
    }

    @Test
    fun lastOwnerAliveStandsDownToIt() {
        val decision = Failover.decideTakeover(
            lastOwnerHost = "192.168.49.1", lastOwnerAliveId = "aaa00001", advertisedLive = emptyMap(),
        )
        assertEquals(Failover.TakeoverDecision.StandDown("aaa00001", "192.168.49.1"), decision)
    }

    @Test
    fun anotherAdvertisedOwnerAliveStandsDownToLowestId() {
        val decision = Failover.decideTakeover(
            lastOwnerHost = "192.168.49.1",
            lastOwnerAliveId = null, // last owner didn't answer
            advertisedLive = mapOf("192.168.49.5" to "ccc00003", "192.168.49.9" to "aaa00001"),
        )
        assertEquals(Failover.TakeoverDecision.StandDown("aaa00001", "192.168.49.9"), decision)
    }

    @Test
    fun lastOwnerAndAdvertisedOwnerBothAliveStandsDownToLowestIdAcrossBoth() {
        val decision = Failover.decideTakeover(
            lastOwnerHost = "192.168.49.1",
            lastOwnerAliveId = "zzz00009",
            advertisedLive = mapOf("192.168.49.9" to "aaa00001"),
        )
        assertEquals(Failover.TakeoverDecision.StandDown("aaa00001", "192.168.49.9"), decision)
    }

    // --- Failover.isLowestCandidate (the ordering half of shouldTakeOver, factored out) ---

    @Test
    fun isLowestCandidateTrueWhenNobodyLowerIsFresh() {
        val network = NetworkState.empty().copy(nodes = listOf(card("owner01", isOwner = true, lastSeenMs = 0L)))
        assertTrue(Failover.isLowestCandidate(nodeConfig("aaa00001", failures = 0), network, nowMs = 1000L, staleMinutes = 30))
    }

    @Test
    fun isLowestCandidateFalseWhenALowerFreshNodeExists() {
        val network = NetworkState.empty().copy(
            nodes = listOf(card("owner01", isOwner = true, lastSeenMs = 0L), card("aaa00001", isOwner = false, lastSeenMs = 1000L)),
        )
        assertFalse(Failover.isLowestCandidate(nodeConfig("bbb00002", failures = 0), network, nowMs = 1000L, staleMinutes = 30))
    }

    // --- Failover.shouldTakeOverAfterFailedRetry (v13 follow-up §3, S4: at the threshold only
    // the lowest candidate takes over; a higher id waits two more failed retries with no live
    // owner before the override lets it take over anyway) ---

    @Test
    fun atThresholdOnlyTheLowestCandidateTakesOver() {
        // Lowest candidate: shouldTakeOverNow is already true at the ordinary threshold.
        assertTrue(Failover.shouldTakeOverAfterFailedRetry(shouldTakeOverNow = true, isLowestCandidate = true, noLiveOwnerAttempts = 0))
        // A higher id at the very same threshold must wait, not override immediately.
        assertFalse(Failover.shouldTakeOverAfterFailedRetry(shouldTakeOverNow = false, isLowestCandidate = false, noLiveOwnerAttempts = 0))
        assertFalse(Failover.shouldTakeOverAfterFailedRetry(shouldTakeOverNow = false, isLowestCandidate = false, noLiveOwnerAttempts = 1))
    }

    @Test
    fun higherIdOverridesOnlyAfterTwoFailedRetriesWithNoLiveOwner() {
        assertTrue(Failover.shouldTakeOverAfterFailedRetry(shouldTakeOverNow = false, isLowestCandidate = false, noLiveOwnerAttempts = 2))
    }

    @Test
    fun lowestCandidateNeverNeedsTheOverride() {
        // Even if shouldTakeOverNow were false for some other reason (autoFailover off, streak
        // not yet at threshold), being the lowest candidate itself blocks the override so a
        // second, redundant path to takeover never opens up for the node already electing itself.
        assertFalse(Failover.shouldTakeOverAfterFailedRetry(shouldTakeOverNow = false, isLowestCandidate = true, noLiveOwnerAttempts = 5))
    }
}
