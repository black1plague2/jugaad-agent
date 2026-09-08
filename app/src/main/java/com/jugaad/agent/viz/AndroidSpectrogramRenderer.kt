package com.jugaad.agent.viz

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import com.jugaad.agent.domain.usecase.SpectrogramPngRenderer
import java.io.ByteArrayOutputStream

/**
 * Log-mel float image -> PNG bytes, low frequencies at the bottom, per-image
 * min/max normalisation, [HeatColor] colormap. Used for the persisted history
 * thumbnail and the "Share Report" image.
 */
class AndroidSpectrogramRenderer : SpectrogramPngRenderer {

    override fun render(
        logMel: FloatArray,
        nMels: Int,
        frames: Int,
        outWidth: Int,
        outHeight: Int,
    ): ByteArray {
        var min = Float.MAX_VALUE
        var max = -Float.MAX_VALUE
        for (v in logMel) {
            if (v < min) min = v
            if (v > max) max = v
        }
        val range = (max - min).takeIf { it > 1e-6f } ?: 1f

        // Small source bitmap (mel x frames), then scale up with the canvas.
        val src = Bitmap.createBitmap(frames, nMels, Bitmap.Config.ARGB_8888)
        for (b in 0 until nMels) {
            val y = nMels - 1 - b            // flip so low freq is at the bottom
            for (t in 0 until frames) {
                val norm = (logMel[b * frames + t] - min) / range
                src.setPixel(t, y, HeatColor.argb(norm))
            }
        }

        val out = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888)
        Canvas(out).apply {
            drawColor(0xFF0B0F14.toInt())
            drawBitmap(
                src,
                Rect(0, 0, frames, nMels),
                Rect(0, 0, outWidth, outHeight),
                Paint(Paint.FILTER_BITMAP_FLAG),
            )
        }
        src.recycle()

        return ByteArrayOutputStream().use { bos ->
            out.compress(Bitmap.CompressFormat.PNG, 100, bos)
            out.recycle()
            bos.toByteArray()
        }
    }
}
