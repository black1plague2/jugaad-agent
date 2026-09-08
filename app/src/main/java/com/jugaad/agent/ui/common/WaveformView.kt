package com.jugaad.agent.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.jugaad.agent.ui.theme.Accent

/**
 * Mirrored bar waveform driven by a [0,1] peak array (from CaptureCoordinator).
 */
@Composable
fun WaveformView(
    peaks: FloatArray,
    modifier: Modifier = Modifier,
    height: Dp = 96.dp,
    color: Color = Accent,
) {
    Canvas(modifier = modifier.fillMaxWidth().height(height)) {
        if (peaks.isEmpty()) return@Canvas
        val midY = size.height / 2f
        val barW = size.width / peaks.size
        val gap = barW * 0.35f
        peaks.forEachIndexed { i, p ->
            val h = (p.coerceIn(0f, 1f)) * midY * 0.95f + 1f
            val x = i * barW + barW / 2f
            drawLine(
                color = color,
                start = Offset(x, midY - h),
                end = Offset(x, midY + h),
                strokeWidth = (barW - gap).coerceAtLeast(1f),
                cap = StrokeCap.Round,
            )
        }
    }
}
