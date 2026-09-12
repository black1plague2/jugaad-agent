package com.jugaad.agent.fl

import android.os.Build
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.random.Random

enum class NodeMode { EXPERIMENTAL, STABLE }

/** This node's last-known role in the WiFi Direct group (v4 plan §4, self-healing nodes). */
enum class NodeRole { NONE, OWNER, CLIENT }

/** This phone's identity and local training/sync preferences. Persisted at `fl/node.json`. */
@Serializable
data class NodeConfig(
    val deviceId: String,
    val name: String,
    val mode: NodeMode,
    val pinnedChallenger: String?,
    val autoTrain: Boolean,
    val autoSync: Boolean,
    /** Restored on startup so an owner that crashed/rebooted resumes serving (v4 §4). */
    val lastRole: NodeRole = NodeRole.NONE,
    val lastOwnerAddress: String? = null,
    /** Reset to 0 on a successful client sync; drives [com.jugaad.agent.p2p.Failover]. */
    val consecutiveSyncFailures: Int = 0,
    /** Whether this node contributes its own labelled samples to peers (v4 §5). */
    val shareSamples: Boolean = true,
)

/** Loads/saves [NodeConfig], migrating `deviceId` from v1's `fl/state.json` on first run. */
object NodeConfigIO {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    fun load(dir: File): NodeConfig {
        val file = File(dir, "node.json")
        file.takeIf { it.exists() }
            ?.let { f -> runCatching { json.decodeFromString<NodeConfig>(f.readText()) }.getOrNull() }
            ?.let { return it }

        val deviceId = migrateDeviceId(dir) ?: randomDeviceId()
        val fresh = NodeConfig(
            deviceId = deviceId,
            name = "${Build.MODEL}-${deviceId.take(4)}",
            mode = NodeMode.EXPERIMENTAL,
            pinnedChallenger = null,
            autoTrain = true,
            autoSync = false,
        )
        save(dir, fresh)
        return fresh
    }

    fun save(dir: File, config: NodeConfig) {
        dir.mkdirs()
        runCatching { File(dir, "node.json").writeText(json.encodeToString(config)) }
    }

    /** v1's `fl/state.json` (see the pre-champion/challenger contract) had a top-level `deviceId`. */
    private fun migrateDeviceId(dir: File): String? {
        val legacy = File(dir, "state.json")
        if (!legacy.exists()) return null
        return runCatching {
            json.decodeFromString<JsonElement>(legacy.readText()).jsonObject["deviceId"]?.jsonPrimitive?.content
        }.getOrNull()
    }

    private fun randomDeviceId(): String {
        val chars = "0123456789abcdef"
        return (1..8).map { chars[Random.nextInt(chars.length)] }.joinToString("")
    }
}
