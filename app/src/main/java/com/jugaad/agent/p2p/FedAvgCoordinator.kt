package com.jugaad.agent.p2p

import com.jugaad.agent.core.Logx
import com.jugaad.agent.core.config.ConfigStore
import com.jugaad.agent.fl.AcceptGuard
import com.jugaad.agent.fl.EventType
import com.jugaad.agent.fl.FedAvg
import com.jugaad.agent.fl.FlVariants
import com.jugaad.agent.fl.NetworkEvent
import com.jugaad.agent.fl.NodeCard
import com.jugaad.agent.fl.NodeMode
import com.jugaad.agent.fl.Promotion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException

enum class SyncRole { GROUP_OWNER, CLIENT }

data class SyncResult(
    val role: SyncRole,
    val peers: Int,
    val variants: List<String>,
    val roundsBefore: Map<String, Int>,
    val roundsAfter: Map<String, Int>,
    val accepted: Map<String, Boolean>,
    val valBefore: Map<String, Float>,
    val valAfter: Map<String, Float>,
    val promoted: String?,
    val message: String,
    /** Samples handed to peers this session (v4 plan §5). */
    val samplesSent: Int = 0,
    /** Samples accepted from peers into the local/pool store this session. */
    val samplesReceived: Int = 0,
)

/**
 * One multi-variant FedAvg sync session, for either side of the WiFi Direct link. Takes
 * an [FlPeer] rather than the concrete `FlRuntime` so the merge/accept-guard/promotion
 * logic can be exercised with loopback sockets and a fake peer in a JVM test.
 */
class FedAvgCoordinator(private val peer: FlPeer) {

    /**
     * Connects to the group owner, uploads one WEIGHTS message per held variant, then
     * applies whatever MERGED/NETWORK replies come back until DONE. When both sides have
     * sharing enabled ([FlPeer.sharingEnabled]), also offers this node's known sample ids
     * right after the WEIGHTS batch and answers the owner's SAMPLE_IDS request with a
     * SAMPLES message before the terminal DONE (v4 plan §5).
     */
    suspend fun runAsClient(ownerAddress: String, port: Int = SyncProtocol.port()): SyncResult = withContext(Dispatchers.IO) {
        val syncCfg = ConfigStore.effective.value.sync
        val deviceId = peer.config.deviceId
        val heldIds = peer.heldVariantIds()
        val roundsBefore = heldIds.associateWith { peer.metrics(it).round }
        val valBefore = heldIds.associateWith { peer.evaluateVal(it, null) }
        val championBefore = peer.network.championId
        val sharing = peer.sharingEnabled()

        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(ownerAddress, port), syncCfg.connectTimeoutMs)
                socket.soTimeout = syncCfg.readTimeoutMs // don't hang forever if the owner dies mid-round

                val effectiveChallenger = peer.config.pinnedChallenger ?: peer.network.assignments[deviceId]
                SyncProtocol.write(
                    socket.getOutputStream(),
                    SyncProtocol.Message(
                        SyncProtocol.HELLO,
                        SyncProtocol.Header(
                            deviceId = deviceId,
                            name = peer.config.name,
                            mode = peer.config.mode,
                            challenger = effectiveChallenger,
                        ),
                        null,
                    ),
                )

                for (id in heldIds) {
                    val m = peer.metrics(id)
                    SyncProtocol.write(
                        socket.getOutputStream(),
                        SyncProtocol.Message(
                            SyncProtocol.WEIGHTS,
                            SyncProtocol.Header(
                                deviceId = deviceId,
                                variantId = id,
                                round = m.round,
                                nTrain = m.nTrain,
                                nVal = m.nVal,
                                trainAcc = m.trainAcc,
                                valAcc = m.valAcc,
                                trainMs = m.trainMs,
                                usesUnlabelled = FlVariants.byId(id).usesUnlabelled,
                                featureSchemaVersion = peer.featureSchemaVersion(),
                            ),
                            peer.weights(id),
                        ),
                    )
                }

                if (sharing) {
                    SyncProtocol.write(
                        socket.getOutputStream(),
                        SyncProtocol.Message(
                            SyncProtocol.SAMPLE_IDS,
                            SyncProtocol.Header(deviceId = deviceId, ids = peer.knownSampleIds().toList()),
                            null,
                        ),
                    )
                }

                SyncProtocol.write(
                    socket.getOutputStream(),
                    SyncProtocol.Message(SyncProtocol.DONE, SyncProtocol.Header(deviceId = deviceId), null),
                )

                val roundsAfter = roundsBefore.toMutableMap()
                val valAfter = valBefore.toMutableMap()
                val accepted = mutableMapOf<String, Boolean>()
                var samplesSent = 0
                var samplesReceived = 0

                readLoop@ while (true) {
                    val msg = SyncProtocol.read(socket.getInputStream())
                    when (msg.type) {
                        SyncProtocol.MERGED -> {
                            val vId = msg.header.variantId
                            val w = msg.weights
                            if (vId == null || w == null) continue@readLoop
                            val newRound = msg.header.round ?: (roundsBefore[vId] ?: 0)
                            if (peer.hasVariant(vId)) {
                                val nTrain = peer.metrics(vId).nTrain
                                val before = peer.evaluateVal(vId, null)
                                val after = peer.evaluateVal(vId, w)
                                val ok = AcceptGuard.accept(nTrain, before, after, ConfigStore.effective.value)
                                accepted[vId] = ok
                                valAfter[vId] = after
                                if (ok) {
                                    peer.applyMerged(vId, w, newRound)
                                    roundsAfter[vId] = newRound
                                }
                            } else {
                                // A fresh challenger assignment the client didn't hold before this
                                // session: nothing local to protect, so accept unconditionally.
                                peer.applyMerged(vId, w, newRound)
                                accepted[vId] = true
                                roundsAfter[vId] = newRound
                                valAfter[vId] = peer.evaluateVal(vId, null)
                            }
                        }

                        SyncProtocol.SAMPLE_IDS -> {
                            // The owner asking for specific ids implies both sides share; answer
                            // in kind even if (defensively) sharing were somehow off locally.
                            val wanted = msg.header.ids.orEmpty().toSet()
                            val toSend = if (sharing) peer.samplesFor(wanted) else emptyList()
                            SyncProtocol.write(
                                socket.getOutputStream(),
                                SyncProtocol.Message(
                                    SyncProtocol.SAMPLES,
                                    SyncProtocol.Header(
                                        deviceId = deviceId,
                                        featureSchemaVersion = peer.featureSchemaVersion(),
                                        samples = toSend,
                                    ),
                                    null,
                                ),
                            )
                            samplesSent += toSend.size
                        }

                        SyncProtocol.SAMPLES -> {
                            val incoming = msg.header.samples.orEmpty()
                            val schema = msg.header.featureSchemaVersion
                            if (schema == null || schema == peer.featureSchemaVersion()) {
                                samplesReceived += peer.acceptShared(incoming)
                            } else {
                                peer.addEvent(EventType.INFO, "schema mismatch from ${msg.header.deviceId ?: "peer"}")
                            }
                        }

                        SyncProtocol.NETWORK -> {
                            msg.header.network?.let { state ->
                                peer.applyNetwork(state)
                                if (peer.applyPolicy(state.policy)) {
                                    val ownerName = state.nodes.firstOrNull { it.isOwner }?.name ?: "owner"
                                    peer.addEvent(EventType.INFO, "policy applied from $ownerName")
                                }
                            }
                        }

                        SyncProtocol.DONE -> break@readLoop

                        else -> Logx.w("fl sync: client got unexpected message type=${msg.type}")
                    }
                }

                val championAfter = peer.network.championId
                val promoted = if (championAfter != championBefore) championAfter else null

                val logMsg = "fl sync: client variants=$heldIds, rounds $roundsBefore -> $roundsAfter, promoted=$promoted"
                Logx.i(logMsg)
                SyncResult(
                    SyncRole.CLIENT, 1, heldIds, roundsBefore, roundsAfter, accepted, valBefore, valAfter, promoted, logMsg,
                    samplesSent = samplesSent, samplesReceived = samplesReceived,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val msg = "fl sync: client failed: ${describe(e)}"
            Logx.i(msg)
            SyncResult(SyncRole.CLIENT, 1, heldIds, roundsBefore, roundsBefore, emptyMap(), valBefore, valBefore, null, msg)
        }
    }

    /**
     * Accepts the first client, then keeps accepting more until [windowMs] have elapsed
     * since that first connection. Reads HELLO + one WEIGHTS per held variant + an optional
     * SAMPLE_IDS + DONE from each client, FedAvg-merges every variant that showed up this
     * window (weighted by nTrain, across contributors plus the owner's own copy if it holds
     * that variant), runs the promotion/assignment pipeline, then replies to every client
     * with its MERGED variant(s), an optional sample exchange (v4 plan §5), the new NETWORK
     * state (stamped with this node's policy), and DONE. [server]'s `soTimeout` governs how
     * long the initial `accept()` blocks; the caller (e.g. [FlSyncService]) owns that value.
     */
    suspend fun serveOnce(
        server: ServerSocket,
        windowMs: Long = ConfigStore.effective.value.sync.windowMs.toLong(),
    ): SyncResult = withContext(Dispatchers.IO) {
        val ownerDeviceId = peer.config.deviceId
        val sockets = mutableListOf<Socket>()
        try {
            // A PING never starts (or counts toward) the merge window: keep accepting until a
            // real HELLO client shows up, or the server's own accept() times out first (idle).
            var first: Socket? = null
            val rawSessions = mutableListOf<ClientSession>()
            while (first == null) {
                val candidate = try {
                    server.accept()
                } catch (e: SocketTimeoutException) {
                    return@withContext SyncResult(
                        SyncRole.GROUP_OWNER, 0, emptyList(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), null, "idle",
                    )
                }
                when (val outcome = acceptFrame(candidate, ownerDeviceId)) {
                    is AcceptOutcome.Hello -> {
                        first = candidate
                        rawSessions.add(outcome.session)
                    }
                    AcceptOutcome.Ping, AcceptOutcome.Failed -> {
                        // answered (or logged) and closed inside acceptFrame; keep waiting for
                        // the first real client without touching the window.
                    }
                }
            }
            sockets.add(first)

            val deadline = System.currentTimeMillis() + windowMs
            val previousTimeout = server.soTimeout
            try {
                server.soTimeout = 200
                while (System.currentTimeMillis() < deadline) {
                    try {
                        val next = server.accept()
                        when (val outcome = acceptFrame(next, ownerDeviceId)) {
                            is AcceptOutcome.Hello -> {
                                sockets.add(next)
                                rawSessions.add(outcome.session)
                            }
                            AcceptOutcome.Ping, AcceptOutcome.Failed -> {
                                // a PING mid-window: answered and closed already, not a client
                            }
                        }
                    } catch (e: SocketTimeoutException) {
                        // keep polling until the window elapses
                    }
                }
            } finally {
                try {
                    server.soTimeout = previousTimeout
                } catch (e: IOException) {
                    Logx.w("fl sync: failed to restore server socket timeout", e)
                }
            }

            // De-duplicate by deviceId, keeping the LAST session seen this window: a phone that
            // reconnects mid-window (an auto-join retry after a timeout is enough) would otherwise
            // be recorded once per connection, so its weights and nTrain get FedAvg'd in several
            // times over and the node/event counts are inflated (D1/D2). The last session carries
            // the most recent weights, so it wins over any earlier one from the same deviceId.
            // Everything below (contributions, node cards, the event text, the reported node count)
            // reads this single deduplicated list rather than patching each call site separately.
            val sessions = LinkedHashMap<String, ClientSession>().apply {
                for (s in rawSessions) put(s.deviceId, s)
            }.values.toList()

            val now = System.currentTimeMillis()
            val championBefore = peer.network.championId
            val ownerHeldIds = peer.heldVariantIds()
            val allVariantIds = (sessions.flatMap { it.variants.keys } + ownerHeldIds).toSet()

            val roundsBefore = allVariantIds.associateWith { v -> if (peer.hasVariant(v)) peer.metrics(v).round else 0 }
            val newRoundByVariant = mutableMapOf<String, Int>()
            val mergedWeights = mutableMapOf<String, FloatArray>()
            val roundsAfter = mutableMapOf<String, Int>()
            val accepted = mutableMapOf<String, Boolean>()
            val valBefore = mutableMapOf<String, Float>()
            val valAfter = mutableMapOf<String, Float>()

            for (v in allVariantIds) {
                val contributions = mutableListOf<Pair<FloatArray, Int>>()
                var maxRound = 0
                for (s in sessions) {
                    val w = s.weights[v]
                    val h = s.variants[v]
                    if (w != null && h != null) {
                        contributions.add(w to (h.nTrain ?: 0))
                        maxRound = maxOf(maxRound, h.round ?: 0)
                    }
                }
                if (peer.hasVariant(v)) {
                    contributions.add(peer.weights(v) to peer.metrics(v).nTrain)
                    maxRound = maxOf(maxRound, peer.metrics(v).round)
                }
                if (contributions.isEmpty()) continue

                val merged = FedAvg.merge(contributions)
                if (merged == null) {
                    // Every contributor (owner included) reported zero trained samples this
                    // round: nothing to merge, so leave the variant's weights and round counter
                    // untouched rather than fabricate progress.
                    Logx.i("fl sync: skipping $v, no trained samples this round")
                    continue
                }
                if (merged.any { !it.isFinite() }) {
                    // Defense in depth against any other arithmetic path producing NaN/Infinity:
                    // never apply or persist a non-finite merge result.
                    Logx.w("fl sync: rejecting non-finite merge result for $v")
                    continue
                }
                val newRound = maxRound + 1
                mergedWeights[v] = merged
                newRoundByVariant[v] = newRound

                if (peer.hasVariant(v)) {
                    val nTrain = peer.metrics(v).nTrain
                    val before = peer.evaluateVal(v, null)
                    val after = peer.evaluateVal(v, merged)
                    val ok = AcceptGuard.accept(nTrain, before, after, ConfigStore.effective.value)
                    accepted[v] = ok
                    valBefore[v] = before
                    valAfter[v] = after
                    if (ok) {
                        peer.applyMerged(v, merged, newRound)
                        roundsAfter[v] = newRound
                    } else {
                        roundsAfter[v] = roundsBefore[v] ?: newRound
                    }
                } else {
                    roundsAfter[v] = newRound
                }
            }

            fun cardFor(
                deviceId: String,
                name: String,
                mode: NodeMode,
                challenger: String?,
                isOwner: Boolean,
                headers: Map<String, SyncProtocol.Header>,
            ): NodeCard {
                val champHeader = headers[championBefore]
                val challHeader = challenger?.let { headers[it] }
                return NodeCard(
                    deviceId = deviceId,
                    name = name,
                    mode = mode,
                    challenger = challenger,
                    isOwner = isOwner,
                    champRound = champHeader?.round ?: 0,
                    champValAcc = champHeader?.valAcc ?: -1f,
                    challValAcc = challHeader?.valAcc ?: -1f,
                    nTrain = champHeader?.nTrain ?: 0,
                    nVal = champHeader?.nVal ?: 0,
                    lastSeenMs = now,
                )
            }

            val ownerHeaders = ownerHeldIds.associateWith { v ->
                val m = peer.metrics(v)
                SyncProtocol.Header(round = m.round, nTrain = m.nTrain, nVal = m.nVal, trainAcc = m.trainAcc, valAcc = m.valAcc)
            }
            val ownerChallenger = peer.config.pinnedChallenger ?: peer.network.assignments[ownerDeviceId]
            val ownerCard = cardFor(ownerDeviceId, peer.config.name, peer.config.mode, ownerChallenger, true, ownerHeaders)
            val clientCards = sessions.map { s -> cardFor(s.deviceId, s.name, s.mode, s.challenger, false, s.variants) }

            val untouchedNodes = peer.network.nodes.filterNot { n ->
                n.deviceId == ownerDeviceId || sessions.any { it.deviceId == n.deviceId }
            }
            var state = peer.network.copy(nodes = untouchedNodes + ownerCard + clientCards)

            val reports = mutableListOf<Promotion.Report>()
            for (s in sessions) {
                for ((vId, h) in s.variants) {
                    reports.add(
                        Promotion.Report(
                            deviceId = s.deviceId,
                            variantId = vId,
                            nVal = h.nVal ?: 0,
                            valAcc = h.valAcc ?: -1f,
                            trainMs = h.trainMs ?: 0L,
                        ),
                    )
                }
            }
            for (v in ownerHeldIds) {
                val m = peer.metrics(v)
                reports.add(
                    Promotion.Report(
                        deviceId = ownerDeviceId,
                        variantId = v,
                        nVal = m.nVal,
                        valAcc = m.valAcc,
                        trainMs = m.trainMs,
                    ),
                )
            }

            state = Promotion.update(state, reports, newRoundByVariant, now)

            for (card in clientCards + ownerCard) {
                if (card.mode == NodeMode.EXPERIMENTAL && card.challenger == null) {
                    state = Promotion.assign(state, card)
                }
            }

            val champRoundForEvent = newRoundByVariant[championBefore] ?: (roundsBefore[championBefore] ?: 0)
            val syncText = "round $champRoundForEvent: ${sessions.size} node(s) merged ${allVariantIds.sorted().joinToString(",")}"
            state = state.copy(events = (state.events + NetworkEvent(now, EventType.SYNC, syncText)).takeLast(50))
            state = state.copy(policy = ConfigStore.policyOf(ConfigStore.effective.value))

            peer.applyNetwork(state)

            val ownerSharing = peer.sharingEnabled()
            val batchSize = ConfigStore.effective.value.sharing.batchSize
            var samplesSent = 0
            var samplesReceived = 0

            for (s in sessions) {
                try {
                    val out = s.socket.getOutputStream()
                    val assignedExtra = state.assignments[s.deviceId]
                    val toSend = (s.variants.keys + (assignedExtra?.let { setOf(it) } ?: emptySet())).distinct()
                    for (v in toSend) {
                        val w = mergedWeights[v] ?: continue
                        SyncProtocol.write(
                            out,
                            SyncProtocol.Message(
                                SyncProtocol.MERGED,
                                SyncProtocol.Header(deviceId = ownerDeviceId, variantId = v, round = newRoundByVariant[v]),
                                w,
                            ),
                        )
                    }

                    val offered = s.offeredIds
                    if (offered != null && ownerSharing) {
                        val wanted = offered.filterNot { it in peer.knownSampleIds() }.take(batchSize).toSet()
                        SyncProtocol.write(
                            out,
                            SyncProtocol.Message(
                                SyncProtocol.SAMPLE_IDS,
                                SyncProtocol.Header(deviceId = ownerDeviceId, ids = wanted.toList()),
                                null,
                            ),
                        )

                        val toGive = peer.poolSamplesExcept(offered, batchSize)
                        SyncProtocol.write(
                            out,
                            SyncProtocol.Message(
                                SyncProtocol.SAMPLES,
                                SyncProtocol.Header(
                                    deviceId = ownerDeviceId,
                                    featureSchemaVersion = peer.featureSchemaVersion(),
                                    samples = toGive,
                                ),
                                null,
                            ),
                        )
                        samplesSent += toGive.size

                        val reply = SyncProtocol.read(s.socket.getInputStream())
                        if (reply.type == SyncProtocol.SAMPLES) {
                            val incoming = reply.header.samples.orEmpty()
                            val schema = reply.header.featureSchemaVersion
                            if (schema == null || schema == peer.featureSchemaVersion()) {
                                samplesReceived += peer.acceptShared(incoming)
                            } else {
                                peer.addEvent(EventType.INFO, "schema mismatch from ${s.deviceId}")
                            }
                        }
                    }

                    SyncProtocol.write(out, SyncProtocol.Message(SyncProtocol.NETWORK, SyncProtocol.Header(network = state), null))
                    SyncProtocol.write(out, SyncProtocol.Message(SyncProtocol.DONE, SyncProtocol.Header(deviceId = ownerDeviceId), null))
                } catch (e: IOException) {
                    Logx.w("fl sync: failed to reply to a client", e)
                }
            }

            val promoted = if (state.championId != championBefore) state.championId else null
            val msg = "fl sync: owner ${sessions.size} node(s) merged ${allVariantIds.sorted()}, promoted=$promoted"
            Logx.i(msg)
            SyncResult(
                SyncRole.GROUP_OWNER,
                sessions.size,
                allVariantIds.toList(),
                roundsBefore,
                roundsAfter,
                accepted,
                valBefore,
                valAfter,
                promoted,
                msg,
                samplesSent = samplesSent,
                samplesReceived = samplesReceived,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val msg = "fl sync: owner failed: ${describe(e)}"
            Logx.i(msg)
            SyncResult(SyncRole.GROUP_OWNER, 0, emptyList(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), null, msg)
        } finally {
            for (s in sockets) {
                try {
                    s.close()
                } catch (e: IOException) {
                    // already gone, nothing to do
                }
            }
        }
    }

    private data class ClientSession(
        val socket: Socket,
        val deviceId: String,
        val name: String,
        val mode: NodeMode,
        val challenger: String?,
        val variants: Map<String, SyncProtocol.Header>,
        val weights: Map<String, FloatArray>,
        /** Ids this client offered via SAMPLE_IDS; null if it never sent one (sharing off, or a pre-v4 peer). */
        val offeredIds: Set<String>? = null,
    )

    /** Human-readable failure reason for the last-sync card; some IOExceptions carry a null message. */
    private fun describe(e: Exception): String =
        "${e::class.simpleName ?: "error"}: ${e.message ?: "connection closed by peer"}"

    private sealed interface AcceptOutcome {
        data class Hello(val session: ClientSession) : AcceptOutcome
        object Ping : AcceptOutcome
        object Failed : AcceptOutcome
    }

    /**
     * Reads the first frame off a freshly accepted socket (v13 plan §1). A PING is answered with
     * PONG (carrying this owner's deviceId) and the socket closed immediately, before the merge
     * window or client bookkeeping ever sees it. A HELLO is handed straight to
     * [readClientSession] with this already-read frame, so the session reader doesn't re-read it.
     * Anything else (or any IOException/timeout on this first read) is logged and dropped.
     */
    private fun acceptFrame(socket: Socket, ownerDeviceId: String): AcceptOutcome = try {
        socket.soTimeout = PING_TIMEOUT_MS
        val firstMsg = SyncProtocol.read(socket.getInputStream())
        when (firstMsg.type) {
            SyncProtocol.PING -> {
                SyncProtocol.write(
                    socket.getOutputStream(),
                    SyncProtocol.Message(SyncProtocol.PONG, SyncProtocol.Header(deviceId = ownerDeviceId), null),
                )
                runCatching { socket.close() }
                AcceptOutcome.Ping
            }
            SyncProtocol.HELLO -> {
                socket.soTimeout = 0 // back to blocking for the rest of the session, as before
                val session = readClientSession(socket, firstMsg)
                if (session != null) AcceptOutcome.Hello(session) else AcceptOutcome.Failed
            }
            else -> {
                Logx.w("fl sync: expected HELLO or PING, got type=${firstMsg.type}")
                AcceptOutcome.Failed
            }
        }
    } catch (e: IOException) {
        Logx.w("fl sync: failed to read from a client", e)
        AcceptOutcome.Failed
    }

    /** Reads one client's WEIGHTS... + optional SAMPLE_IDS + DONE, given its already-read HELLO
     * ([acceptFrame]'s first frame); null (and logs) on any protocol error. */
    private fun readClientSession(socket: Socket, hello: SyncProtocol.Message): ClientSession? = try {
        val deviceId = hello.header.deviceId
        if (deviceId == null) {
            Logx.w("fl sync: HELLO missing a deviceId")
            null
        } else {
            val name = hello.header.name ?: deviceId
            val mode = hello.header.mode ?: NodeMode.STABLE
            val challenger = hello.header.challenger

            val variants = mutableMapOf<String, SyncProtocol.Header>()
            val weights = mutableMapOf<String, FloatArray>()
            var offeredIds: Set<String>? = null
            readLoop@ while (true) {
                val msg = SyncProtocol.read(socket.getInputStream())
                when (msg.type) {
                    SyncProtocol.WEIGHTS -> {
                        val vId = msg.header.variantId
                        val w = msg.weights
                        val schema = msg.header.featureSchemaVersion
                        if (vId != null && w != null) {
                            if (schema == null || schema == peer.featureSchemaVersion()) {
                                variants[vId] = msg.header
                                weights[vId] = w
                            } else {
                                peer.addEvent(EventType.INFO, "schema mismatch from $deviceId")
                            }
                        }
                    }

                    SyncProtocol.SAMPLE_IDS -> offeredIds = msg.header.ids.orEmpty().toSet()

                    SyncProtocol.DONE -> break@readLoop

                    else -> Logx.w("fl sync: unexpected message from client (type=${msg.type})")
                }
            }
            ClientSession(socket, deviceId, name, mode, challenger, variants, weights, offeredIds)
        }
    } catch (e: IOException) {
        Logx.w("fl sync: failed to read from a client", e)
        null
    }

    companion object {
        /** How long the accept path waits for a client's first frame (v13 plan §1); a real
         * HELLO client sends it right after connecting, a PING probe even sooner. */
        private const val PING_TIMEOUT_MS = 2000
    }
}
