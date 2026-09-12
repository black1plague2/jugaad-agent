package com.jugaad.agent.p2p

import com.jugaad.agent.fl.EventType
import com.jugaad.agent.fl.NetworkEvent
import com.jugaad.agent.fl.NetworkState
import com.jugaad.agent.fl.NodeCard
import com.jugaad.agent.fl.NodeMode
import com.jugaad.agent.fl.VariantStanding
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

class SyncProtocolTest {

    private fun roundTrip(message: SyncProtocol.Message): SyncProtocol.Message {
        val out = ByteArrayOutputStream()
        SyncProtocol.write(out, message)
        return SyncProtocol.read(ByteArrayInputStream(out.toByteArray()))
    }

    @Test
    fun helloMessageRoundTrips() {
        val header = SyncProtocol.Header(
            deviceId = "abc12345",
            name = "Phone A",
            mode = NodeMode.EXPERIMENTAL,
            challenger = "deep",
        )
        val readBack = roundTrip(SyncProtocol.Message(SyncProtocol.HELLO, header, null))

        assertEquals(SyncProtocol.HELLO, readBack.type)
        assertEquals(header, readBack.header)
        assertNull(readBack.weights)
    }

    @Test
    fun weightsMessageRoundTrips() {
        val weights = FloatArray(16707) { i -> (i % 97) * 0.001f - 0.05f }
        val header = SyncProtocol.Header(
            deviceId = "abc12345",
            variantId = "base",
            round = 3,
            nTrain = 20,
            nVal = 6,
            trainAcc = 0.91f,
            valAcc = 0.87f,
        )
        val readBack = roundTrip(SyncProtocol.Message(SyncProtocol.WEIGHTS, header, weights))

        assertEquals(SyncProtocol.WEIGHTS, readBack.type)
        assertEquals(header, readBack.header)
        assertArrayEquals(weights, readBack.weights, 1e-6f)
    }

    @Test
    fun weightsHeaderWithTrainMsAndUsesUnlabelledRoundTrips() {
        val header = SyncProtocol.Header(
            deviceId = "abc12345",
            variantId = "distill",
            round = 3,
            nTrain = 20,
            nVal = 6,
            trainAcc = 0.91f,
            valAcc = 0.87f,
            trainMs = 842L,
            usesUnlabelled = true,
        )
        val readBack = roundTrip(SyncProtocol.Message(SyncProtocol.WEIGHTS, header, floatArrayOf(1f, 2f)))

        assertEquals(header, readBack.header)
        assertEquals(842L, readBack.header.trainMs)
        assertEquals(true, readBack.header.usesUnlabelled)
    }

    @Test
    fun weightsHeaderWithoutTrainMsOrUsesUnlabelledRoundTripsAsNull() {
        // Mirrors a v2 sender that never set these v3-only fields.
        val header = SyncProtocol.Header(deviceId = "abc12345", variantId = "base", round = 1)
        val readBack = roundTrip(SyncProtocol.Message(SyncProtocol.WEIGHTS, header, floatArrayOf(1f)))

        assertEquals(header, readBack.header)
        assertNull(readBack.header.trainMs)
        assertNull(readBack.header.usesUnlabelled)
    }

    @Test
    fun weightsHeaderWithFeatureSchemaVersionRoundTrips() {
        val header = SyncProtocol.Header(deviceId = "abc12345", variantId = "base", round = 1, featureSchemaVersion = 3)
        val readBack = roundTrip(SyncProtocol.Message(SyncProtocol.WEIGHTS, header, floatArrayOf(1f)))

        assertEquals(header, readBack.header)
        assertEquals(3, readBack.header.featureSchemaVersion)
    }

    @Test
    fun sampleIdsMessageRoundTrips() {
        val header = SyncProtocol.Header(deviceId = "abc12345", ids = listOf("s1", "s2", "s3"))
        val readBack = roundTrip(SyncProtocol.Message(SyncProtocol.SAMPLE_IDS, header, null))

        assertEquals(SyncProtocol.SAMPLE_IDS, readBack.type)
        assertEquals(header, readBack.header)
        assertNull(readBack.weights)
    }

    @Test
    fun samplesMessageRoundTrips() {
        val wire = SyncProtocol.FlSampleWire(
            id = "s1", origin = "owner01", assetId = "asset1", machineTypeId = "coffee_vending",
            x = FloatArray(260) { it * 0.01f }, label = 1, score = 0.42, ts = 1_000L,
        )
        val header = SyncProtocol.Header(deviceId = "owner01", featureSchemaVersion = 3, samples = listOf(wire))
        val readBack = roundTrip(SyncProtocol.Message(SyncProtocol.SAMPLES, header, null))

        // Header carries a FlSampleWire with a FloatArray, so (as with Message.weights
        // elsewhere in this file) compare fields individually rather than via data-class
        // equals, which would compare `x` by array reference.
        assertEquals(SyncProtocol.SAMPLES, readBack.type)
        assertEquals(3, readBack.header.featureSchemaVersion)
        val readWire = readBack.header.samples?.single()
        assertNotNull(readWire)
        assertEquals(wire.id, readWire?.id)
        assertEquals(wire.origin, readWire?.origin)
        assertEquals(wire.assetId, readWire?.assetId)
        assertEquals(wire.machineTypeId, readWire?.machineTypeId)
        assertArrayEquals(wire.x, readWire?.x, 1e-6f)
        assertEquals(wire.label, readWire?.label)
        assertEquals(wire.score, readWire?.score)
        assertEquals(wire.ts, readWire?.ts)
        assertNull(readBack.weights)
    }

    @Test
    fun mergedMessageRoundTrips() {
        val weights = floatArrayOf(1f, 2f, 3f)
        val header = SyncProtocol.Header(deviceId = "owner01", variantId = "small", round = 5)
        val readBack = roundTrip(SyncProtocol.Message(SyncProtocol.MERGED, header, weights))

        assertEquals(SyncProtocol.MERGED, readBack.type)
        assertEquals(header, readBack.header)
        assertArrayEquals(weights, readBack.weights, 1e-6f)
    }

    @Test
    fun networkMessageWithNonEmptyStateRoundTrips() {
        val state = NetworkState(
            championId = "base",
            nodes = listOf(
                NodeCard(
                    deviceId = "owner01", name = "Phone A", mode = NodeMode.STABLE, challenger = null,
                    isOwner = true, champRound = 4, champValAcc = 0.9f, challValAcc = -1f,
                    nTrain = 20, nVal = 5, lastSeenMs = 1_000L,
                ),
                NodeCard(
                    deviceId = "client01", name = "Phone B", mode = NodeMode.EXPERIMENTAL, challenger = "deep",
                    isOwner = false, champRound = 4, champValAcc = 0.88f, challValAcc = 0.92f,
                    nTrain = 10, nVal = 4, lastSeenMs = 1_500L,
                ),
            ),
            standings = mapOf(
                "base" to VariantStanding("base", 0.9f, 9, 2, listOf(0.8f, 0.85f, 0.9f), 0, 4),
                "deep" to VariantStanding("deep", 0.92f, 4, 1, listOf(0.9f, 0.92f), 1, 4),
            ),
            assignments = mapOf("client01" to "deep"),
            events = listOf(NetworkEvent(1_500L, EventType.SYNC, "round 4: 1 node(s) merged base,deep")),
            updatedMs = 1_500L,
        )
        val header = SyncProtocol.Header(network = state)
        val readBack = roundTrip(SyncProtocol.Message(SyncProtocol.NETWORK, header, null))

        assertEquals(SyncProtocol.NETWORK, readBack.type)
        assertEquals(state, readBack.header.network)
        assertNull(readBack.weights)
    }

    @Test
    fun doneMessageRoundTrips() {
        val header = SyncProtocol.Header(deviceId = "owner01")
        val readBack = roundTrip(SyncProtocol.Message(SyncProtocol.DONE, header, null))

        assertEquals(SyncProtocol.DONE, readBack.type)
        assertEquals(header, readBack.header)
        assertNull(readBack.weights)
    }

    @Test
    fun badMagicThrows() {
        val out = ByteArrayOutputStream()
        SyncProtocol.write(out, SyncProtocol.Message(SyncProtocol.HELLO, SyncProtocol.Header(), null))
        val bytes = out.toByteArray()
        bytes[0] = 'X'.code.toByte()

        assertThrows(IOException::class.java) {
            SyncProtocol.read(ByteArrayInputStream(bytes))
        }
    }

    @Test
    fun wrongVersionThrows() {
        val out = ByteArrayOutputStream()
        SyncProtocol.write(out, SyncProtocol.Message(SyncProtocol.HELLO, SyncProtocol.Header(), null))
        val bytes = out.toByteArray()
        bytes[4] = 1 // magic is 4 bytes, version is the next byte

        assertThrows(IOException::class.java) {
            SyncProtocol.read(ByteArrayInputStream(bytes))
        }
    }
}
