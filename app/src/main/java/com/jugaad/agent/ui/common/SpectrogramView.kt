package com.jugaad.agent.ui.common

import android.graphics.Bitmap
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.jugaad.agent.ui.common.fiori.FioriColors
import com.jugaad.agent.viz.HeatColor

/**
 * The animated "machine heartbeat": the log-mel heatmap with a sweeping scan
 * line and a gentle breathing pulse. Purely decorative motion, the data is static.
 */
@Composable
fun SpectrogramView(
    logMel: FloatArray?,
    nMels: Int,
    frames: Int,
    modifier: Modifier = Modifier,
    animate: Boolean = true,
) {
    val image = remember(logMel, nMels, frames) {
        if (logMel == null || logMel.size < nMels * frames) null
        else buildHeatmap(logMel, nMels, frames).asImageBitmap()
    }

    val transition = rememberInfiniteTransition(label = "heartbeat")
    val sweep by transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1800, easing = LinearEasing), RepeatMode.Restart),
        label = "sweep",
    )
    val pulse by transition.animateFloat(
        initialValue = 0.992f, targetValue = 1.008f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse),
        label = "pulse",
    )

    Canvas(modifier = modifier.fillMaxWidth().aspectRatio(1.6f)) {
        val s = if (animate) pulse else 1f
        scale(s, s) {
            if (image != null) {
                drawImage(
                    image = image,
                    dstOffset = IntOffset(0, 0),
                    dstSize = IntSize(size.width.toInt(), size.height.toInt()),
                    filterQuality = FilterQuality.Medium,
                )
            } else {
                drawPlaceholder()
            }
        }
        if (animate && image != null) {
            val x = sweep * size.width
            drawLine(
                color = FioriColors.TextPrimary.copy(alpha = 0.35f),
                start = Offset(x, 0f),
                end = Offset(x, size.height),
                strokeWidth = 2f,
            )
        }
    }
}

private fun DrawScope.drawPlaceholder() {
    drawRect(FioriColors.Surface)
}

/**
 * Same "heartbeat" treatment but fed a pre-rendered [Bitmap] (e.g. the saved
 * history PNG) instead of a raw log-mel float array.
 */
@Composable
fun SpectrogramImage(
    bitmap: Bitmap?,
    modifier: Modifier = Modifier,
    animate: Boolean = true,
) {
    val image = remember(bitmap) { bitmap?.asImageBitmap() }
    val transition = rememberInfiniteTransition(label = "heartbeatImg")
    val sweep by transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1800, easing = LinearEasing), RepeatMode.Restart),
        label = "sweepImg",
    )
    val pulse by transition.animateFloat(
        initialValue = 0.992f, targetValue = 1.008f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse),
        label = "pulseImg",
    )
    Canvas(modifier = modifier.fillMaxWidth().aspectRatio(1.6f)) {
        val s = if (animate) pulse else 1f
        scale(s, s) {
            if (image != null) {
                drawImage(
                    image = image,
                    dstOffset = IntOffset(0, 0),
                    dstSize = IntSize(size.width.toInt(), size.height.toInt()),
                    filterQuality = FilterQuality.Medium,
                )
            } else {
                drawPlaceholder()
            }
        }
        if (animate && image != null) {
            val x = sweep * size.width
            drawLine(
                color = FioriColors.TextPrimary.copy(alpha = 0.35f),
                start = Offset(x, 0f),
                end = Offset(x, size.height),
                strokeWidth = 2f,
            )
        }
    }
}

private fun buildHeatmap(logMel: FloatArray, nMels: Int, frames: Int): Bitmap {
    var min = Float.MAX_VALUE
    var max = -Float.MAX_VALUE
    for (v in logMel) { if (v < min) min = v; if (v > max) max = v }
    val range = (max - min).takeIf { it > 1e-6f } ?: 1f

    val pixels = IntArray(nMels * frames)
    for (b in 0 until nMels) {
        val y = nMels - 1 - b
        for (t in 0 until frames) {
            val norm = (logMel[b * frames + t] - min) / range
            pixels[y * frames + t] = HeatColor.argb(norm)
        }
    }
    return Bitmap.createBitmap(pixels, frames, nMels, Bitmap.Config.ARGB_8888)
}
