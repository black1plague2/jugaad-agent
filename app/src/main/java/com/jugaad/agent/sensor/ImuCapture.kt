package com.jugaad.agent.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import com.jugaad.agent.core.Constants
import com.jugaad.agent.core.Logx
import com.jugaad.agent.core.Outcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * High-rate accelerometer capture.
 *
 *  - SENSOR_DELAY_FASTEST (samplingPeriodUs = 0) to request the maximum rate.
 *  - Needs HIGH_SAMPLING_RATE_SENSORS (install-time normal permission) to exceed
 *    200 Hz; below that Android silently caps the stream.
 *  - Events are delivered on a dedicated HandlerThread so a busy main/UI thread
 *    can't drop samples and depress the effective rate.
 *  - The effective rate is measured from event timestamps, not assumed, so the
 *    downstream FFT bin spacing is correct on every device.
 */
class ImuCapture(context: Context) {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accel: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    data class Reading(
        val x: FloatArray,
        val y: FloatArray,
        val z: FloatArray,
        val effectiveRateHz: Double,
        val sampleCount: Int,
    )

    fun isAvailable(): Boolean = accel != null

    suspend fun record(seconds: Int = Constants.CAPTURE_SECONDS): Outcome<Reading> {
        val sensor = accel ?: return Outcome.Err("No accelerometer on this device")

        val cap = seconds * Constants.IMU_TARGET_RATE_HZ * 2
        val xs = FloatArray(cap); val ys = FloatArray(cap); val zs = FloatArray(cap)
        var n = 0
        var firstTs = 0L
        var lastTs = 0L

        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                if (n >= cap) return
                if (firstTs == 0L) firstTs = e.timestamp
                lastTs = e.timestamp
                xs[n] = e.values[0]; ys[n] = e.values[1]; zs[n] = e.values[2]
                n++
            }
            override fun onAccuracyChanged(s: Sensor?, accuracy: Int) {}
        }

        val ht = HandlerThread("imu-capture").apply { start() }
        val handler = Handler(ht.looper)

        return withContext(Dispatchers.Default) {
            val ok = sensorManager.registerListener(listener, sensor, 0, 0, handler)
            if (!ok) {
                ht.quitSafely()
                return@withContext Outcome.Err("registerListener rejected (accelerometer)")
            }
            try {
                delay(seconds * 1000L + 120L)
            } finally {
                sensorManager.unregisterListener(listener)
                ht.quitSafely()
            }

            if (n < 16) return@withContext Outcome.Err("Only $n accelerometer samples captured")

            val spanSec = if (lastTs > firstTs) (lastTs - firstTs) / 1_000_000_000.0 else seconds.toDouble()
            val rate = if (spanSec > 0) (n - 1) / spanSec else Constants.IMU_TARGET_RATE_HZ.toDouble()
            Logx.i("ImuCapture n=$n effRate=${"%.1f".format(rate)}Hz (target ${Constants.IMU_TARGET_RATE_HZ})")

            Outcome.Ok(
                Reading(
                    x = xs.copyOf(n),
                    y = ys.copyOf(n),
                    z = zs.copyOf(n),
                    effectiveRateHz = rate,
                    sampleCount = n,
                )
            )
        }
    }

    /**
     * Contact-placement gate: true when the phone is lying still (|a| ~ g, low
     * jitter) i.e. resting on the machine housing rather than being held.
     */
    suspend fun isRestingStill(windowMs: Long = 700L): Boolean {
        val sensor = accel ?: return true
        val mags = ArrayList<Float>(96)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                mags.add(
                    sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2])
                )
            }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        val ht = HandlerThread("imu-still").apply { start() }
        val handler = Handler(ht.looper)
        return withContext(Dispatchers.Default) {
            sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME, handler)
            try { delay(windowMs) } finally {
                sensorManager.unregisterListener(listener)
                ht.quitSafely()
            }
            if (mags.size < 8) return@withContext false
            val mean = mags.average().toFloat()
            val jitter = mags.maxOf { abs(it - mean) }
            abs(mean - SensorManager.STANDARD_GRAVITY) < 1.2f && jitter < 0.6f
        }
    }
}
