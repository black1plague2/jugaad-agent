package com.jugaad.agent.p2p

import com.jugaad.agent.fl.NodeRole
import com.jugaad.agent.p2p.AutoJoin.Step
import com.jugaad.agent.p2p.LanDiscovery.LanPeer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoJoinTest {
    private val owner = LanPeer("IQOO-1", "a2b6", "192.168.66.250", 8988)
    private val other = LanPeer("IQOO-2", "219e", "192.168.66.225", 8988)

    private fun plan(
        peers: List<LanPeer>,
        lastHost: String? = null,
        lastSyncAt: Long = 0L,
        now: Long = 100_000L,
        serving: Boolean = false,
        enabled: Boolean = true,
        lastOwnerAddress: String? = null,
        lastRole: NodeRole = NodeRole.NONE,
        consecutiveSyncFailures: Int = 0,
        failoverAfterFailures: Int = 3,
        liveOwnerIds: Set<String> = emptySet(),
        lastAttemptFailed: Boolean = false,
    ) = AutoJoin.plan(
        enabled, serving, peers, lastOwnerAddress, lastHost, lastSyncAt, now, intervalMs = 60_000L,
        lastRole = lastRole, consecutiveSyncFailures = consecutiveSyncFailures,
        failoverAfterFailures = failoverAfterFailures, liveOwnerIds = liveOwnerIds, lastAttemptFailed = lastAttemptFailed,
    )

    @Test
    fun newOwnerSyncsImmediately() {
        assertEquals(Step.Sync(owner), plan(listOf(owner)))
        assertEquals(Step.Sync(owner), plan(listOf(owner), lastHost = "10.0.0.1", lastSyncAt = 99_000L))
    }

    @Test
    fun sameOwnerWaitsUntilIntervalElapses() {
        val step = plan(listOf(owner), lastHost = owner.host, lastSyncAt = 70_000L)
        assertTrue(step is Step.Wait && step.ms <= 5000L && "IQOO-1" in step.status)
        assertEquals(Step.Sync(owner), plan(listOf(owner), lastHost = owner.host, lastSyncAt = 40_000L))
    }

    @Test
    fun neverSyncsWhileServingOffOrAmbiguous() {
        assertTrue(plan(listOf(owner), serving = true) is Step.Wait)
        assertTrue(plan(listOf(owner), enabled = false) is Step.Wait)
        assertTrue(plan(emptyList()) is Step.Wait)
        assertTrue(plan(listOf(owner, other)) is Step.Wait)
        assertEquals(Step.Sync(other), plan(listOf(owner, other), lastOwnerAddress = other.host))
    }

    @Test
    fun noPeersButKnownClientOwnerStillTried() {
        // mDNS lost the advertisement, but we're a CLIENT with a still-trusted last owner:
        // AutoJoin keeps syncing to it directly instead of going silent.
        assertEquals(
            Step.SyncLastOwner(other.host),
            plan(emptyList(), lastOwnerAddress = other.host, lastRole = NodeRole.CLIENT),
        )
    }

    @Test
    fun noPeersNoLastOwnerAddressDoesNothing() {
        assertTrue(plan(emptyList(), lastOwnerAddress = null, lastRole = NodeRole.CLIENT) is Step.Wait)
    }

    @Test
    fun noPeersNotAClientDoesNothing() {
        assertTrue(plan(emptyList(), lastOwnerAddress = other.host, lastRole = NodeRole.NONE) is Step.Wait)
    }

    // --- v13 plan §8 (H7): once SyncLastOwner already had its shot, retry a full failover
    // attempt instead of waiting forever with no owner anywhere in the fleet ---

    @Test
    fun noPeersPastFailoverThresholdTriesFailover() {
        assertEquals(
            Step.TryFailover(other.host),
            plan(
                emptyList(), lastOwnerAddress = other.host, lastRole = NodeRole.CLIENT,
                consecutiveSyncFailures = 3, failoverAfterFailures = 3,
            ),
        )
    }

    @Test
    fun failoverRetryNotTriedMoreThanOncePerInterval() {
        val step = plan(
            emptyList(), lastOwnerAddress = other.host, lastRole = NodeRole.CLIENT,
            consecutiveSyncFailures = 3, failoverAfterFailures = 3, lastSyncAt = 70_000L,
        )
        assertTrue(step is Step.Wait)
    }

    @Test
    fun noPeersServingDoesNothing() {
        assertTrue(
            plan(emptyList(), lastOwnerAddress = other.host, lastRole = NodeRole.CLIENT, serving = true) is Step.Wait,
        )
    }

    @Test
    fun onePeerPicksThatPeerEvenWithLastOwnerElsewhere() {
        assertEquals(Step.Sync(owner), plan(listOf(owner), lastOwnerAddress = other.host, lastRole = NodeRole.CLIENT))
    }

    // --- v13 plan §4: with several owners advertised, pick the lowest deviceId that answers ping ---

    @Test
    fun pickLowestLiveIdWhenSeveralOwnersAdvertised() {
        // other.deviceId="219e" sorts below owner.deviceId="a2b6"; both answered ping.
        assertEquals(
            Step.Sync(other),
            plan(listOf(owner, other), liveOwnerIds = setOf(owner.deviceId, other.deviceId)),
        )
    }

    @Test
    fun ignoresADeadOwnerEvenWithALowerId() {
        // "219e" (other) is the lower id, but only owner ("a2b6") answered ping.
        assertEquals(Step.Sync(owner), plan(listOf(owner, other), liveOwnerIds = setOf(owner.deviceId)))
    }

    @Test
    fun fallsBackToLastOwnerWhenNobodyAnswersPing() {
        assertEquals(Step.Sync(other), plan(listOf(owner, other), lastOwnerAddress = other.host, liveOwnerIds = emptySet()))
    }

    // --- v13 follow-up §2: a failed attempt retries after FAILED_RETRY_MS (15 s), not the full
    // autoJoinIntervalMs (60 s); success goes back to the normal interval ---

    @Test
    fun failedAttemptRetriesSoonerThanNormalInterval() {
        // 20 s since the last sync: too soon for the 60 s normal interval...
        assertTrue(plan(listOf(owner), lastHost = owner.host, lastSyncAt = 80_000L) is Step.Wait)
        // ...but past the 15 s failed-retry.
        assertEquals(
            Step.Sync(owner),
            plan(listOf(owner), lastHost = owner.host, lastSyncAt = 80_000L, lastAttemptFailed = true),
        )
    }

    @Test
    fun tryFailoverAlsoRetriesAfterFailedRetryMsNotFullInterval() {
        assertTrue(
            plan(
                emptyList(), lastOwnerAddress = other.host, lastRole = NodeRole.CLIENT,
                consecutiveSyncFailures = 3, failoverAfterFailures = 3, lastSyncAt = 80_000L,
            ) is Step.Wait,
        )
        assertEquals(
            Step.TryFailover(other.host),
            plan(
                emptyList(), lastOwnerAddress = other.host, lastRole = NodeRole.CLIENT,
                consecutiveSyncFailures = 3, failoverAfterFailures = 3, lastSyncAt = 80_000L, lastAttemptFailed = true,
            ),
        )
    }

    @Test
    fun noPeersLastOwnerWaitsForIntervalOnceJoined() {
        val step = plan(
            emptyList(), lastOwnerAddress = other.host, lastHost = other.host, lastSyncAt = 70_000L,
            lastRole = NodeRole.CLIENT,
        )
        assertTrue(step is Step.Wait)
    }
}
