package com.jugaad.agent.viz

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import com.jugaad.agent.domain.model.Diagnosis
import com.jugaad.agent.domain.model.MachineStatus
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Renders a self-contained "Share Report" PNG entirely offline. 1080 x 1350,
 * dark industrial styling to match the app.
 */
object ReportRenderer {

    fun render(
        outFile: File,
        assetName: String,
        diagnosis: Diagnosis,
        spectrogram: Bitmap?,
    ): File {
        val w = 1080
        val h = 1350
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(0xFF0B0F14.toInt())

        val pad = 64f
        val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFEAF2FF.toInt(); textSize = 58f; typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF9FB2C7.toInt(); textSize = 34f
        }
        val big = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFEAF2FF.toInt(); textSize = 44f; typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val body = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFEAF2FF.toInt(); textSize = 36f
        }

        var y = pad + 40f
        c.drawText("Jugaad Agent — Report", pad, y, title)
        y += 46f
        c.drawText(assetName, pad, y, label)
        y += 30f
        val ts = SimpleDateFormat("d MMM yyyy · HH:mm", Locale.getDefault()).format(Date(diagnosis.timestampMs))
        c.drawText(ts, pad, y, label)

        // Status band
        y += 50f
        val statusColor = when (diagnosis.status) {
            MachineStatus.HEALTHY -> 0xFF22C55E.toInt()
            MachineStatus.WARNING -> 0xFFF59E0B.toInt()
            MachineStatus.CRITICAL -> 0xFFEF4444.toInt()
        }
        val bandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = statusColor }
        c.drawRoundRect(pad, y, w - pad, y + 120f, 24f, 24f, bandPaint)
        val statusText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF0B0F14.toInt(); textSize = 64f; typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        c.drawText(diagnosis.status.label.uppercase(), pad + 32f, y + 82f, statusText)
        y += 170f

        // Metrics
        c.drawText("Anomaly score", pad, y, label)
        c.drawText("%.2f".format(diagnosis.anomalyScore), w - pad - big.measureText("%.2f".format(diagnosis.anomalyScore)), y, big)
        y += 60f
        diagnosis.faultClass?.let {
            c.drawText("Likely fault", pad, y, label)
            c.drawText(it.label, w - pad - body.measureText(it.label), y, body)
            y += 60f
        }
        if (diagnosis.dominantHz > 0) {
            c.drawText("Dominant frequency", pad, y, label)
            val f = "${diagnosis.dominantHz.toInt()} Hz"
            c.drawText(f, w - pad - body.measureText(f), y, body)
            y += 60f
        }
        c.drawText("Backend", pad, y, label)
        val be = if (diagnosis.faultClass != null) diagnosis.backend.longLabel else "Anomaly only"
        c.drawText(be, w - pad - body.measureText(be), y, body)
        y += 80f

        // Spectrogram
        spectrogram?.let {
            val specTop = y
            val specH = 360f
            c.drawBitmap(
                it,
                Rect(0, 0, it.width, it.height),
                android.graphics.RectF(pad, specTop, w - pad, specTop + specH),
                Paint(Paint.FILTER_BITMAP_FLAG),
            )
            y += specH + 40f
        }

        // Advice
        c.drawText("Advice", pad, y, label)
        y += 46f
        wrap(diagnosis.advice, body, (w - 2 * pad)).forEach { line ->
            c.drawText(line, pad, y, body)
            y += 46f
        }

        val footer = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF64768A.toInt(); textSize = 28f }
        c.drawText("Generated on-device · no network used", pad, h - 48f, footer)

        outFile.parentFile?.mkdirs()
        outFile.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
        return outFile
    }

    private fun wrap(text: String, paint: Paint, maxWidth: Float): List<String> {
        if (text.isBlank()) return listOf("—")
        val words = text.split(" ")
        val lines = ArrayList<String>()
        var cur = StringBuilder()
        for (word in words) {
            val candidate = if (cur.isEmpty()) word else "$cur $word"
            if (paint.measureText(candidate) > maxWidth && cur.isNotEmpty()) {
                lines.add(cur.toString())
                cur = StringBuilder(word)
            } else {
                cur = StringBuilder(candidate)
            }
        }
        if (cur.isNotEmpty()) lines.add(cur.toString())
        return lines
    }
}
