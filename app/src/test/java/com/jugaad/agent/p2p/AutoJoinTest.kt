package com.jugaad.agent.p2p

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
    ) = AutoJoin.plan(enabled, serving, peers, lastOwnerAddress, lastHost, lastSyncAt, now, intervalMs = 60_000L)

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
}
