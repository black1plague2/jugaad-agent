package com.jugaad.agent.p2p

import com.jugaad.agent.core.config.ConfigStore
import com.jugaad.agent.fl.NetworkState
import com.jugaad.agent.fl.NodeMode
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Wire framing for FL peer sync over a plain TCP socket, protocol v2:
 * magic "JGFL" (4 ascii bytes), u8 version=2, u8 type, u32 jsonLen, JSON header (UTF-8),
 * u32 floatCount, floats as little-endian float32. The two u32 length fields use
 * DataOutputStream/DataInputStream's normal (big-endian) int encoding; only the float
 * payload is explicitly little-endian. Framing is byte-for-byte unchanged from v1; only
 * the version number, message types and header shape changed for multi-variant FedAvg
 * plus champion/challenger network state. v4 adds SAMPLE_IDS/SAMPLES (peer dataset
 * enrichment, see [FlSampleWire]) within this same v2 framing; an older peer that
 * never sends them still completes a plain weights-only session.
 */
object SyncProtocol {
    const val VERSION = 2

    const val HELLO = 1
    const val WEIGHTS = 2
    const val MERGED = 3
    const val NETWORK = 5
    const val DONE = 6
    const val SAMPLE_IDS = 7
    const val SAMPLES = 8

    private val MAGIC = "JGFL".toByteArray(Charsets.US_ASCII)
    private val json = Json { ignoreUnknownKeys = true }

    /** Owner/client TCP port, config-driven (v4 plan §2); [com.jugaad.agent.core.config.Sync.port]. */
    fun port(): Int = ConfigStore.effective.value.sync.port

    /**
     * One header shape shared by every message type; only the fields relevant to a given
     * [Message.type] are populated. HELLO carries the sender's node-card identity
     * (deviceId, name, mode, challenger). WEIGHTS/MERGED carry the per-variant fields
     * (variantId, round, nTrain, nVal, trainAcc, valAcc) alongside the float payload;
     * WEIGHTS additionally carries trainMs/usesUnlabelled for strategy ranking (v3).
     * NETWORK carries the full [network] state and nothing else. DONE carries only
     * (optionally) deviceId, for logging. trainMs/usesUnlabelled default to null so
     * headers from a v2 sender still parse.
     */
    @Serializable
    data class Header(
        val deviceId: String? = null,
        val name: String? = null,
        val mode: NodeMode? = null,
        val challenger: String? = null,
        val variantId: String? = null,
        val round: Int? = null,
        val nTrain: Int? = null,
        val nVal: Int? = null,
        val trainAcc: Float? = null,
        val valAcc: Float? = null,
        val trainMs: Long? = null,
        val usesUnlabelled: Boolean? = null,
        val network: NetworkState? = null,
        /** v4: this sender's [com.jugaad.agent.core.config.Features.schemaVersion]; on WEIGHTS/SAMPLES. */
        val featureSchemaVersion: Int? = null,
        /** v4: sample ids offered (client -> owner) or wanted (owner -> client), on SAMPLE_IDS. */
        val ids: List<String>? = null,
        /** v4: the sample payload, on SAMPLES. */
        val samples: List<FlSampleWire>? = null,
    )

    /**
     * Wire form of one peer-shared sample (v4 plan §5): the training-ready 260-d feature
     * vector only, no absolute log-mel/sensor stats. [origin] is never null on the wire —
     * see [com.jugaad.agent.fl.FlSample.origin].
     */
    @Serializable
    data class FlSampleWire(
        val id: String,
        val origin: String,
        val assetId: String,
        val machineTypeId: String?,
        val x: FloatArray,
        val label: Int,
        val score: Double?,
        val ts: Long,
    )

    data class Message(val type: Int, val header: Header, val weights: FloatArray?)

    fun write(out: OutputStream, m: Message) {
        val dos = DataOutputStream(out)
        dos.write(MAGIC)
        dos.writeByte(VERSION)
        dos.writeByte(m.type)

        val headerBytes = json.encodeToString(m.header).toByteArray(Charsets.UTF_8)
        dos.writeInt(headerBytes.size)
        dos.write(headerBytes)

        val weights = m.weights
        dos.writeInt(weights?.size ?: 0)
        if (weights != null && weights.isNotEmpty()) {
            val buf = ByteBuffer.allocate(weights.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            for (f in weights) buf.putFloat(f)
            dos.write(buf.array())
        }
        dos.flush()
    }

    fun read(input: InputStream): Message {
        val dis = DataInputStream(input)

        val magic = ByteArray(MAGIC.size)
        dis.readFully(magic)
        if (!magic.contentEquals(MAGIC)) {
            throw IOException("SyncProtocol: bad magic bytes ${magic.joinToString()}")
        }

        val version = dis.readUnsignedByte()
        if (version != VERSION) {
            throw IOException("SyncProtocol: unsupported version $version")
        }

        val type = dis.readUnsignedByte()

        val jsonLen = dis.readInt()
        if (jsonLen < 0) throw IOException("SyncProtocol: negative header length $jsonLen")
        val headerBytes = ByteArray(jsonLen)
        dis.readFully(headerBytes)
        val header = json.decodeFromString<Header>(String(headerBytes, Charsets.UTF_8))

        val floatCount = dis.readInt()
        if (floatCount < 0) throw IOException("SyncProtocol: negative float count $floatCount")
        val weights = if (floatCount > 0) {
            val raw = ByteArray(floatCount * 4)
            dis.readFully(raw)
            val buf = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
            FloatArray(floatCount) { buf.float }
        } else {
            null
        }

        return Message(type, header, weights)
    }
}
