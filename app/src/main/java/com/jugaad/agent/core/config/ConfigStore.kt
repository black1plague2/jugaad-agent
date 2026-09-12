package com.jugaad.agent.core.config

import android.content.Context
import com.jugaad.agent.core.Logx
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.longOrNull
import java.io.File

/**
 * Loads [AppConfig] from `assets/config/app_config.json`, merges a device-local
 * partial override (`filesDir/config/overrides.json`), and lets an owner's network
 * policy (flattened `"section.key" -> value` strings) win on top of that — see the
 * v4 plan, "App configuration". Layers, lowest to highest: asset defaults ->
 * overrides.json -> policy.
 */
object ConfigStore {

    private const val ASSET_PATH = "config/app_config.json"
    private const val OVERRIDES_RELATIVE_PATH = "config/overrides.json"
    private const val BUDGET_SECTION = "budget"

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val _effective = MutableStateFlow(AppConfig())
    val effective: StateFlow<AppConfig> = _effective

    /** Leaf paths ("section.key") currently set by the device-local overrides file. */
    @Volatile private var overrideKeys: Set<String> = emptySet()

    /** Leaf paths currently set by an applied network policy (wins over overrides). */
    @Volatile private var policyKeys: Set<String> = emptySet()

    fun load(context: Context): AppConfig {
        val defaultsText = context.assets.open(ASSET_PATH).use { it.readBytes().decodeToString() }
        val overridesFile = File(context.filesDir, OVERRIDES_RELATIVE_PATH)
        val overridesText = if (overridesFile.exists()) {
            runCatching { overridesFile.readText() }
                .onFailure { Logx.w("ConfigStore: reading overrides.json failed", it) }
                .getOrNull()
        } else null
        return loadFrom(defaultsText, overridesText)
    }

    fun resetOverrides(context: Context) {
        val overridesFile = File(context.filesDir, OVERRIDES_RELATIVE_PATH)
        if (overridesFile.exists() && !overridesFile.delete()) {
            Logx.w("ConfigStore: failed to delete overrides.json")
        }
        load(context)
    }

    /**
     * Pure entry point used by [load] and by tests (no [Context] needed): default asset
     * JSON text plus an optional partial-override JSON text, merged into the effective
     * config. Resets the policy layer — a fresh load re-acquires policy from the owner.
     */
    internal fun loadFrom(defaultsText: String, overridesText: String?): AppConfig {
        val cfg = merge(defaultsText, overridesText)
        overrideKeys = overridesText?.let { leafPaths(it) }.orEmpty()
        policyKeys = emptySet()
        _effective.value = cfg
        return cfg
    }

    /** Pure parse of the defaults JSON (or any full/partial-with-Kotlin-defaults JSON). */
    fun parse(text: String): AppConfig =
        runCatching { json.decodeFromString(AppConfig.serializer(), text) }
            .onFailure { Logx.w("ConfigStore: parse failed, using defaults", it) }
            .getOrDefault(AppConfig())

    /** Pure merge: defaults JSON overlaid by an optional partial-JSON of the same shape. */
    fun merge(defaultsJson: String, overridesJson: String?): AppConfig {
        val base = parse(defaultsJson)
        if (overridesJson == null) return base
        val merged = runCatching {
            val baseElement = json.encodeToJsonElement(AppConfig.serializer(), base) as JsonObject
            val overlay = json.parseToJsonElement(overridesJson) as JsonObject
            deepMerge(baseElement, overlay)
        }.onFailure { Logx.w("ConfigStore: overrides.json is not valid JSON", it) }.getOrNull() ?: return base
        return runCatching { json.decodeFromJsonElement(AppConfig.serializer(), merged) }
            .onFailure { Logx.w("ConfigStore: overrides.json merge failed", it) }
            .getOrDefault(base)
    }

    /** Flattens every non-`budget` section of [cfg] to `"section.key" -> value` strings. */
    fun policyOf(cfg: AppConfig): Map<String, String> {
        val root = json.encodeToJsonElement(AppConfig.serializer(), cfg) as JsonObject
        val out = LinkedHashMap<String, String>()
        for ((section, value) in root) {
            if (section == BUDGET_SECTION) continue
            flattenToStrings(section, value, out)
        }
        return out
    }

    /**
     * Parses a flattened policy map back into the effective config. Unknown keys and
     * type-mismatched values are ignored (logged) one at a time; `budget.*` is always
     * refused since it is device-local. Returns true iff the effective config changed.
     */
    fun applyPolicy(policy: Map<String, String>): Boolean {
        var current = _effective.value
        var changed = false
        val appliedKeys = LinkedHashSet<String>()
        for ((key, value) in policy) {
            if (key == BUDGET_SECTION || key.startsWith("$BUDGET_SECTION.")) {
                Logx.w("ConfigStore.applyPolicy: refusing device-local key $key")
                continue
            }
            val next = applyOne(current, key, value)
            if (next == null) {
                Logx.w("ConfigStore.applyPolicy: unknown key or wrong type for $key=$value")
                continue
            }
            appliedKeys += key
            if (next != current) {
                current = next
                changed = true
            }
        }
        if (appliedKeys.isNotEmpty()) policyKeys = policyKeys + appliedKeys
        if (changed) _effective.value = current
        return changed
    }

    fun source(key: String): ConfigSource = when {
        key in policyKeys -> ConfigSource.POLICY
        key in overrideKeys -> ConfigSource.OVERRIDE
        else -> ConfigSource.DEFAULT
    }

    // ---------------------------------------------------------------- internals

    private fun deepMerge(base: JsonObject, overlay: JsonObject): JsonObject {
        val result = LinkedHashMap<String, JsonElement>(base)
        for ((k, v) in overlay) {
            val baseVal = result[k]
            result[k] = if (baseVal is JsonObject && v is JsonObject) deepMerge(baseVal, v) else v
        }
        return JsonObject(result)
    }

    private fun flattenToStrings(prefix: String, element: JsonElement, out: MutableMap<String, String>) {
        when (element) {
            is JsonObject -> for ((k, v) in element) flattenToStrings("$prefix.$k", v, out)
            is JsonArray -> out[prefix] = element.joinToString(",") { (it as JsonPrimitive).content }
            is JsonPrimitive -> out[prefix] = element.content
        }
    }

    /** Leaf paths ("section.key", one entry per array too) present in a raw JSON object. */
    private fun leafPaths(rawJson: String): Set<String> {
        val root = runCatching { json.parseToJsonElement(rawJson) }.getOrNull() as? JsonObject
            ?: return emptySet()
        val out = LinkedHashSet<String>()
        fun walk(prefix: String, element: JsonElement) {
            when (element) {
                is JsonObject -> for ((k, v) in element) walk(if (prefix.isEmpty()) k else "$prefix.$k", v)
                else -> out.add(prefix)
            }
        }
        walk("", root)
        return out
    }

    private fun applyOne(cfg: AppConfig, path: String, rawValue: String): AppConfig? {
        val root = json.encodeToJsonElement(AppConfig.serializer(), cfg) as JsonObject
        val newRoot = setPath(root, path.split("."), rawValue) ?: return null
        return runCatching { json.decodeFromJsonElement(AppConfig.serializer(), newRoot) }.getOrNull()
    }

    private fun setPath(obj: JsonObject, parts: List<String>, rawValue: String): JsonObject? {
        val key = parts.first()
        val existing = obj[key] ?: return null
        val replacement: JsonElement = if (parts.size == 1) {
            buildReplacement(existing, rawValue) ?: return null
        } else {
            if (existing !is JsonObject) return null
            setPath(existing, parts.drop(1), rawValue) ?: return null
        }
        val updated = LinkedHashMap(obj)
        updated[key] = replacement
        return JsonObject(updated)
    }

    private fun buildReplacement(original: JsonElement, rawValue: String): JsonElement? = when (original) {
        is JsonArray -> {
            val sample = original.firstOrNull() as? JsonPrimitive
            val items = rawValue.split(",").map { primitiveLike(sample, it) }
            if (items.any { it == null }) null else JsonArray(items.map { it!! })
        }
        is JsonPrimitive -> primitiveLike(original, rawValue)
        else -> null
    }

    private fun primitiveLike(sample: JsonPrimitive?, rawToken: String): JsonPrimitive? {
        val token = rawToken.trim()
        if (sample?.booleanOrNull != null) {
            return token.toBooleanStrictOrNull()?.let { JsonPrimitive(it) }
        }
        val isIntegral = sample == null || sample.longOrNull != null
        if (isIntegral) {
            token.toLongOrNull()?.let { return JsonPrimitive(it) }
        }
        return token.toDoubleOrNull()?.let { JsonPrimitive(it) }
    }
}
