package com.jugaad.agent.core.config

import android.content.Context
import com.jugaad.agent.core.Logx
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class Condition(
    val metric: String,
    val op: String,
    val value: Double,
    val weight: Double = 1.0,
)

@Serializable
data class FaultRule(
    val id: String,
    val label: String,
    val keywords: List<String> = emptyList(),
    val evidence: List<Condition> = emptyList(),
    val action: String,
    val severity: String,
)

@Serializable
data class MachineType(
    val id: String,
    val label: String,
    val keywords: List<String> = emptyList(),
    val expectedRotationHz: List<Double> = emptyList(),
    val notes: String = "",
    val faults: List<FaultRule> = emptyList(),
)

@Serializable
private data class MachineCatalogFile(
    val schemaVersion: Int = 1,
    val types: List<MachineType> = emptyList(),
)

/**
 * The machine-type + fault-rule catalogue, loaded from `assets/config/machines.json`.
 * Evidence conditions may only reference the fixed metric vocabulary that
 * `EvidenceExtractor` produces; anything else is dropped at load time (with a log)
 * rather than silently mis-scored. See the v4 plan, "Machine catalogue".
 */
object MachineCatalog {

    const val GENERIC_ID = "generic"

    private const val ASSET_PATH = "config/machines.json"

    /** Fixed vocabulary produced by `EvidenceExtractor`. Rules may use only these metric names. */
    val VALID_METRICS = setOf(
        "lowBandDelta", "midBandDelta", "highBandDelta", "veryHighBandDelta", "broadbandDelta",
        "spectralSpread", "peakiness", "dominantHz", "lineHumDelta", "temporalVariability",
        "imuDelta", "gyroDelta", "magDelta", "magRmsDelta", "anomalyScore", "cnnClass", "cnnConfidence",
    )
    val VALID_OPS = setOf(">", ">=", "<", "<=", "==")

    private val json = Json { ignoreUnknownKeys = true }

    private val fallbackGeneric = MachineType(
        id = GENERIC_ID,
        label = "Other rotating machine",
        keywords = emptyList(),
        expectedRotationHz = emptyList(),
        notes = "",
        faults = emptyList(),
    )

    @Volatile private var loaded: List<MachineType> = emptyList()

    fun load(context: Context): List<MachineType> {
        val text = context.assets.open(ASSET_PATH).use { it.readBytes().decodeToString() }
        loaded = parse(text)
        return loaded
    }

    /** Pure parse + validation, no [Context] needed — used by [load] and by tests. */
    fun parse(text: String): List<MachineType> {
        val file = runCatching { json.decodeFromString(MachineCatalogFile.serializer(), text) }
            .onFailure { Logx.w("MachineCatalog: parse failed", it) }
            .getOrDefault(MachineCatalogFile())
        return file.types.map(::validateType)
    }

    /** Test-only hook: JVM tests have no [Context], so they parse the asset file straight
     *  off disk and prime this catalogue through here before calling [byId]/[search]. */
    internal fun primeForTest(types: List<MachineType>) {
        loaded = types
    }

    fun byId(id: String): MachineType =
        loaded.firstOrNull { it.id == id }
            ?: loaded.firstOrNull { it.id == GENERIC_ID }
            ?: fallbackGeneric

    /** Case-insensitive match on id/label/keywords. Blank query -> generic first, then the rest. */
    fun search(query: String): List<MachineType> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) {
            val generic = loaded.filter { it.id == GENERIC_ID }
            val rest = loaded.filterNot { it.id == GENERIC_ID }
            return generic + rest
        }
        return loaded.filter { type ->
            type.id.lowercase().contains(q) ||
                type.label.lowercase().contains(q) ||
                type.keywords.any { it.lowercase().contains(q) }
        }
    }

    private fun validateType(type: MachineType): MachineType {
        val validFaults = type.faults.mapNotNull { fault ->
            val validEvidence = fault.evidence.filter { cond ->
                val ok = cond.metric in VALID_METRICS && cond.op in VALID_OPS
                if (!ok) {
                    Logx.w("MachineCatalog: dropping invalid rule ${type.id}/${fault.id}: metric=${cond.metric} op=${cond.op}")
                }
                ok
            }
            if (fault.evidence.isNotEmpty() && validEvidence.isEmpty()) {
                Logx.w("MachineCatalog: dropping fault ${type.id}/${fault.id}, no valid evidence left")
                null
            } else {
                fault.copy(evidence = validEvidence)
            }
        }
        return type.copy(faults = validFaults)
    }
}
