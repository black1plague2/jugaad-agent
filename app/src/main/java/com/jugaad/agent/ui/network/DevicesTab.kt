package com.jugaad.agent.ui.network

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.jugaad.agent.domain.model.FaultClass
import com.jugaad.agent.ui.common.fiori.FioriColors
import com.jugaad.agent.ui.common.fiori.FioriEmptyState
import com.jugaad.agent.ui.common.fiori.FioriKeyValueRow
import com.jugaad.agent.ui.common.fiori.GhostButton
import com.jugaad.agent.ui.common.fiori.MatrixHeatmap
import com.jugaad.agent.ui.common.fiori.PrimaryButton
import com.jugaad.agent.ui.common.fiori.SectionTitle
import com.jugaad.agent.ui.common.fiori.SegmentBar
import com.jugaad.agent.ui.common.fiori.Semantic
import com.jugaad.agent.ui.common.fiori.StatusChip

/** Devices tab (Stitch "Connected Devices / Sync"): peer roster and the two dataset matrices. */
@Composable
fun DevicesTab(
    ui: NetworkUiState,
    vm: NetworkViewModel,
    runWithWifiPermission: (() -> Unit) -> Unit,
    runWithServicePermission: (() -> Unit) -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        OwnerUnreachableBanner(ui)
        HeaderCard(ui, vm, runWithWifiPermission, runWithServicePermission)
        NearbySection(ui, vm)
        PeersSection(ui, runWithWifiPermission, vm)
        DatasetOverviewSection(ui)
        PeerSampleMatrixSection(ui)
    }
}

@Composable
private fun HeaderCard(
    ui: NetworkUiState,
    vm: NetworkViewModel,
    runWithWifiPermission: (() -> Unit) -> Unit,
    runWithServicePermission: (() -> Unit) -> Unit,
) {
    val (statusText, statusSemantic) = syncStatus(ui)
    val online = ui.displayNodes.count { System.currentTimeMillis() - it.lastSeenMs < STALE_MS }

    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(FioriColors.SurfaceElevated).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StatusChip(text = statusText, semantic = statusSemantic)
        ui.autoJoin?.let { Text(it, color = FioriColors.TextSecondary, style = MaterialTheme.typography.bodyMedium) }
        Text("$online active device" + if (online == 1) "" else "s", color = FioriColors.TextPrimary, style = MaterialTheme.typography.headlineMedium)
        val r = ui.lastSync
        if (r == null) {
            Text("No samples shared this session yet", color = FioriColors.TextSecondary, style = MaterialTheme.typography.bodyMedium)
        } else {
            Text(
                "Samples shared this session: ${r.samplesSent} sent, ${r.samplesReceived} received",
                color = FioriColors.TextSecondary,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Automatic background sync", color = FioriColors.TextPrimary, style = MaterialTheme.typography.bodyLarge)
            Switch(checked = ui.schedulerEnabled, onCheckedChange = { vm.toggleScheduler(it) })
        }
        when {
            ui.serving || ui.group.isGroupOwner -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PrimaryButton(
                    text = "Start sync service",
                    onClick = { runWithServicePermission { vm.startServing() } },
                    enabled = ui.modelReady && !ui.serving,
                    modifier = Modifier.weight(1f),
                )
                GhostButton(text = "Stop", onClick = { vm.stopServing() }, enabled = ui.serving, modifier = Modifier.weight(1f))
            }
            // Client, or no group yet: sync to the WiFi Direct owner or to an owner seen on
            // this WiFi network; or become the owner (the service also forms a WiFi Direct group).
            else -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PrimaryButton(
                    text = "Sync now",
                    onClick = { vm.syncNow() },
                    enabled = ui.modelReady && !ui.busy && (ui.group.ownerAddress != null || ui.lanPeers.isNotEmpty()),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GhostButton(
                        text = "Serve as owner",
                        onClick = { runWithServicePermission { vm.startServing() } },
                        enabled = ui.modelReady,
                        modifier = Modifier.weight(1f),
                    )
                    if (!ui.group.formed) {
                        GhostButton(
                            text = "Create group",
                            onClick = { runWithWifiPermission { vm.createGroup() } },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PeersSection(ui: NetworkUiState, runWithWifiPermission: (() -> Unit) -> Unit, vm: NetworkViewModel) {
    SectionTitle("Connected peers")
    if (ui.peers.isEmpty()) {
        FioriEmptyState("No peers found yet", "Discover peers from the Network tab while another phone is nearby.")
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ui.peers.forEach { peer ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(FioriColors.Surface).padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    // Several phones can share the same model name, so the last 5 characters
                    // of the MAC address disambiguate them in the list.
                    Text("${peer.name} - ${peer.address.takeLast(5)}", color = FioriColors.TextPrimary, style = MaterialTheme.typography.titleLarge)
                    Text(peerStatusLabel(peer.status), color = FioriColors.TextSecondary, style = MaterialTheme.typography.bodyMedium)
                }
                if (peer.status != 0) {
                    GhostButton(text = "Connect", onClick = { runWithWifiPermission { vm.connect(peer.address) } })
                }
            }
        }
    }
}

/** Owners advertising on the local WiFi network (mDNS), listed by node name; tapping Sync uses
 * the owner's LAN address directly, no WiFi Direct pairing needed. */
@Composable
private fun NearbySection(ui: NetworkUiState, vm: NetworkViewModel) {
    SectionTitle("Nearby on this WiFi", trailing = "${ui.lanPeers.size} owner" + if (ui.lanPeers.size == 1) "" else "s")
    if (ui.lanPeers.isEmpty()) {
        FioriEmptyState("No owner on this network yet", "Tap Serve as owner on one phone; every other phone lists it here by node name and joins it on its own.")
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ui.lanPeers.forEach { peer ->
            val isLast = peer.host == ui.config?.lastOwnerAddress
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(FioriColors.Surface).padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(peer.name, color = FioriColors.TextPrimary, style = MaterialTheme.typography.titleLarge, maxLines = 1)
                    Text(
                        if (isLast) "${peer.host}, last owner" else peer.host,
                        color = FioriColors.TextSecondary,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                GhostButton(
                    text = "Sync",
                    onClick = { vm.syncWith(peer.host) },
                    enabled = ui.modelReady && !ui.busy && !ui.serving,
                )
            }
        }
    }
}

/** Old DatasetSection's own counts, shown as a 3-class distribution bar per the contract. */
@Composable
private fun DatasetOverviewSection(ui: NetworkUiState) {
    val total = ui.trainCounts.sum() + ui.valCounts.sum()
    SectionTitle("Dataset overview", trailing = "$total total record" + if (total == 1) "" else "s")
    if (total == 0) {
        FioriEmptyState("No labelled samples yet", "Confirm labels on the Measurement document screen to build a dataset.")
        return
    }
    val counts = FaultClass.entries.map { fc -> (ui.trainCounts.getOrElse(fc.index) { 0 } + ui.valCounts.getOrElse(fc.index) { 0 }) }
    val parts = FaultClass.entries.map { fc -> (counts[fc.index].toFloat() / total.toFloat()) to classSemantic(fc) }
    SegmentBar(parts = parts, modifier = Modifier.fillMaxWidth())
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        FaultClass.entries.forEach { fc ->
            FioriKeyValueRow(fc.label, "${counts[fc.index]} (${pct1(counts[fc.index].toFloat() / total.toFloat())}%)")
        }
        if (ui.pending > 0) {
            FioriKeyValueRow("Pending review", "${ui.pending}")
        }
    }
}

/** "Peer sample matrix": [com.jugaad.agent.fl.SharedPool.countByOrigin] only reports per-origin
 * totals, not a per-class breakdown per origin, so this is a single "samples" column of each
 * origin's share of the pool rather than an invented per-class split. */
@Composable
private fun PeerSampleMatrixSection(ui: NetworkUiState) {
    SectionTitle("Peer sample matrix", trailing = "${ui.poolCount} pooled")
    if (ui.poolByOrigin.isEmpty()) {
        FioriEmptyState("No peer samples yet", "Sync with another node to receive shared samples.")
        return
    }
    val sortedOrigins = ui.poolByOrigin.entries.sortedByDescending { it.value }
    val rows = sortedOrigins.map { it.key }
    val values = sortedOrigins.map { (_, count) -> listOf(count.toFloat() / ui.poolCount.toFloat()) }
    MatrixHeatmap(rows = rows, cols = listOf("samples"), values = values, modifier = Modifier.fillMaxWidth())
}
