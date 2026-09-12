package com.jugaad.agent.ui.common.fiori

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * Canvas polyline over [values], auto min/max scaled with 8% padding, drawn over a
 * hairline baseline in a single semantic colour, Positive by default. When
 * [animateOnce], the line draws in left-to-right over 600 ms exactly once per
 * composition, unless the system animator duration scale is 0 (reduced motion),
 * in which case it renders statically.
 */
@Composable
fun Sparkline(
    values: List<Float>,
    modifier: Modifier = Modifier,
    semantic: Semantic = Semantic.POSITIVE,
    animateOnce: Boolean = true,
) {
    val color = FioriColors.of(semantic)
    val context = LocalContext.current
    val reducedMotion = remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    val shouldAnimate = animateOnce && !reducedMotion
    val progress = remember { Animatable(if (shouldAnimate) 0f else 1f) }
    var hasAnimated by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (shouldAnimate && !hasAnimated) {
            hasAnimated = true
            progress.animateTo(1f, animationSpec = tween(600, easing = FastOutSlowInEasing))
        } else if (!shouldAnimate) {
            progress.snapTo(1f)
        }
    }

    // Sizing is entirely caller-controlled: callers place this inline (e.g. a leading
    // slot in a FioriObjectCell) with their own explicit width/height.
    Canvas(modifier = modifier) {
        val baselineY = size.height - 1.dp.toPx()
        drawLine(
            color = FioriColors.Hairline,
            start = Offset(0f, baselineY),
            end = Offset(size.width, baselineY),
            strokeWidth = 1.dp.toPx(),
        )

        if (values.isEmpty()) return@Canvas

        val min = values.min()
        val max = values.max()
        val range = (max - min).let { if (it < 1e-6f) 1f else it }
        val pad = range * 0.08f
        val paddedMin = min - pad
        val paddedRange = (range + 2 * pad).coerceAtLeast(1e-6f)
        val n = values.size
        val stepX = if (n > 1) size.width / (n - 1) else 0f

        fun yOf(v: Float): Float = size.height - ((v - paddedMin) / paddedRange) * size.height

        clipRect(right = size.width * progress.value) {
            val path = Path()
            values.forEachIndexed { i, v ->
                val x = i * stepX
                val y = yOf(v)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(
                path = path,
                color = color,
                style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
        }
    }
}
