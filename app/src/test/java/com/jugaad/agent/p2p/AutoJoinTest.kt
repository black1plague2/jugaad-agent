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
    ) = AutoJoin.plan(
        enabled, serving, peers, lastOwnerAddress, lastHost, lastSyncAt, now, intervalMs = 60_000L,
        lastRole = lastRole, consecutiveSyncFailures = consecutiveSyncFailures,
        failoverAfterFailures = failoverAfterFailures,
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

    @Test
    fun noPeersPastFailoverThresholdDoesNothing() {
        // Once SyncNow's failure streak reached the failover threshold, Failover already had
        // its chance; AutoJoin should not keep hammering a owner it has given up on.
        assertTrue(
            plan(
                emptyList(), lastOwnerAddress = other.host, lastRole = NodeRole.CLIENT,
                consecutiveSyncFailures = 3, failoverAfterFailures = 3,
            ) is Step.Wait,
        )
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

    @Test
    fun noPeersLastOwnerWaitsForIntervalOnceJoined() {
        val step = plan(
            emptyList(), lastOwnerAddress = other.host, lastHost = other.host, lastSyncAt = 70_000L,
            lastRole = NodeRole.CLIENT,
        )
        assertTrue(step is Step.Wait)
    }
}
