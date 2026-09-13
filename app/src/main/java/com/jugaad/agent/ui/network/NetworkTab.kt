package com.jugaad.agent.ui.network

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jugaad.agent.fl.Cohorts
import com.jugaad.agent.fl.FlVariants
import com.jugaad.agent.fl.NodeMode
import com.jugaad.agent.ui.common.SpectrogramImage
import com.jugaad.agent.ui.common.fiori.FioriColors
import com.jugaad.agent.ui.common.fiori.FioriEmptyState
import com.jugaad.agent.ui.common.fiori.FioriKeyValueRow
import com.jugaad.agent.ui.common.fiori.MatrixHeatmap
import com.jugaad.agent.ui.common.fiori.MiniStatCard
import com.jugaad.agent.ui.common.fiori.PrimaryButton
import com.jugaad.agent.ui.common.fiori.GhostButton
import com.jugaad.agent.ui.common.fiori.SectionTitle
import com.jugaad.agent.ui.common.fiori.Semantic
import com.jugaad.agent.ui.common.fiori.StatusChip
import com.jugaad.agent.ui.common.fiori.semantic

/**
 * Network tab (Stitch "Federated Network Topology"): mesh status, node-by-variant accuracy
 * heatmap and the connected-devices list. Carries forward the old NetworkScreen's KpiSection
 * (nodes online/round), CohortsSection and DeviceSection, which the four-tab contract does not
 * give an explicit new home to.
 */
@Composable
fun NetworkTab(
    ui: NetworkUiState,
    vm: NetworkViewModel,
    wide: Boolean,
    onOpenGroupSettings: () -> Unit,
    runWithWifiPermission: (() -> Unit) -> Unit,
) {
    val latestReading by vm.latestReading.collectAsStateWithLifecycle()
    val left: @Composable () -> Unit = {
        OwnerUnreachableBanner(ui)
        MeshStatusCard(ui, vm, onOpenGroupSettings, runWithWifiPermission)
        CohortsSection(ui)
    }
    val right: @Composable () -> Unit = {
        LatestReadingCard(latestReading)
        ConnectedDevicesSection(ui)
        NodeByVariantCard(ui)
        DeviceSection(ui)
    }
    if (wide) {
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(
                Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(28.dp),
            ) { left() }
            Column(
                Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(28.dp),
            ) { right() }
        }
    } else {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            left()
            right()
        }
    }
}

@Composable
private fun MeshStatusCard(
    ui: NetworkUiState,
    vm: NetworkViewModel,
    onOpenGroupSettings: () -> Unit,
    runWithWifiPermission: (() -> Unit) -> Unit,
) {
    val (roleText, roleSemantic) = meshRole(ui)
    val (statusText, statusSemantic) = syncStatus(ui)
    val online = ui.displayNodes.count { System.currentTimeMillis() - it.lastSeenMs < STALE_MS }
    val standings = ui.network?.standings ?: emptyMap()
    val champStanding = standings[ui.championId]
    val round = champStanding?.round ?: ui.heldMetrics[ui.championId]?.round ?: 0

    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(FioriColors.SurfaceElevated).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Local mesh group", color = FioriColors.TextPrimary, style = MaterialTheme.typography.titleLarge)
            StatusChip(text = statusText, semantic = statusSemantic)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MiniStatCard(label = "Owner address", value = meshOwnerAddress(ui), modifier = Modifier.weight(1f))
            MiniStatCard(label = "Role", value = roleText, modifier = Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MiniStatCard(label = "Nodes online", value = "$online", modifier = Modifier.weight(1f))
            MiniStatCard(label = "Round", value = "$round", modifier = Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Auto-sync", color = FioriColors.TextPrimary, style = MaterialTheme.typography.bodyLarge)
            Switch(checked = ui.schedulerEnabled, onCheckedChange = { vm.toggleScheduler(it) })
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PrimaryButton(
                text = "Discover peers",
                onClick = { runWithWifiPermission { vm.discover() } },
                icon = Icons.Filled.Search,
                modifier = Modifier.weight(1.3f),
            )
            GhostButton(text = "Group settings", onClick = onOpenGroupSettings, modifier = Modifier.weight(1f))
        }
    }
}

/** Old CohortsSection: mean champion held-out accuracy for experimental vs stable nodes. */
@Composable
private fun CohortsSection(ui: NetworkUiState) {
    SectionTitle("Cohorts")
    val network = ui.network
    if (network == null) {
        FioriEmptyState("No cohort data yet", "Nodes appear here once the network state has synced.")
        return
    }
    val cohorts = Cohorts.compute(network)
    val experimental = cohorts[NodeMode.EXPERIMENTAL]
    val stable = cohorts[NodeMode.STABLE]
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        MiniStatCard(
            label = "Experimental nodes",
            value = experimental?.let { pct1(it.meanChampValAcc) + "%" } ?: "--",
            sub = experimental?.let { "${it.nodes} nodes, round ${"%.1f".format(it.meanRound)}" },
            modifier = Modifier.weight(1f),
        )
        MiniStatCard(
            label = "Stable nodes",
            value = stable?.let { pct1(it.meanChampValAcc) + "%" } ?: "--",
            sub = stable?.let { "${it.nodes} nodes, round ${"%.1f".format(it.meanRound)}" },
            modifier = Modifier.weight(1f),
        )
    }
}

/** Newest [com.jugaad.agent.domain.model.Diagnosis] across every asset, shown with the same
 * spectrogram treatment as the Result screen rather than a placeholder heatmap. */
@Composable
private fun LatestReadingCard(reading: LatestReading?) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(FioriColors.Surface).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("Latest reading", color = FioriColors.TextPrimary, style = MaterialTheme.typography.titleLarge)
                if (reading != null) {
                    Text(
                        "${reading.assetName}, ${relativeTime(reading.timestampMs)}",
                        color = FioriColors.TextSecondary,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            if (reading != null) {
                StatusChip(text = reading.status.label, semantic = reading.status.semantic())
            }
        }
        if (reading == null) {
            FioriEmptyState("No readings yet", "Take a reading on any equipment to see its heatmap here")
        } else {
            SpectrogramImage(bitmap = reading.png, modifier = Modifier.fillMaxWidth())
        }
    }
}

/** Per node/variant held-out accuracy: only the champion column (champValAcc) and each node's
 * own challenger column (challValAcc) are ever populated on [com.jugaad.agent.fl.NodeCard] -
 * every other cell is null rather than invented. */
@Composable
private fun NodeByVariantCard(ui: NetworkUiState) {
    SectionTitle("Node by variant")
    if (ui.displayNodes.isEmpty()) {
        FioriEmptyState("No nodes yet", "Nodes appear here once the network state has synced.")
        return
    }
    val orderedSpecs = listOf(FlVariants.byId(ui.championId)) + FlVariants.ALL.filter { it.id != ui.championId }
    val cols = orderedSpecs.map { it.label }
    val values = ui.displayNodes.map { node ->
        orderedSpecs.map { spec ->
            when (spec.id) {
                ui.championId -> node.champValAcc.takeIf { it >= 0f }
                node.challenger -> node.challValAcc.takeIf { it >= 0f }
                else -> null
            }
        }
    }
    MatrixHeatmap(rows = ui.displayNodes.map { it.name }, cols = cols, values = values, modifier = Modifier.fillMaxWidth())
}

/** Old NodesSection, re-shaped into raised cards with two [MiniStatCard]s per the contract. */
@Composable
private fun ConnectedDevicesSection(ui: NetworkUiState) {
    SectionTitle("Connected devices", trailing = "${ui.displayNodes.size} node" + if (ui.displayNodes.size == 1) "" else "s")
    if (ui.displayNodes.size <= 1) {
        FioriEmptyState("This device is the only node", "Discover and connect another phone from the Devices tab to grow the network.")
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ui.displayNodes.forEach { node ->
            val stale = System.currentTimeMillis() - node.lastSeenMs > STALE_MS
            val (statusText, statusSemantic) = when {
                node.isOwner -> "Owner" to Semantic.INFORMATIVE
                stale -> "Stale" to Semantic.CRITICAL
                else -> "Client" to Semantic.NEUTRAL
            }
            val challengerLabel = node.challenger?.let { id -> runCatching { FlVariants.byId(id).label }.getOrDefault(id) }
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(FioriColors.Surface).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        Text(node.name, color = FioriColors.TextPrimary, style = MaterialTheme.typography.titleLarge)
                        Text(
                            "${modeLabel(node.mode)}, ${challengerLabel ?: "no challenger"}",
                            color = FioriColors.TextSecondary,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    StatusChip(text = statusText, semantic = statusSemantic)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    MiniStatCard(label = "Held-out accuracy", value = valAccText(node.champValAcc), modifier = Modifier.weight(1f))
                    MiniStatCard(label = "Round", value = "${node.champRound}", modifier = Modifier.weight(1f))
                }
                Text("Synced ${relativeTime(node.lastSeenMs)}", color = FioriColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** Old DeviceSection: [com.jugaad.agent.fl.TrainBudget] resource snapshot for this phone. */
@Composable
private fun DeviceSection(ui: NetworkUiState) {
    SectionTitle("Device")
    val snap = ui.deviceSnapshot
    if (snap == null) {
        FioriEmptyState("Reading device status", "The device resource snapshot has not loaded yet.")
        return
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        MiniStatCard(label = "Cores", value = "${snap.cores}", sub = "${snap.threads} training threads", modifier = Modifier.weight(1f))
        MiniStatCard(label = "Thermal status", value = thermalLabel(snap.thermal), modifier = Modifier.weight(1f))
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        MiniStatCard(
            label = "Battery",
            value = "${snap.batteryPct}%",
            sub = if (snap.charging) "Charging" else null,
            modifier = Modifier.weight(1f),
        )
        MiniStatCard(label = "Memory", value = "${snap.availMemMb} MB free", sub = "of ${snap.totalMemMb} MB", modifier = Modifier.weight(1f))
    }
    StatusChip(
        text = "Auto-train ${if (snap.allowAutoTrain) "allowed" else "blocked"}: ${snap.reason}",
        semantic = if (snap.allowAutoTrain) Semantic.POSITIVE else Semantic.CRITICAL,
    )
    if (ui.heldIds.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ui.heldIds.forEach { id ->
                val spec = FlVariants.byId(id)
                val m = ui.heldMetrics[id]
                FioriKeyValueRow(spec.label, "${m?.trainMs ?: 0} ms, ${spec.weightCount * 4} bytes")
            }
        }
    }
}
