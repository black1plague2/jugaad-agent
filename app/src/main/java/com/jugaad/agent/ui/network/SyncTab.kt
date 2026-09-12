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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.jugaad.agent.domain.model.FaultClass
import com.jugaad.agent.fl.FlVariants
import com.jugaad.agent.ui.common.fiori.FioriBanner
import com.jugaad.agent.ui.common.fiori.FioriColors
import com.jugaad.agent.ui.common.fiori.FioriEmptyState
import com.jugaad.agent.ui.common.fiori.FioriKeyValueRow
import com.jugaad.agent.ui.common.fiori.MatrixHeatmap
import com.jugaad.agent.ui.common.fiori.MiniStatCard
import com.jugaad.agent.ui.common.fiori.PrimaryButton
import com.jugaad.agent.ui.common.fiori.SectionTitle
import com.jugaad.agent.ui.common.fiori.SegmentBar
import com.jugaad.agent.ui.common.fiori.Semantic

/** Sync tab (Stitch "Sync & Model Weights"): champion training card, local dataset, feature
 * importance heatmap and the training/sync pipeline switches. */
@Composable
fun SyncTab(ui: NetworkUiState, vm: NetworkViewModel) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        LastSyncBanner(ui)
        ActiveModelCard(ui, vm)
        LocalRecordsSection(ui)
        FeatureImportanceSection(ui)
        PipelineControlsSection(ui, vm)
    }
}

@Composable
private fun LastSyncBanner(ui: NetworkUiState) {
    val r = ui.lastSync
    if (r == null) {
        FioriEmptyState("No sync yet", "Sync with a group owner to merge model weights across the network.")
        return
    }
    FioriBanner(r.message, semantic = if (r.promoted != null) Semantic.POSITIVE else Semantic.INFORMATIVE)
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        FioriKeyValueRow("Role", r.role.name)
        FioriKeyValueRow("Peers", "${r.peers}")
        r.variants.forEach { v ->
            val label = runCatching { FlVariants.byId(v).label }.getOrDefault(v)
            FioriKeyValueRow("$label round", "${r.roundsBefore[v] ?: 0} to ${r.roundsAfter[v] ?: 0}")
            FioriKeyValueRow("$label held-out accuracy", valAccTransition(r.valBefore[v] ?: -1f, r.valAfter[v] ?: -1f))
        }
        r.promoted?.let { promotedId ->
            val label = runCatching { FlVariants.byId(promotedId).label }.getOrDefault(promotedId)
            FioriKeyValueRow("Promoted", label, valueSemantic = Semantic.POSITIVE)
        }
    }
}

@Composable
private fun ActiveModelCard(ui: NetworkUiState, vm: NetworkViewModel) {
    val spec = FlVariants.byId(ui.championId)
    val m = ui.heldMetrics[ui.championId]
    val heldOutAcc = m?.valAcc ?: -1f
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(FioriColors.SurfaceElevated).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column {
            Text("Active model", color = FioriColors.TextSecondary, style = MaterialTheme.typography.labelMedium)
            Text(spec.label, color = FioriColors.TextPrimary, style = MaterialTheme.typography.headlineMedium)
        }
        MiniStatCard(
            label = "Held-out accuracy",
            value = if (heldOutAcc < 0f) "--" else "${pct1(heldOutAcc)}%",
            sub = if (heldOutAcc < 0f) "needs 4+ held-out samples" else null,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MiniStatCard(label = "Loss", value = m?.valLoss?.let { "%.3f".format(it) } ?: "--", modifier = Modifier.weight(1f))
            MiniStatCard(label = "Last trained", value = relativeTime(m?.lastTrainedMs ?: 0L), modifier = Modifier.weight(1f))
        }
        PrimaryButton(
            text = "Train now",
            onClick = { vm.trainNow() },
            enabled = ui.modelReady && !ui.busy && ui.trainCounts.sum() > 0,
            icon = Icons.Filled.PlayArrow,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Old DatasetSection's own counts (train + validation, this device only). */
@Composable
private fun LocalRecordsSection(ui: NetworkUiState) {
    val total = ui.trainCounts.sum() + ui.valCounts.sum()
    SectionTitle("Local records", trailing = "$total record" + if (total == 1) "" else "s")
    if (total == 0) {
        FioriEmptyState("No local records yet", "Confirm labels on the Measurement document screen to build a dataset.")
        return
    }
    val counts = FaultClass.entries.map { fc -> ui.trainCounts.getOrElse(fc.index) { 0 } + ui.valCounts.getOrElse(fc.index) { 0 } }
    val parts = FaultClass.entries.map { fc -> (counts[fc.index].toFloat() / total.toFloat()) to classSemantic(fc) }
    SegmentBar(parts = parts, modifier = Modifier.fillMaxWidth())
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        FaultClass.entries.forEach { fc ->
            Column {
                Text(fc.label, color = FioriColors.TextSecondary, style = MaterialTheme.typography.labelMedium)
                Text(
                    "${pct1(counts[fc.index].toFloat() / total.toFloat())}% (${counts[fc.index]})",
                    color = FioriColors.TextPrimary,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
    }
}

/** [FeatureImportance.Group] shares as a single-row heatmap, per the contract's exact shape. */
@Composable
private fun FeatureImportanceSection(ui: NetworkUiState) {
    SectionTitle("What the champion listens to")
    if (ui.importance.isEmpty()) {
        FioriEmptyState("Not computed yet", "Feature importance appears after the champion has trained at least once.")
        return
    }
    MatrixHeatmap(
        rows = listOf("share"),
        cols = ui.importance.map { it.name },
        values = listOf(ui.importance.map { it.share / 100f }),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun PipelineControlsSection(ui: NetworkUiState, vm: NetworkViewModel) {
    SectionTitle("Pipeline controls")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text("Auto-train", color = FioriColors.TextPrimary, style = MaterialTheme.typography.bodyLarge)
        Switch(checked = ui.config?.autoTrain ?: false, onCheckedChange = { vm.setAutoTrain(it) })
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text("Auto-sync after training", color = FioriColors.TextPrimary, style = MaterialTheme.typography.bodyLarge)
        Switch(checked = ui.config?.autoSync ?: false, onCheckedChange = { vm.setAutoSync(it) })
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text("Data sharing", color = FioriColors.TextPrimary, style = MaterialTheme.typography.bodyLarge)
        Switch(checked = ui.config?.shareSamples ?: true, onCheckedChange = { vm.setShareSamples(it) })
    }
    PrimaryButton(
        text = "Sync now",
        onClick = { vm.syncNow() },
        enabled = ui.modelReady && !ui.busy && ui.group.formed && !ui.group.isGroupOwner && ui.group.ownerAddress != null,
        icon = Icons.Filled.Sync,
        modifier = Modifier.fillMaxWidth(),
    )
}
