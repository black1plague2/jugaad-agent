package com.jugaad.agent.ui.network

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jugaad.agent.domain.model.FaultClass
import com.jugaad.agent.fl.EventType
import com.jugaad.agent.fl.FlVariants
import com.jugaad.agent.fl.StrategyRank
import com.jugaad.agent.fl.VariantStanding
import com.jugaad.agent.ui.common.fiori.ConfusionGrid
import com.jugaad.agent.ui.common.fiori.DonutRing
import com.jugaad.agent.ui.common.fiori.FeedRow
import com.jugaad.agent.ui.common.fiori.FioriColors
import com.jugaad.agent.ui.common.fiori.FioriEmptyState
import com.jugaad.agent.ui.common.fiori.HorizontalMeter
import com.jugaad.agent.ui.common.fiori.MatrixHeatmap
import com.jugaad.agent.ui.common.fiori.MiniStatCard
import com.jugaad.agent.ui.common.fiori.SectionTitle
import com.jugaad.agent.ui.common.fiori.Semantic
import com.jugaad.agent.ui.common.fiori.StatusChip

private const val HISTORY_COLS = 8

/** Performance tab (Stitch "Training Performance"): champion donut, the strategy ranking
 * (old LeaderboardSection + StrategyRankSection merged per the contract), the training
 * activity heatmap, the live event feed and this device's confusion grid. */
@Composable
fun PerformanceTab(ui: NetworkUiState, wide: Boolean) {
    val left: @Composable () -> Unit = {
        ChampionSummary(ui)
        RankingSection(ui)
    }
    val right: @Composable () -> Unit = {
        TrainingActivityMatrix(ui)
        LiveActivityFeed(ui)
        ThisDeviceSection(ui)
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
private fun ChampionSummary(ui: NetworkUiState) {
    val standings = ui.network?.standings ?: emptyMap()
    val champStanding = standings[ui.championId]
    val bestChallenger = standings.filterKeys { it != ui.championId }.values.maxByOrNull { it.netAcc }
    val delta = if (bestChallenger != null && champStanding != null) (bestChallenger.netAcc - champStanding.netAcc) * 100f else null
    val online = ui.displayNodes.count { System.currentTimeMillis() - it.lastSeenMs < STALE_MS }
    val championSpec = FlVariants.byId(ui.championId)
    val champLoss = ui.heldMetrics[ui.championId]?.valLoss

    DonutRing(
        fraction = (champStanding?.netAcc ?: 0f).coerceIn(0f, 1f),
        label = "Accuracy",
        value = champStanding?.let { pct1(it.netAcc) + "%" } ?: "--",
        modifier = Modifier.fillMaxWidth(),
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        MiniStatCard(label = "Champion", value = championSpec.label, sub = champStanding?.let { pct1(it.netAcc) + "%" }, modifier = Modifier.weight(1f))
        MiniStatCard(
            label = "Best challenger",
            value = bestChallenger?.let { runCatching { FlVariants.byId(it.variantId).label }.getOrDefault(it.variantId) } ?: "--",
            sub = delta?.let { deltaPts(it) },
            subSemantic = when {
                delta == null -> Semantic.NEUTRAL
                delta >= 0f -> Semantic.POSITIVE
                else -> Semantic.NEGATIVE
            },
            modifier = Modifier.weight(1f),
        )
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        // No round-over-round loss history is kept in NetworkState, only this device's current
        // value, so the delta vs the previous round is left out rather than invented.
        MiniStatCard(label = "Training loss", value = champLoss?.let { "%.3f".format(it) } ?: "--", modifier = Modifier.weight(1f))
        MiniStatCard(label = "Nodes online", value = "$online", modifier = Modifier.weight(1f))
    }
}

/** Merges the old LeaderboardSection + StrategyRankSection into the contract's single ranking list. */
@Composable
private fun RankingSection(ui: NetworkUiState) {
    SectionTitle("Model architectures", trailing = "${FlVariants.ALL.size} setups")
    val network = ui.network
    if (network == null) {
        FioriEmptyState("No ranking yet", "Train and sync the champion model to build a strategy ranking.")
        return
    }
    val ranked = StrategyRank.rank(network)
    if (ranked.isEmpty()) {
        // No variant has standings yet (before the first sync): list every strategy, champion
        // first, so "8 setups" in the header isn't followed by a blank list.
        val orderedSpecs = listOf(FlVariants.byId(ui.championId)) + FlVariants.ALL.filter { it.id != ui.championId }
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            orderedSpecs.forEach { spec ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(spec.label, color = FioriColors.TextPrimary, style = MaterialTheme.typography.titleLarge)
                        StatusChip(text = "No data", semantic = Semantic.NEUTRAL)
                        Text("--", color = FioriColors.TextPrimary, style = MaterialTheme.typography.titleLarge)
                    }
                    HorizontalMeter(fraction = 0f, semantic = Semantic.NEUTRAL, modifier = Modifier.fillMaxWidth())
                    Text(spec.description, color = FioriColors.TextSecondary, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        ranked.forEach { standing: VariantStanding ->
            val spec = runCatching { FlVariants.byId(standing.variantId) }.getOrNull()
            val isChampion = standing.variantId == network.championId
            val eligible = standing.nVal >= MIN_ELIGIBLE_NVAL
            val (statusText, statusSemantic) = variantStatus(standing, isChampion, eligible)
            val m = ui.heldMetrics[standing.variantId]
            val subtitle = (spec?.description ?: standing.variantId) + (m?.valLoss?.let { ", loss %.3f".format(it) } ?: "")
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(spec?.label ?: standing.variantId, color = FioriColors.TextPrimary, style = MaterialTheme.typography.titleLarge)
                    StatusChip(text = statusText, semantic = statusSemantic)
                    Text(if (eligible) pct1(standing.netAcc) + "%" else "--", color = FioriColors.TextPrimary, style = MaterialTheme.typography.titleLarge)
                }
                HorizontalMeter(fraction = standing.netAcc, semantic = if (isChampion) Semantic.POSITIVE else Semantic.NEUTRAL, accent = isChampion, modifier = Modifier.fillMaxWidth())
                Text(subtitle, color = FioriColors.TextSecondary, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** rows = every variant label, cols = the last 8 rounds (oldest to newest), values =
 * [VariantStanding.history] padded with nulls at the front when a variant has fewer than 8
 * recorded rounds; a variant with no standing at all gets an all-null row. */
@Composable
private fun TrainingActivityMatrix(ui: NetworkUiState) {
    SectionTitle("Training activity matrix")
    val network = ui.network
    if (network == null || network.standings.isEmpty()) {
        FioriEmptyState("No training history yet", "Rounds appear here once at least one variant has trained.")
        return
    }
    val cols = (-(HISTORY_COLS - 1)..0).map { "r$it" }
    val rows = FlVariants.ALL.map { it.label }
    val values: List<List<Float?>> = FlVariants.ALL.map { spec ->
        val history = network.standings[spec.id]?.history
        val padded: List<Float?> = if (history == null) {
            List(HISTORY_COLS) { null }
        } else {
            val lastN = if (history.size > HISTORY_COLS) history.takeLast(HISTORY_COLS) else history
            val pad: List<Float?> = List(HISTORY_COLS - lastN.size) { null }
            pad + lastN
        }
        padded
    }
    MatrixHeatmap(rows = rows, cols = cols, values = values, modifier = Modifier.fillMaxWidth())
}

/** PROMOTE -> negative (crimson) dot, RECOVER/FAILOVER -> critical, everything else neutral,
 * exactly as specified by the contract. */
@Composable
private fun LiveActivityFeed(ui: NetworkUiState) {
    SectionTitle("Live activity feed")
    val events = ui.network?.events?.sortedByDescending { it.ts }?.take(10) ?: emptyList()
    if (events.isEmpty()) {
        FioriEmptyState("No activity yet", "Events appear here after the first sync with another node.")
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        events.forEach { e ->
            val dotSemantic = when (e.type) {
                EventType.PROMOTE -> Semantic.NEGATIVE
                EventType.RECOVER, EventType.FAILOVER -> Semantic.CRITICAL
                else -> Semantic.NEUTRAL
            }
            FeedRow(dotSemantic = dotSemantic, title = e.text, meta = relativeTime(e.ts))
        }
    }
}

@Composable
private fun ThisDeviceSection(ui: NetworkUiState) {
    SectionTitle("This device")
    val confusion = ui.confusion
    if (confusion == null) {
        FioriEmptyState("No held-out data yet", "Label at least one validation sample to see the confusion matrix.")
    } else {
        ConfusionGrid(confusion, FaultClass.entries.map { it.label })
    }
}
