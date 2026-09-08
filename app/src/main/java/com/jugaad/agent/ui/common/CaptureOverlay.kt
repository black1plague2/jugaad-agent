package com.jugaad.agent.ui.common

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jugaad.agent.sensor.CaptureProgress
import com.jugaad.agent.ui.theme.Accent
import com.jugaad.agent.ui.theme.Ink
import com.jugaad.agent.ui.theme.InkLine
import com.jugaad.agent.ui.theme.TextHi
import com.jugaad.agent.ui.theme.TextMid

/**
 * Full-bleed capture overlay: countdown ring, big seconds-left number, live
 * waveform, contact-placement reminder. Shown while [CaptureProgress.running].
 */
@Composable
fun CaptureOverlay(
    progress: CaptureProgress,
    label: String,
    modifier: Modifier = Modifier,
) {
    val animatedFraction by animateFloatAsState(
        targetValue = progress.fraction,
        animationSpec = tween(220),
        label = "capFraction",
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Ink.copy(alpha = 0.96f))
            .padding(28.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            Text(
                label.uppercase(),
                color = Accent,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.5.sp,
                style = MaterialTheme.typography.labelLarge,
            )

            Box(contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(220.dp)) {
                    val stroke = 14.dp.toPx()
                    val inset = stroke / 2f
                    val arcSize = Size(size.width - stroke, size.height - stroke)
                    drawArc(
                        color = InkLine,
                        startAngle = -90f, sweepAngle = 360f, useCenter = false,
                        topLeft = Offset(inset, inset), size = arcSize,
                        style = Stroke(width = stroke, cap = StrokeCap.Round),
                    )
                    drawArc(
                        color = Accent,
                        startAngle = -90f, sweepAngle = 360f * animatedFraction, useCenter = false,
                        topLeft = Offset(inset, inset), size = arcSize,
                        style = Stroke(width = stroke, cap = StrokeCap.Round),
                    )
                }
                Text(
                    text = "${progress.secondsLeft}",
                    color = TextHi,
                    fontSize = 84.sp,
                    fontWeight = FontWeight.Bold,
                )
            }

            WaveformView(
                peaks = progress.waveform,
                modifier = Modifier.fillMaxWidth(),
            )

            Text(
                "Keep the phone flat and pressed against the machine housing.\nHold still until the ring completes.",
                color = TextMid,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
