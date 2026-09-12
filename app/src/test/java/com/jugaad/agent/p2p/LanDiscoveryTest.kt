package com.jugaad.agent.p2p

import com.jugaad.agent.p2p.LanDiscovery.LanPeer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LanDiscoveryTest {
    private val a = LanPeer("IQOO-1", "a2b6", "192.168.66.250", 8988)
    private val b = LanPeer("I2501-219e", "219e", "192.168.66.225", 8988)

    @Test
    fun lastOwnerWinsWhenStillVisible() {
        assertEquals(b, LanDiscovery.pickOwner(listOf(a, b), "192.168.66.225"))
    }

    @Test
    fun singleVisibleOwnerIsPicked() {
        assertEquals(a, LanDiscovery.pickOwner(listOf(a), null))
        assertEquals(a, LanDiscovery.pickOwner(listOf(a), "10.0.0.9"))
    }

    @Test
    fun ambiguousOrEmptyReturnsNull() {
        assertNull(LanDiscovery.pickOwner(listOf(a, b), null))
        assertNull(LanDiscovery.pickOwner(emptyList(), "192.168.66.250"))
    }
}
