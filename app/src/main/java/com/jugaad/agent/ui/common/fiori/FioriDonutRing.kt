package com.jugaad.agent.ui.common.fiori

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Ring gauge (the "83.3% Accuracy" ring): a 12 dp crimson progress arc on a
 * hairline track, with [value] centred in headline weight and [label] below it.
 * Animates once over 700 ms FastOutSlowIn, or renders statically when the system
 * animator duration scale is 0.
 */
@Composable
fun DonutRing(fraction: Float, label: String, value: String, modifier: Modifier = Modifier) {
    val clamped = fraction.coerceIn(0f, 1f)
    val context = LocalContext.current
    val reducedMotion = remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    val progress = remember { Animatable(if (reducedMotion) clamped else 0f) }

    LaunchedEffect(clamped, reducedMotion) {
        if (reducedMotion) {
            progress.snapTo(clamped)
        } else {
            progress.animateTo(clamped, animationSpec = tween(700, easing = FastOutSlowInEasing))
        }
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(140.dp)) {
            val stroke = 12.dp.toPx()
            val inset = stroke / 2f
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(
                color = FioriColors.Hairline,
                startAngle = -90f, sweepAngle = 360f, useCenter = false,
                topLeft = Offset(inset, inset), size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
            drawArc(
                color = FioriColors.Brand,
                startAngle = -90f, sweepAngle = 360f * progress.value, useCenter = false,
                topLeft = Offset(inset, inset), size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, color = FioriColors.TextPrimary, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Text(label, color = FioriColors.TextSecondary, style = MaterialTheme.typography.labelMedium)
        }
    }
}
