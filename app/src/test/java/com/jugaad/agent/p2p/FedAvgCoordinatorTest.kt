package com.jugaad.agent.p2p

import com.jugaad.agent.fl.EventType
import com.jugaad.agent.fl.FlVariants
import com.jugaad.agent.fl.NetworkState
import com.jugaad.agent.fl.NodeConfig
import com.jugaad.agent.fl.NodeMode
import com.jugaad.agent.fl.VariantMetrics
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket

/** One variant's fake local state: weights, metrics, and a caller-supplied evaluate() function. */
private class FakeVariant(
    var weights: FloatArray,
    var metrics: VariantMetrics,
    val evaluateFn: (FloatArray?) -> Float,
)

/** Fake [FlPeer] with known variants/weights, caller-supplied evaluate() functions, and an
 * in-memory sample store (own + pool) so sample-exchange can be exercised without [FlRuntime]. */
private class FakeFlPeer(
    deviceId: String,
    name: String = deviceId,
    mode: NodeMode = NodeMode.STABLE,
    pinnedChallenger: String? = null,
    initialNetwork: NetworkState = NetworkState.empty(),
    private val sharing: Boolean = true,
    private val schemaVersion: Int = 3,
) : FlPeer {
    override val config: NodeConfig = NodeConfig(deviceId, name, mode, pinnedChallenger, autoTrain = false, autoSync = false)
    override var network: NetworkState = initialNetwork

    val events = mutableListOf<Pair<EventType, String>>()

    private val variants = mutableMapOf<String, FakeVariant>()
    private val ownSamples = mutableListOf<SyncProtocol.FlSampleWire>()
    private val pool = mutableListOf<SyncProtocol.FlSampleWire>()

    fun addVariant(
        id: String,
        weights: FloatArray,
        round: Int,
        nTrain: Int,
        nVal: Int,
        trainAcc: Float,
        valAcc: Float,
        trainMs: Long = 0L,
        evaluateFn: (FloatArray?) -> Float,
    ) {
        variants[id] = FakeVariant(
            weights,
            VariantMetrics(
                variantId = id, round = round, nTrain = nTrain, nVal = nVal,
                trainAcc = trainAcc, valAcc = valAcc, lastLoss = 0f, lastTrainedMs = 0L, trainMs = trainMs,
            ),
            evaluateFn,
        )
    }

    fun addOwnSample(wire: SyncProtocol.FlSampleWire) {
        ownSamples += wire
    }

    fun addPoolSample(wire: SyncProtocol.FlSampleWire) {
        pool += wire
    }

    fun poolIds(): Set<String> = pool.map { it.id }.toSet()

    override fun heldVariantIds(): List<String> = variants.keys.toList()
    override fun metrics(id: String): VariantMetrics = variants.getValue(id).metrics
    override fun weights(id: String): FloatArray = variants.getValue(id).weights
    override fun evaluateVal(id: String, w: FloatArray?): Float = variants[id]?.evaluateFn?.invoke(w) ?: -1f
    override fun applyMerged(id: String, w: FloatArray, round: Int) {
        val existing = variants[id]
        if (existing != null) {
            existing.weights = w
            existing.metrics = existing.metrics.copy(round = round)
        } else {
            variants[id] = FakeVariant(
                w,
                VariantMetrics(
                    variantId = id, round = round, nTrain = 0, nVal = 0,
                    trainAcc = 0f, valAcc = -1f, lastLoss = 0f, lastTrainedMs = 0L, trainMs = 0L,
                ),
            ) { -1f }
        }
    }

    override fun applyNetwork(s: NetworkState) {
        network = s
    }

    override fun hasVariant(id: String): Boolean = variants.containsKey(id)

    override fun applyPolicy(policy: Map<String, String>): Boolean = false

    override fun addEvent(type: EventType, text: String) {
        events += type to text
    }

    override fun sharingEnabled(): Boolean = sharing
    override fun featureSchemaVersion(): Int = schemaVersion
    override fun knownSampleIds(): Set<String> = (ownSamples + pool).map { it.id }.toSet()
    override fun samplesFor(ids: Set<String>): List<SyncProtocol.FlSampleWire> =
        (ownSamples + pool).filter { it.id in ids }

    override fun poolSamplesExcept(known: Set<String>, limit: Int): List<SyncProtocol.FlSampleWire> =
        (pool + ownSamples).filter { it.id !in known }.take(limit)

    override fun acceptShared(samples: List<SyncProtocol.FlSampleWire>): Int {
        var added = 0
        for (s in samples) {
            if (pool.none { it.id == s.id }) {
                pool += s
                added++
            }
        }
        return added
    }
}

private fun wire(id: String, origin: String) = SyncProtocol.FlSampleWire(
    id = id, origin = origin, assetId = "asset1", machineTypeId = null,
    x = FloatArray(4), label = 0, score = null, ts = 0L,
)

/**
 * Exercises [FedAvgCoordinator] over real loopback sockets, with fake peers so the test
 * needs neither `FlRuntime` nor TFLite. `runAsClient` dials `ownerAddress:SyncProtocol.port()`
 * (the config-default port, per contract, not a caller-supplied one unless the test passes
 * one) so the server socket here is bound to that same port rather than an OS-assigned
 * ephemeral one.
 */
class FedAvgCoordinatorTest {

    @Test
    fun twoVariantSessionMergesSharedVariantAndKeepsClientOnlyOne() {
        val server = ServerSocket(SyncProtocol.port())
        try {
            val ownerBaseWeights = floatArrayOf(1f, 2f, 3f, 4f)
            val clientBaseWeights = floatArrayOf(5f, 6f, 7f, 8f)
            val clientSmallWeights = floatArrayOf(9f, 9f)
            val ownerDeepWeights = floatArrayOf(11f, 11f)

            val owner = FakeFlPeer("owner01", mode = NodeMode.STABLE)
            owner.addVariant("base", ownerBaseWeights, round = 3, nTrain = 20, nVal = 6, trainAcc = 0.9f, valAcc = 0.9f) { 0.9f }
            owner.addVariant("deep", ownerDeepWeights, round = 1, nTrain = 12, nVal = 4, trainAcc = 0.8f, valAcc = 0.8f) { 0.8f }

            // client's challenger is pinned to "small", so it never lacks a challenger and
            // "deep" (owner-only, not assigned to this client) must not come back to it.
            val client = FakeFlPeer("client01", mode = NodeMode.EXPERIMENTAL, pinnedChallenger = "small")
            client.addVariant("base", clientBaseWeights, round = 2, nTrain = 10, nVal = 5, trainAcc = 0.88f, valAcc = 0.88f) { 0.9f }
            client.addVariant("small", clientSmallWeights, round = 5, nTrain = 7, nVal = 4, trainAcc = 0.85f, valAcc = 0.85f) { 0.9f }

            val expectedBase = FloatArray(4) { i ->
                (ownerBaseWeights[i] * 20 + clientBaseWeights[i] * 10) / 30f
            }
            val expectedBaseRound = maxOf(3, 2) + 1
            val expectedSmallRound = 5 + 1

            val ownerCoordinator = FedAvgCoordinator(owner)
            val clientCoordinator = FedAvgCoordinator(client)

            val (ownerResult, clientResult) = runBlocking {
                val serverJob = async { ownerCoordinator.serveOnce(server, windowMs = 500) }
                val clientJob = async { clientCoordinator.runAsClient("127.0.0.1") }
                Pair(serverJob.await(), clientJob.await())
            }

            assertArrayEquals(expectedBase, owner.weights("base"), 1e-4f)
            assertArrayEquals(expectedBase, client.weights("base"), 1e-4f)
            assertEquals(expectedBaseRound, owner.metrics("base").round)
            assertEquals(expectedBaseRound, client.metrics("base").round)

            // Owner lacks "small": FedAvg over a single contributor is exactly that
            // contributor's own weights, unchanged.
            assertArrayEquals(clientSmallWeights, client.weights("small"), 1e-6f)
            assertEquals(expectedSmallRound, client.metrics("small").round)

            // "deep" is owner-only and was never assigned to this client, so the client
            // must never have received (and applied) it.
            assertFalse(client.hasVariant("deep"))

            assertTrue("owner should accept base: ${ownerResult.message}", ownerResult.accepted["base"] == true)
            assertTrue("client should accept base: ${clientResult.message}", clientResult.accepted["base"] == true)
        } finally {
            server.close()
        }
    }

    @Test
    fun clientWithoutChallengerReceivesAnAssignmentInNetworkState() {
        val server = ServerSocket(SyncProtocol.port())
        try {
            val owner = FakeFlPeer("owner01", mode = NodeMode.STABLE)
            owner.addVariant("base", floatArrayOf(1f, 1f), round = 1, nTrain = 20, nVal = 6, trainAcc = 0.9f, valAcc = 0.9f) { 0.9f }

            val client = FakeFlPeer("client01", mode = NodeMode.EXPERIMENTAL, pinnedChallenger = null)
            client.addVariant("base", floatArrayOf(2f, 2f), round = 1, nTrain = 10, nVal = 5, trainAcc = 0.85f, valAcc = 0.85f) { 0.9f }

            runBlocking {
                val serverJob = async { FedAvgCoordinator(owner).serveOnce(server, windowMs = 500) }
                val clientJob = async { FedAvgCoordinator(client).runAsClient("127.0.0.1") }
                serverJob.await()
                clientJob.await()
            }

            assertNotNull("client should receive a challenger assignment", client.network.assignments["client01"])
        } finally {
            server.close()
        }
    }

    @Test
    fun challengerBeatingChampionOnTwoSuccessiveRoundsGetsPromoted() {
        val server = ServerSocket(SyncProtocol.port())
        try {
            val owner = FakeFlPeer("owner01", mode = NodeMode.STABLE)
            owner.addVariant("base", floatArrayOf(1f, 1f), round = 1, nTrain = 20, nVal = 10, trainAcc = 0.9f, valAcc = 0.80f) { 0.80f }

            val client = FakeFlPeer("client01", mode = NodeMode.EXPERIMENTAL, pinnedChallenger = "deep")
            client.addVariant("base", floatArrayOf(1f, 1f), round = 1, nTrain = 10, nVal = 10, trainAcc = 0.9f, valAcc = 0.80f) { 0.80f }
            // beats the champion by 0.05 >= the 0.03 promotion margin
            client.addVariant("deep", floatArrayOf(2f, 2f), round = 1, nTrain = 10, nVal = 10, trainAcc = 0.9f, valAcc = 0.85f) { 0.85f }

            val ownerCoordinator = FedAvgCoordinator(owner)

            val firstResult = runBlocking {
                val serverJob = async { ownerCoordinator.serveOnce(server, windowMs = 500) }
                val clientJob = async { FedAvgCoordinator(client).runAsClient("127.0.0.1") }
                val r = serverJob.await()
                clientJob.await()
                r
            }
            assertNull("no promotion yet after a single winning round", firstResult.promoted)

            val secondResult = runBlocking {
                val serverJob = async { ownerCoordinator.serveOnce(server, windowMs = 500) }
                val clientJob = async { FedAvgCoordinator(client).runAsClient("127.0.0.1") }
                val r = serverJob.await()
                clientJob.await()
                r
            }

            assertEquals("deep", secondResult.promoted)
            assertEquals("deep", owner.network.championId)
        } finally {
            server.close()
        }
    }

    @Test
    fun clientWeightsHeaderCarriesTrainMsAndUsesUnlabelledForOwnerPromotionReports() {
        // FedAvgCoordinator.serveOnce builds each Promotion.Report straight from the
        // WEIGHTS header it received (h.trainMs ?: 0L); a fake owner-side reader that
        // captures that same header is equivalent evidence without needing to observe
        // Promotion.update's internals.
        val server = ServerSocket(SyncProtocol.port())
        server.soTimeout = 5000
        try {
            val client = FakeFlPeer("client01", mode = NodeMode.STABLE)
            client.addVariant(
                "base", floatArrayOf(1f, 1f), round = 2, nTrain = 10, nVal = 5,
                trainAcc = 0.9f, valAcc = 0.88f, trainMs = 1234L,
            ) { 0.9f }

            val weightsHeaders = mutableListOf<SyncProtocol.Header>()

            runBlocking {
                // Blocking socket I/O must not run on runBlocking's single thread or the client never starts.
                val ownerJob = async(kotlinx.coroutines.Dispatchers.IO) {
                    server.accept().use { socket ->
                        SyncProtocol.read(socket.getInputStream()) // HELLO
                        readLoop@ while (true) {
                            val msg = SyncProtocol.read(socket.getInputStream())
                            when (msg.type) {
                                SyncProtocol.WEIGHTS -> weightsHeaders.add(msg.header)
                                SyncProtocol.DONE -> break@readLoop
                            }
                        }
                        SyncProtocol.write(socket.getOutputStream(), SyncProtocol.Message(SyncProtocol.DONE, SyncProtocol.Header(), null))
                    }
                }
                val clientJob = async { FedAvgCoordinator(client).runAsClient("127.0.0.1") }
                ownerJob.await()
                clientJob.await()
            }

            assertEquals(1, weightsHeaders.size)
            val header = weightsHeaders.first()
            assertEquals("base", header.variantId)
            assertEquals(1234L, header.trainMs)
            assertEquals(FlVariants.byId("base").usesUnlabelled, header.usesUnlabelled)
        } finally {
            server.close()
        }
    }

    @Test
    fun serveOnceReturnsIdleWithoutThrowingWhenNoClientConnects() {
        val server = ServerSocket(0)
        server.soTimeout = 200
        try {
            val owner = FakeFlPeer("owner01")
            val result = runBlocking { FedAvgCoordinator(owner).serveOnce(server, windowMs = 500) }

            assertEquals(0, result.peers)
            assertEquals("idle", result.message)
        } finally {
            server.close()
        }
    }

    @Test
    fun sampleExchangeRoundTripMovesSamplesBothWaysAndCountsThem() {
        val server = ServerSocket(SyncProtocol.port())
        try {
            val owner = FakeFlPeer("owner01", mode = NodeMode.STABLE)
            owner.addVariant("base", floatArrayOf(1f, 1f), round = 1, nTrain = 10, nVal = 5, trainAcc = 0.9f, valAcc = 0.9f) { 0.9f }
            owner.addPoolSample(wire("o1", "owner01"))
            owner.addPoolSample(wire("o2", "owner01"))

            val client = FakeFlPeer("client01", mode = NodeMode.STABLE)
            client.addVariant("base", floatArrayOf(2f, 2f), round = 1, nTrain = 10, nVal = 5, trainAcc = 0.9f, valAcc = 0.9f) { 0.9f }
            client.addOwnSample(wire("c1", "client01"))
            client.addOwnSample(wire("c2", "client01"))
            client.addOwnSample(wire("c3", "client01"))

            val (ownerResult, clientResult) = runBlocking {
                val serverJob = async { FedAvgCoordinator(owner).serveOnce(server, windowMs = 500) }
                val clientJob = async { FedAvgCoordinator(client).runAsClient("127.0.0.1") }
                Pair(serverJob.await(), clientJob.await())
            }

            // The owner keeps its own pool samples and gains the client's three.
            assertEquals(setOf("o1", "o2", "c1", "c2", "c3"), owner.poolIds())
            assertEquals(setOf("o1", "o2"), client.poolIds())
            assertEquals(3, ownerResult.samplesReceived)
            assertEquals(2, ownerResult.samplesSent)
            assertEquals(2, clientResult.samplesReceived)
            assertEquals(3, clientResult.samplesSent)
        } finally {
            server.close()
        }
    }

    @Test
    fun sharingDisabledOnOneSideExchangesZeroSamplesButStillMergesWeights() {
        val server = ServerSocket(SyncProtocol.port())
        try {
            val owner = FakeFlPeer("owner01", mode = NodeMode.STABLE, sharing = false)
            owner.addVariant("base", floatArrayOf(1f, 1f), round = 1, nTrain = 10, nVal = 5, trainAcc = 0.9f, valAcc = 0.9f) { 0.9f }
            owner.addPoolSample(wire("o1", "owner01"))

            val client = FakeFlPeer("client01", mode = NodeMode.STABLE, sharing = true)
            client.addVariant("base", floatArrayOf(2f, 2f), round = 1, nTrain = 10, nVal = 5, trainAcc = 0.9f, valAcc = 0.9f) { 0.9f }
            client.addOwnSample(wire("c1", "client01"))

            val (ownerResult, clientResult) = runBlocking {
                val serverJob = async { FedAvgCoordinator(owner).serveOnce(server, windowMs = 500) }
                val clientJob = async { FedAvgCoordinator(client).runAsClient("127.0.0.1") }
                Pair(serverJob.await(), clientJob.await())
            }

            assertEquals(0, ownerResult.samplesReceived)
            assertEquals(0, ownerResult.samplesSent)
            assertEquals(0, clientResult.samplesSent)
            assertEquals(0, clientResult.samplesReceived)
            assertTrue(owner.poolIds().none { it == "c1" })
            assertTrue(client.poolIds().none { it == "o1" })
            assertTrue("weights should still merge", ownerResult.accepted["base"] == true)
        } finally {
            server.close()
        }
    }

    @Test
    fun zeroTrainedSamplesSkipsVariantWithoutAdvancingRoundOrWeights() {
        val server = ServerSocket(SyncProtocol.port())
        try {
            val owner = FakeFlPeer("owner01", mode = NodeMode.STABLE)
            val ownerWeights = floatArrayOf(1f, 2f)
            owner.addVariant("base", ownerWeights, round = 5, nTrain = 0, nVal = 0, trainAcc = 0f, valAcc = -1f) { -1f }

            val client = FakeFlPeer("client01", mode = NodeMode.STABLE)
            client.addVariant("base", floatArrayOf(9f, 9f), round = 5, nTrain = 0, nVal = 0, trainAcc = 0f, valAcc = -1f) { -1f }

            runBlocking {
                val serverJob = async { FedAvgCoordinator(owner).serveOnce(server, windowMs = 500) }
                val clientJob = async { FedAvgCoordinator(client).runAsClient("127.0.0.1") }
                serverJob.await()
                clientJob.await()
            }

            // No node trained this round: nothing to merge, so weights and the round counter
            // stay exactly as they were rather than fabricate progress from an all-NaN average.
            assertArrayEquals(ownerWeights, owner.weights("base"), 1e-6f)
            assertEquals(5, owner.metrics("base").round)
        } finally {
            server.close()
        }
    }

    @Test
    fun nonFiniteMergeResultIsRejectedAndLocalWeightsAreUntouched() {
        val server = ServerSocket(SyncProtocol.port())
        try {
            val owner = FakeFlPeer("owner01", mode = NodeMode.STABLE)
            // Simulates a phone already carrying a poisoned weight from before this fix.
            val ownerWeights = floatArrayOf(Float.NaN, 2f)
            owner.addVariant("base", ownerWeights, round = 5, nTrain = 10, nVal = 5, trainAcc = 0.9f, valAcc = 0.9f) { 0.9f }

            val client = FakeFlPeer("client01", mode = NodeMode.STABLE)
            client.addVariant("base", floatArrayOf(3f, 4f), round = 5, nTrain = 10, nVal = 5, trainAcc = 0.9f, valAcc = 0.9f) { 0.9f }

            runBlocking {
                val serverJob = async { FedAvgCoordinator(owner).serveOnce(server, windowMs = 500) }
                val clientJob = async { FedAvgCoordinator(client).runAsClient("127.0.0.1") }
                serverJob.await()
                clientJob.await()
            }

            // totalN > 0 here, so FedAvg.merge doesn't short-circuit; the NaN contributor still
            // makes the result non-finite, and the coordinator must reject it before applying it.
            assertArrayEquals(ownerWeights, owner.weights("base"), 0f)
            assertEquals(5, owner.metrics("base").round)
        } finally {
            server.close()
        }
    }

    // D1/D2: a device reconnecting inside one merge window (an auto-join retry after a
    // timeout is enough) must be counted once, not once per connection, or its weights and
    // nTrain get FedAvg'd in several times over and the node/event counts are inflated.
    @Test
    fun duplicateDeviceIdConnectingSeveralTimesContributesOnceAndProducesOneCard() {
        val server = ServerSocket(SyncProtocol.port())
        try {
            val owner = FakeFlPeer("owner01", mode = NodeMode.STABLE)
            owner.addVariant("base", floatArrayOf(0f, 0f), round = 1, nTrain = 30, nVal = 0, trainAcc = 0.9f, valAcc = -1f) { -1f }

            // Same deviceId connects three times within one window (an auto-join retry after a
            // timeout is enough to do this on a real phone). Each reports nTrain=10; if dedup
            // fails, phoneA's contribution is tripled to nTrain=30 and skews the weighted
            // average away from the value a single, correctly-deduplicated contribution gives.
            val result = runBlocking {
                val serverJob = async { FedAvgCoordinator(owner).serveOnce(server, windowMs = 700) }
                val clientJobs = (1..3).map {
                    async {
                        val phone = FakeFlPeer("phoneA", mode = NodeMode.STABLE)
                        phone.addVariant("base", floatArrayOf(30f, 30f), round = 1, nTrain = 10, nVal = 0, trainAcc = 0.9f, valAcc = -1f) { -1f }
                        FedAvgCoordinator(phone).runAsClient("127.0.0.1")
                    }
                }
                val r = serverJob.await()
                clientJobs.forEach { it.await() } // the 2 deduplicated-away sockets fail their read; that's fine here
                r
            }

            // owner(nTrain=30, w=0) + one deduplicated phoneA(nTrain=10, w=30) -> (0*30+30*10)/40 = 7.5.
            // Were dedup not applied, phoneA would count 3x: (0*30+30*10*3)/60 = 15.
            assertArrayEquals(floatArrayOf(7.5f, 7.5f), owner.weights("base"), 1e-3f)
            assertEquals(1, result.peers)
            assertEquals(1, owner.network.nodes.count { it.deviceId == "phoneA" })
        } finally {
            server.close()
        }
    }

    @Test
    fun distinctDeviceIdsAllSurviveDeduplication() {
        val server = ServerSocket(SyncProtocol.port())
        try {
            val owner = FakeFlPeer("owner01", mode = NodeMode.STABLE)
            owner.addVariant("base", floatArrayOf(0f, 0f), round = 1, nTrain = 30, nVal = 0, trainAcc = 0.9f, valAcc = -1f) { -1f }

            val clientA = FakeFlPeer("clientA", mode = NodeMode.STABLE)
            clientA.addVariant("base", floatArrayOf(10f, 10f), round = 1, nTrain = 10, nVal = 0, trainAcc = 0.9f, valAcc = -1f) { -1f }
            val clientB = FakeFlPeer("clientB", mode = NodeMode.STABLE)
            clientB.addVariant("base", floatArrayOf(20f, 20f), round = 1, nTrain = 20, nVal = 0, trainAcc = 0.9f, valAcc = -1f) { -1f }

            val result = runBlocking {
                val serverJob = async { FedAvgCoordinator(owner).serveOnce(server, windowMs = 700) }
                val jobA = async { FedAvgCoordinator(clientA).runAsClient("127.0.0.1") }
                val jobB = async { FedAvgCoordinator(clientB).runAsClient("127.0.0.1") }
                val r = serverJob.await()
                jobA.await()
                jobB.await()
                r
            }

            // Two distinct clients: both must survive dedup and both contribute to the merge.
            assertEquals(2, result.peers)
            assertEquals(1, owner.network.nodes.count { it.deviceId == "clientA" })
            assertEquals(1, owner.network.nodes.count { it.deviceId == "clientB" })
            // Weighted average across owner(30) + clientA(10) + clientB(20), total 60.
            val expected = (0f * 30 + 10f * 10 + 20f * 20) / 60f
            assertArrayEquals(floatArrayOf(expected, expected), owner.weights("base"), 1e-3f)
        } finally {
            server.close()
        }
    }

    @Test
    fun weightsWithMismatchedSchemaVersionAreIgnoredWithEvent() {
        val server = ServerSocket(SyncProtocol.port())
        try {
            val owner = FakeFlPeer("owner01", mode = NodeMode.STABLE, schemaVersion = 3)
            owner.addVariant("base", floatArrayOf(1f, 1f), round = 1, nTrain = 10, nVal = 5, trainAcc = 0.9f, valAcc = 0.9f) { 0.9f }

            val client = FakeFlPeer("client01", mode = NodeMode.STABLE, schemaVersion = 4)
            client.addVariant("base", floatArrayOf(9f, 9f), round = 1, nTrain = 10, nVal = 5, trainAcc = 0.9f, valAcc = 0.9f) { 0.9f }

            runBlocking {
                val serverJob = async { FedAvgCoordinator(owner).serveOnce(server, windowMs = 500) }
                val clientJob = async { FedAvgCoordinator(client).runAsClient("127.0.0.1") }
                serverJob.await()
                clientJob.await()
            }

            // The client's mismatched contribution never enters the merge: owner's "base" is
            // the single remaining (its own) contributor, so it comes back unchanged.
            assertArrayEquals(floatArrayOf(1f, 1f), owner.weights("base"), 1e-6f)
            assertTrue(
                "expected a schema-mismatch INFO event, got ${owner.events}",
                owner.events.any { it.first == EventType.INFO && it.second.contains("schema mismatch") && it.second.contains("client01") },
            )
        } finally {
            server.close()
        }
    }
}
