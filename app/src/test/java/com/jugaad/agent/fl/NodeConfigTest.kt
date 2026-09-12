package com.jugaad.agent.fl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Self-healing role persistence (v4 plan §4): old `node.json` still loads once [NodeRole] etc. are added. */
class NodeConfigTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun oldNodeJsonWithoutV4FieldsLoadsWithDefaults() {
        val dir = tmp.newFolder()
        File(dir, "node.json").writeText(
            """{"deviceId":"abc123","name":"Phone-abc1","mode":"EXPERIMENTAL","pinnedChallenger":null,"autoTrain":true,"autoSync":false}""",
        )

        val config = NodeConfigIO.load(dir)

        assertEquals("abc123", config.deviceId)
        assertEquals(NodeRole.NONE, config.lastRole)
        assertNull(config.lastOwnerAddress)
        assertEquals(0, config.consecutiveSyncFailures)
        assertTrue(config.shareSamples)
    }

    @Test
    fun roundTripsTheNewFieldsThroughSaveAndLoad() {
        val dir = tmp.newFolder()
        val fresh = NodeConfigIO.load(dir).copy(
            lastRole = NodeRole.OWNER,
            lastOwnerAddress = "192.168.49.1",
            consecutiveSyncFailures = 2,
            shareSamples = false,
        )
        NodeConfigIO.save(dir, fresh)

        val reloaded = NodeConfigIO.load(dir)

        assertEquals(NodeRole.OWNER, reloaded.lastRole)
        assertEquals("192.168.49.1", reloaded.lastOwnerAddress)
        assertEquals(2, reloaded.consecutiveSyncFailures)
        assertFalse(reloaded.shareSamples)
    }
}
