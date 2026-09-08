package com.jugaad.agent.viz

/**
 * Approximate "inferno" colormap (dark navy -> magenta -> orange -> pale yellow).
 * Shared by the PNG renderer and the Compose spectrogram so the report matches
 * what the technician saw on screen.
 */
object HeatColor {

    // 6 control points, evenly spaced on [0, 1].
    private val stops = arrayOf(
        intArrayOf(0, 0, 4),
        intArrayOf(40, 11, 84),
        intArrayOf(101, 21, 110),
        intArrayOf(159, 42, 99),
        intArrayOf(212, 72, 66),
        intArrayOf(245, 125, 21),
    )
    private val last = intArrayOf(252, 255, 164)

    /** @param v value in [0, 1]. @return 0xAARRGGBB with alpha = 0xFF. */
    fun argb(v: Float): Int {
        val x = v.coerceIn(0f, 1f) * stops.size
        val i = x.toInt().coerceIn(0, stops.size - 1)
        val f = x - i
        val a = stops[i]
        val b = if (i + 1 < stops.size) stops[i + 1] else last
        val r = (a[0] + (b[0] - a[0]) * f).toInt()
        val g = (a[1] + (b[1] - a[1]) * f).toInt()
        val bl = (a[2] + (b[2] - a[2]) * f).toInt()
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
    }
}
