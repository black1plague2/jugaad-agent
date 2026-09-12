package com.jugaad.agent.ui.common.fiori

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** KPI tile: a raised 16 dp card with a secondary label above a headline value. */
@Composable
fun FioriKpiTile(
    label: String,
    value: String,
    unit: String? = null,
    delta: String? = null,
    deltaSemantic: Semantic = Semantic.NEUTRAL,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(FioriRadius)
            .background(FioriColors.Surface)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(label, color = FioriColors.TextSecondary, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(value, color = FioriColors.TextPrimary, style = MaterialTheme.typography.headlineSmall, maxLines = 1, softWrap = false)
            if (unit != null) {
                Text(
                    unit,
                    color = FioriColors.TextSecondary,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 3.dp),
                )
            }
        }
        if (delta != null) {
            Text(delta, color = FioriColors.of(deltaSemantic), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** Small tinted status tag: [FioriColors.of] at status-tint alpha over Surface, dot only for a real state. */
@Composable
fun FioriStatusLabel(text: String, semantic: Semantic, modifier: Modifier = Modifier) {
    val color = FioriColors.of(semantic)
    Row(
        modifier = modifier
            .clip(FioriTagRadius)
            .background(color.copy(alpha = FioriColors.STATUS_TINT_ALPHA))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (semantic != Semantic.NEUTRAL) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, color = color, style = MaterialTheme.typography.labelMedium, maxLines = 1, softWrap = false)
    }
}

/** Object cell: title/subtitle row with optional leading/status/trailing slots, as a borderless raised 16 dp card. The 12 dp gap to the next cell is the caller's (e.g. Arrangement.spacedBy). */
@Composable
fun FioriObjectCell(
    title: String,
    subtitle: String? = null,
    status: (@Composable () -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val base = modifier
        .fillMaxWidth()
        .clip(FioriRadius)
        .background(FioriColors.Surface)
        .let { if (onClick != null) it.clickable(onClick = onClick) else it }
        .padding(20.dp)

    Row(modifier = base, verticalAlignment = Alignment.CenterVertically) {
        if (leading != null) {
            Box(Modifier.padding(end = 12.dp)) { leading() }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = FioriColors.TextPrimary, style = MaterialTheme.typography.titleLarge)
            if (subtitle != null) {
                Text(subtitle, color = FioriColors.TextSecondary, style = MaterialTheme.typography.bodyMedium)
            }
            if (status != null) {
                Box(Modifier.padding(top = 4.dp)) { status() }
            }
        }
        if (trailing != null) {
            Box(Modifier.padding(start = 12.dp)) { trailing() }
        }
    }
}

/** Section header with an optional trailing action (a link, a button, a toggle). */
@Composable
fun FioriSectionHeader(
    title: String,
    action: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            color = FioriColors.TextPrimary,
            style = MaterialTheme.typography.titleLarge,
        )
        if (action != null) action()
    }
}

/** Key/value row: label on the left, tabular-number value on the right, optionally tinted. */
@Composable
fun FioriKeyValueRow(key: String, value: String, valueSemantic: Semantic? = null) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Weighted so a long value (e.g. a full event sentence) wraps instead of pushing into
        // or overlapping the key; short values still hug the right edge as before.
        Text(
            key,
            color = FioriColors.TextSecondary,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Text(
            value,
            color = valueSemantic?.let { FioriColors.of(it) } ?: FioriColors.TextPrimary,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.End,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1.5f),
        )
    }
}

/** Empty state for any list that can have zero rows. */
@Composable
fun FioriEmptyState(title: String, body: String, action: (@Composable () -> Unit)? = null) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(FioriRadius)
            .background(FioriColors.Surface)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            title,
            color = FioriColors.TextPrimary,
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
        )
        Text(
            body,
            color = FioriColors.TextSecondary,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        if (action != null) action()
    }
}

/** Full-width floating-tone banner for an urgent one-line notice, with a semantic left rule. */
@Composable
fun FioriBanner(text: String, semantic: Semantic = Semantic.CRITICAL, modifier: Modifier = Modifier) {
    val color = FioriColors.of(semantic)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(FioriRadius)
            .background(FioriColors.SurfaceElevated)
            .border(1.dp, FioriColors.Hairline, FioriRadius)
            .drawBehind {
                drawRect(color = color, size = androidx.compose.ui.geometry.Size(3.dp.toPx(), size.height))
            }
            .padding(start = 16.dp, top = 12.dp, end = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, color = FioriColors.TextPrimary, style = MaterialTheme.typography.bodyMedium)
    }
}

/** 4 dp fill bar on a hairline track. Width is caller-controlled via [modifier]. [accent] fills with Brand instead of the semantic colour (e.g. the champion row in a ranking). */
@Composable
fun HorizontalMeter(fraction: Float, semantic: Semantic, modifier: Modifier = Modifier, accent: Boolean = false) {
    val clamped = fraction.coerceIn(0f, 1f)
    val fillColor = if (accent) FioriColors.Brand else FioriColors.of(semantic)
    Box(
        modifier = modifier
            .height(4.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(FioriColors.Hairline),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(clamped)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(fillColor),
        )
    }
}

/** 3x3 confusion grid, reusing [HeatmapCell]'s styling: diagonal (correct) tints Positive, off-diagonal tints Negative, both scaled by the row-normalised value; zero-count cells render as empty raised cells. */
@Composable
fun ConfusionGrid(matrix: Array<IntArray>, labels: List<String>, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth()) {
            Box(Modifier.weight(1f))
            labels.forEach { label ->
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Text(
                        label,
                        color = FioriColors.TextSecondary,
                        style = MaterialTheme.typography.labelMedium,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
        matrix.forEachIndexed { i, row ->
            val rowSum = row.sum().coerceAtLeast(1)
            Row(Modifier.fillMaxWidth()) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                    Text(
                        labels.getOrElse(i) { "" },
                        color = FioriColors.TextSecondary,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(end = 6.dp),
                    )
                }
                row.forEachIndexed { j, value ->
                    val frac = value.toFloat() / rowSum
                    val isDiagonal = i == j
                    val tintValue = when {
                        value == 0 -> null
                        isDiagonal -> 0.5f + frac * 0.5f
                        else -> (0.5f - frac * 0.5f).coerceAtLeast(0f)
                    }
                    HeatmapCell(
                        tintValue = tintValue,
                        displayText = "$value",
                        modifier = Modifier.weight(1f).height(40.dp).padding(2.dp),
                    )
                }
            }
        }
    }
}
