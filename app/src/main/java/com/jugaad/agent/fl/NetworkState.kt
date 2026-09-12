package com.jugaad.agent.fl

import com.jugaad.agent.core.config.ConfigStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/** One variant's network-wide leaderboard entry. */
@Serializable
data class VariantStanding(
    val variantId: String,
    val netAcc: Float,
    val nVal: Int,
    val nodes: Int,
    val history: List<Float>,
    val wins: Int,
    val round: Int,
    /** nVal-weighted mean of reported training time in ms (Decision 7, v3). */
    val trainMs: Float = 0f,
    /** [StrategyRank.score]; the champion's own standing is always 0. */
    val score: Float = 0f,
    val usesUnlabelled: Boolean = false,
)

enum class EventType { JOIN, SYNC, PROMOTE, TRAIN, ASSIGN, INFO, RECOVER, FAILOVER, SHARE }

@Serializable
data class NetworkEvent(val ts: Long, val type: EventType, val text: String)

/** The full federated network snapshot, replicated to every node's `fl/network.json`. */
@Serializable
data class NetworkState(
    val championId: String,
    val nodes: List<NodeCard>,
    val standings: Map<String, VariantStanding>,
    val assignments: Map<String, String>,
    val events: List<NetworkEvent>,
    val updatedMs: Long,
    /** Owner's non-device-local config sections, flattened "section.key" -> value (v4 §2). */
    val policy: Map<String, String> = emptyMap(),
    /** [com.jugaad.agent.core.config.Features.schemaVersion] this node trained its contributions with. */
    val featureSchemaVersion: Int = 3,
) {
    companion object {
        fun empty(championId: String = FlVariants.CHAMPION_DEFAULT): NetworkState =
            NetworkState(
                championId = championId,
                nodes = emptyList(),
                standings = emptyMap(),
                assignments = emptyMap(),
                events = emptyList(),
                updatedMs = 0L,
                policy = emptyMap(),
                featureSchemaVersion = ConfigStore.effective.value.features.schemaVersion,
            )
    }
}

/** Loads/saves [NetworkState] at `fl/network.json`. */
object NetworkStateIO {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    fun load(dir: File, championId: String = FlVariants.CHAMPION_DEFAULT): NetworkState {
        val file = File(dir, "network.json")
        return file.takeIf { it.exists() }
            ?.let { f -> runCatching { json.decodeFromString<NetworkState>(f.readText()) }.getOrNull() }
            ?: NetworkState.empty(championId)
    }

    fun save(dir: File, state: NetworkState) {
        dir.mkdirs()
        runCatching { File(dir, "network.json").writeText(json.encodeToString(state)) }
    }
}
