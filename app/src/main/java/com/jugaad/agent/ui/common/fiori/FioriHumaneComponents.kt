package com.jugaad.agent.ui.common.fiori

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Mini stat cell: a small label/value/optional-sub tile on a faint row-highlight
 * fill, e.g. the Stitch "Host Address / 192.168.49.1" cell in a status grid.
 */
@Composable
fun MiniStatCard(
    label: String,
    value: String,
    sub: String? = null,
    subSemantic: Semantic = Semantic.NEUTRAL,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(FioriControlRadius)
            .background(FioriColors.RowHighlight)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(label, color = FioriColors.TextSecondary, style = MaterialTheme.typography.labelSmall, maxLines = 2)
        Text(
            value,
            color = FioriColors.TextPrimary,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
        if (sub != null) {
            Text(sub, color = FioriColors.of(subSemantic), style = MaterialTheme.typography.labelSmall)
        }
    }
}

/** Pill status chip, e.g. "Mesh Active". [dot] is a real-state indicator, not decoration. */
@Composable
fun StatusChip(text: String, semantic: Semantic, dot: Boolean = true) {
    val color = FioriColors.of(semantic)
    val isNeutral = semantic == Semantic.NEUTRAL
    Row(
        modifier = Modifier
            .clip(FioriTagRadius)
            .background(if (isNeutral) FioriColors.ChipNeutral else color.copy(alpha = FioriColors.STATUS_TINT_ALPHA))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (dot) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(6.dp))
        }
        Text(
            text,
            color = if (isNeutral) FioriColors.ChipText else color,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            softWrap = false,
        )
    }
}

/**
 * Distribution bar for up to a few classes. [parts] is (fraction-or-count, semantic)
 * pairs; the Negative class always paints Brand crimson rather than its usual
 * warning tint, since this bar is the one place a class distribution is itself
 * the primary action-worthy signal.
 */
@Composable
fun SegmentBar(parts: List<Pair<Float, Semantic>>, modifier: Modifier = Modifier) {
    val total = parts.sumOf { it.first.toDouble() }.toFloat().let { if (it < 1e-6f) 1f else it }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(FioriTagRadius)
            .background(FioriColors.Hairline),
    ) {
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            parts.forEach { (value, semantic) ->
                if (value > 0f) {
                    val color = if (semantic == Semantic.NEGATIVE) FioriColors.Brand else FioriColors.of(semantic)
                    Box(
                        modifier = Modifier
                            .weight(value / total)
                            .fillMaxHeight()
                            .background(color),
                    )
                }
            }
        }
    }
}

/**
 * Tonal cell shared by [MatrixHeatmap] and [ConfusionGrid]: a raised base with an
 * optional Positive/Negative tint (Positive at or above 0.5, Negative below) whose
 * alpha scales with [tintValue], and tabular-numeral label text. `null` renders an
 * empty raised cell with no tint.
 */
@Composable
internal fun HeatmapCell(tintValue: Float?, displayText: String?, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(FioriControlRadius)
            .background(FioriColors.Surface)
            .then(
                if (tintValue != null) {
                    val alpha = (0.15f + tintValue.coerceIn(0f, 1f) * 0.55f).coerceIn(0.15f, 0.7f)
                    val tint = if (tintValue < 0.5f) FioriColors.Negative else FioriColors.Positive
                    Modifier.background(tint.copy(alpha = alpha))
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (displayText != null) {
            Text(
                displayText,
                color = FioriColors.TextPrimary,
                style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
            )
        }
    }
}

/**
 * Row/column matrix of tonal cells (see [HeatmapCell]): row labels fixed on the
 * left, column labels on top, the cell grid horizontally scrollable once it is
 * wider than the screen.
 */
@Composable
fun MatrixHeatmap(
    rows: List<String>,
    cols: List<String>,
    values: List<List<Float?>>,
    modifier: Modifier = Modifier,
    valueFormat: (Float) -> String = { "%.0f".format(it * 100) },
) {
    val cellSize = 56.dp
    val headerHeight = 28.dp
    val labelColumnWidth = 88.dp
    val scrollState = rememberScrollState()

    Row(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.width(labelColumnWidth)) {
            Spacer(Modifier.height(headerHeight))
            rows.forEach { row ->
                Box(Modifier.height(cellSize), contentAlignment = Alignment.CenterStart) {
                    Text(row, color = FioriColors.TextSecondary, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                }
            }
        }
        Column(modifier = Modifier.horizontalScroll(scrollState)) {
            Row {
                cols.forEach { col ->
                    Box(Modifier.width(cellSize).height(headerHeight), contentAlignment = Alignment.Center) {
                        Text(
                            col,
                            color = FioriColors.TextSecondary,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
            values.forEach { rowValues ->
                Row {
                    rowValues.forEach { v ->
                        HeatmapCell(
                            tintValue = v,
                            displayText = v?.let(valueFormat),
                            modifier = Modifier.padding(2.dp).size(cellSize - 4.dp),
                        )
                    }
                }
            }
        }
    }
}

/** One activity-feed row: a semantic dot, a title and a meta line. */
@Composable
fun FeedRow(dotSemantic: Semantic, title: String, meta: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(FioriColors.of(dotSemantic)))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(title, color = FioriColors.TextPrimary, style = MaterialTheme.typography.bodyMedium)
            Text(meta, color = FioriColors.TextSecondary, style = MaterialTheme.typography.labelSmall)
        }
    }
}

/** Section title with an optional muted trailing note, e.g. "Connected Devices   2 active devices". */
@Composable
fun SectionTitle(title: String, trailing: String? = null) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, color = FioriColors.TextPrimary, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        if (trailing != null) {
            Text(trailing, color = FioriColors.TextSecondary, style = MaterialTheme.typography.labelSmall)
        }
    }
}
