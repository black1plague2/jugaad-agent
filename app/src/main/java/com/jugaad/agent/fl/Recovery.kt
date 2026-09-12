package com.jugaad.agent.fl

import com.jugaad.agent.core.Constants
import com.jugaad.agent.core.Logx
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Startup self-healing (v4 plan §4, "self-healing nodes"): every JSON file directly
 * under `fl/` and one level under `assets/<id>/` is checked for well-formedness. A
 * file that fails to parse is renamed to `<name>.corrupt-<ts>` — deliberately *not*
 * reconstructed here, since every loader in this codebase (NodeConfigIO, NetworkStateIO,
 * per-asset stores, ...) already falls back to its own typed default the moment the
 * file it looks for is missing, so quarantining the bad file is enough to let the
 * normal load-or-default path recreate it on next access. Weight files
 * (`weights_<id>.bin`) whose byte length doesn't match any [FlVariants] variant's
 * weight count, or that contain a non-finite float (NaN/Infinity, e.g. from a FedAvg
 * round merged before the totalN <= 0 guard existed), are renamed to `<name>.stale` so
 * a truncated/mismatched/poisoned export can't be loaded into the graph.
 */
object Recovery {
    data class Report(val repairedFiles: List<String>, val staleWeights: List<String>)

    private val json = Json { ignoreUnknownKeys = true }

    fun repair(filesDir: File): Report {
        val ts = System.currentTimeMillis()
        val repaired = mutableListOf<String>()
        val stale = mutableListOf<String>()

        val flDir = File(filesDir, "fl")
        if (flDir.isDirectory) {
            flDir.listFiles()?.forEach { f ->
                when {
                    !f.isFile -> Unit
                    f.name.endsWith(".json") -> repairJsonFile(f, ts)?.let { repaired += it }
                    f.name.startsWith("weights_") && f.name.endsWith(".bin") -> {
                        staleWeightFile(f)?.let { stale += it }
                    }
                }
            }
        }

        val assetsDir = File(filesDir, Constants.ASSETS_DIR)
        if (assetsDir.isDirectory) {
            assetsDir.listFiles { f -> f.isDirectory }?.forEach { assetDir ->
                assetDir.listFiles { f -> f.isFile && f.name.endsWith(".json") }?.forEach { f ->
                    repairJsonFile(f, ts)?.let { repaired += it }
                }
            }
        }

        return Report(repaired, stale)
    }

    /** @return the original file name if it was quarantined, null if it was fine (or already gone). */
    private fun repairJsonFile(f: File, ts: Long): String? {
        val text = runCatching { f.readText() }.getOrNull() ?: return quarantine(f, ts, "corrupt")
        // json.parseToJsonElement is lenient about bare unquoted tokens (e.g. "garbage2" parses
        // as a JsonPrimitive string even though it's not valid JSON), so a well-formedness check
        // on the parse result alone would miss plain-text corruption. Every file this repairs
        // (NodeConfig, NetworkState, per-asset stores) serializes to a JSON object, so require that.
        val element = runCatching { json.parseToJsonElement(text) }.getOrNull()
        if (element is JsonObject) return null
        val quarantined = quarantine(f, ts, "corrupt") ?: return null
        Logx.i("Recovery: repaired corrupt file $quarantined")
        return quarantined
    }

    private fun staleWeightFile(f: File): String? {
        val len = f.length()
        val floats = if (len > 0 && len % 4L == 0L) (len / 4L).toInt() else -1
        val matchesKnownVariant = floats >= 0 && FlVariants.ALL.any { it.weightCount == floats }
        if (!matchesKnownVariant) return quarantine(f, null, "stale")
        // Right length alone isn't enough: an all-NaN merge from before the FedAvg totalN <= 0
        // guard produces a file of the correct length that would otherwise pass as healthy.
        if (hasNonFiniteFloat(f, floats)) return quarantine(f, null, "stale")
        return null
    }

    /** Reads [floats] little-endian floats from [f] (matching [WeightsCodec]'s encoding),
     * stopping at the first non-finite value. */
    private fun hasNonFiniteFloat(f: File, floats: Int): Boolean {
        val bytes = runCatching { f.readBytes() }.getOrNull() ?: return false
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        repeat(floats) {
            if (!buf.getFloat().isFinite()) return true
        }
        return false
    }

    /** Renames [f] aside as `<name>.corrupt-<ts>` (or `<name>.stale` when [ts] is null). */
    private fun quarantine(f: File, ts: Long?, reason: String): String? {
        val suffix = if (ts != null) "$reason-$ts" else reason
        val target = File(f.parentFile, "${f.name}.$suffix")
        return if (f.renameTo(target)) {
            Logx.w("Recovery: $reason file ${f.name} -> ${target.name}")
            f.name
        } else {
            Logx.w("Recovery: failed to quarantine $reason file ${f.name}")
            null
        }
    }
}
