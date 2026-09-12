package com.jugaad.agent.ui.network

import android.os.PowerManager
import androidx.compose.runtime.Composable
import com.jugaad.agent.core.config.AppConfig
import com.jugaad.agent.core.config.ConfigSource
import com.jugaad.agent.core.config.ConfigStore
import com.jugaad.agent.domain.model.FaultClass
import com.jugaad.agent.fl.NodeMode
import com.jugaad.agent.fl.VariantStanding
import com.jugaad.agent.ui.common.fiori.FioriBanner
import com.jugaad.agent.ui.common.fiori.Semantic

/** A node is considered stale (and excluded from "online" counts) after this long unseen. */
internal const val STALE_MS = 30L * 60L * 1000L

/** A variant's network standing needs at least this many held-out samples network-wide to rank. */
internal const val MIN_ELIGIBLE_NVAL = 8

internal fun pct1(frac: Float): String = "%.1f".format(frac.coerceIn(0f, 1f) * 100f)

internal fun deltaPts(v: Float): String = "%+.1f pts".format(v)

internal fun valAccText(acc: Float): String = if (acc < 0f) "needs 4+ validation samples" else "${pct1(acc)}%"

/** Before/after held-out accuracy for a sync summary row. Falls back to a single message
 * instead of concatenating [valAccText] on both sides when neither had enough samples, which
 * would otherwise print "needs 4+ validation samples to needs 4+ validation samples". */
internal fun valAccTransition(before: Float, after: Float): String =
    if (before < 0f && after < 0f) "needs 4+ held-out samples" else "${valAccText(before)} to ${valAccText(after)}"

internal fun modeLabel(mode: NodeMode): String = mode.name.lowercase().replaceFirstChar { it.uppercase() }

/** Devices/Network tabs' "owner unreachable" banner text (v4 plan §4: failed client syncs). */
internal fun ownerUnreachableMessage(consecutiveSyncFailures: Int): String =
    "Owner unreachable ($consecutiveSyncFailures failed syncs): Discover and Connect to another node, or promote this node in Group settings"

/** Shown on both the Devices and Network tabs once client syncs start failing, so a stuck
 * node's owner can be re-discovered or this node promoted without digging into logs. */
@Composable
internal fun OwnerUnreachableBanner(ui: NetworkUiState) {
    val failures = ui.config?.consecutiveSyncFailures ?: 0
    if (failures < 1 || ui.group.isGroupOwner) return
    FioriBanner(ownerUnreachableMessage(failures), semantic = Semantic.CRITICAL)
}

/** Devices/Network tabs' role+sync status chip: a failed last sync always surfaces first (an
 * "active device" count means nothing if the last sync to it errored out), then ownership and
 * whether anything has actually synced yet, so "formed" alone never reads as fully healthy. */
internal fun syncStatus(ui: NetworkUiState): Pair<String, Semantic> {
    val failed = ui.lastSync?.message?.contains("failed", ignoreCase = true) == true
    return when {
        failed -> "Sync failed" to Semantic.CRITICAL
        ui.group.isGroupOwner && ui.serving -> "Serving" to Semantic.POSITIVE
        ui.group.isGroupOwner -> "Group formed, not serving" to Semantic.CRITICAL
        ui.group.formed && ui.lastSync != null -> "Synchronized" to Semantic.POSITIVE
        ui.group.formed -> "Connected" to Semantic.INFORMATIVE
        else -> "Not connected" to Semantic.NEUTRAL
    }
}

internal fun peerStatusLabel(status: Int): String = when (status) {
    0 -> "Connected"
    1 -> "Invited"
    2 -> "Failed"
    3 -> "Available"
    4 -> "Unavailable"
    else -> "Unknown"
}

internal fun thermalLabel(status: Int): String = when (status) {
    PowerManager.THERMAL_STATUS_NONE -> "NONE"
    PowerManager.THERMAL_STATUS_LIGHT -> "LIGHT"
    PowerManager.THERMAL_STATUS_MODERATE -> "MODERATE"
    PowerManager.THERMAL_STATUS_SEVERE -> "SEVERE"
    PowerManager.THERMAL_STATUS_CRITICAL -> "CRITICAL"
    PowerManager.THERMAL_STATUS_EMERGENCY -> "EMERGENCY"
    PowerManager.THERMAL_STATUS_SHUTDOWN -> "SHUTDOWN"
    else -> "UNKNOWN"
}

internal fun relativeTime(ms: Long): String {
    if (ms <= 0L) return "never"
    val minutes = (System.currentTimeMillis() - ms) / 60_000
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "$minutes min ago"
        minutes < 24 * 60 -> "${minutes / 60}h ago"
        else -> "${minutes / (24 * 60)}d ago"
    }
}

/** Champion / Candidate n/2 / Trailing / No data, shared by the ranking and node-by-variant views. */
internal fun variantStatus(standing: VariantStanding?, isChampion: Boolean, eligible: Boolean): Pair<String, Semantic> = when {
    isChampion -> "Champion" to Semantic.POSITIVE
    !eligible -> "No data" to Semantic.NEUTRAL
    standing != null && standing.wins > 0 -> "Candidate ${standing.wins}/2" to Semantic.INFORMATIVE
    else -> "Trailing" to Semantic.NEUTRAL
}

/** Healthy -> Positive, Rotor imbalance -> Critical, Airflow obstruction -> Negative (task mapping). */
internal fun classSemantic(fc: FaultClass): Semantic = when (fc) {
    FaultClass.HEALTHY -> Semantic.POSITIVE
    FaultClass.ROTOR_IMBALANCE -> Semantic.CRITICAL
    FaultClass.AIRFLOW_OBSTRUCTION -> Semantic.NEGATIVE
}

/** Parses the "<name> took over" suffix out of a `FAILOVER "owner unreachable, <name> took over"` event. */
internal fun extractNewOwnerName(text: String): String? {
    val marker = " took over"
    if (!text.endsWith(marker)) return null
    val withoutSuffix = text.removeSuffix(marker)
    val idx = withoutSuffix.lastIndexOf(", ")
    return if (idx >= 0) withoutSuffix.substring(idx + 2).takeIf { it.isNotBlank() } else null
}

internal data class ConfigRow(val key: String, val fullKey: String, val value: String, val source: ConfigSource)

/** Flattens [ConfigStore.policyOf] (every non-device-local key) plus the device-local `budget`
 * section, grouped by the section name before the first dot, for the Configuration panel. */
internal fun groupedConfigRows(cfg: AppConfig): Map<String, List<ConfigRow>> {
    val flat = ConfigStore.policyOf(cfg).toMutableMap()
    flat["budget.maxThreads"] = "${cfg.budget.maxThreads}"
    flat["budget.thermalMax"] = "${cfg.budget.thermalMax}"
    flat["budget.minBatteryPct"] = "${cfg.budget.minBatteryPct}"
    return flat.toSortedMap().entries
        .map { (full, value) -> ConfigRow(full.substringAfter('.'), full, value, ConfigStore.source(full)) }
        .groupBy { it.fullKey.substringBefore('.') }
}
