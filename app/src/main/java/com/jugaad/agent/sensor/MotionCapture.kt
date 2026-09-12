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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * High-rate accelerometer + gyroscope + magnetometer capture, all three over
 * the same window on one HandlerThread.
 *
 *  - SENSOR_DELAY_FASTEST requests the maximum rate each sensor supports;
 *    [BATCH_LATENCY_US] lets the hardware FIFO batch delivery instead of
 *    waking the AP for every sample (device-budget requirement — batching
 *    changes delivery cadence, not the sampled rate).
 *  - Gyroscope and magnetometer are optional: [Reading.gyro]/[Reading.mag]
 *    are null when the device has no such sensor (or capture yields too few
 *    samples). The accelerometer keeps the old [ImuCapture] contract of
 *    always producing a result: on failure it comes back as empty arrays so
 *    downstream indices degrade to 0 instead of the whole capture failing.
 *  - Effective rate is measured per sensor from event timestamps, not assumed,
 *    so the downstream FFT bin spacing is correct on every device.
 */
class MotionCapture(context: Context) {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val magSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

    data class Axes(val x: FloatArray, val y: FloatArray, val z: FloatArray)

    data class Reading(
        val accel: Axes,
        val gyro: Axes?,
        val mag: Axes?,
        val accelRateHz: Double,
        val gyroRateHz: Double,
        val magRateHz: Double,
    )

    fun isAvailable(type: Int): Boolean = sensorManager.getDefaultSensor(type) != null

    suspend fun capture(seconds: Int = Constants.CAPTURE_SECONDS): Reading = withContext(Dispatchers.Default) {
        val ht = HandlerThread("motion-capture").apply { start() }
        val handler = Handler(ht.looper)
        try {
            val accel = register(accelSensor, seconds * Constants.IMU_TARGET_RATE_HZ * 2, handler)
            val gyro = register(gyroSensor, seconds * Constants.IMU_TARGET_RATE_HZ * 2, handler)
            val mag = register(magSensor, seconds * Constants.MAG_TARGET_RATE_HZ * 2, handler)
            try {
                // +150ms covers delivery jitter plus the batching latency requested below.
                delay(seconds * 1000L + 150L)
            } finally {
                accel?.let { sensorManager.unregisterListener(it) }
                gyro?.let { sensorManager.unregisterListener(it) }
                mag?.let { sensorManager.unregisterListener(it) }
            }

            val accelRate = accel?.rateHz(Constants.IMU_TARGET_RATE_HZ.toDouble()) ?: 0.0
            val gyroRate = gyro?.rateHz(Constants.IMU_TARGET_RATE_HZ.toDouble()) ?: 0.0
            val magRate = mag?.rateHz(Constants.MAG_TARGET_RATE_HZ.toDouble()) ?: 0.0
            Logx.i(
                "MotionCapture accel n=${accel?.n ?: 0} rate=${"%.1f".format(accelRate)}Hz " +
                    "gyro n=${gyro?.n ?: 0} rate=${"%.1f".format(gyroRate)}Hz " +
                    "mag n=${mag?.n ?: 0} rate=${"%.1f".format(magRate)}Hz"
            )

            Reading(
                accel = accel?.takeIf { it.n >= MIN_SAMPLES }?.axes()
                    ?: Axes(FloatArray(0), FloatArray(0), FloatArray(0)),
                gyro = gyro?.takeIf { it.n >= MIN_SAMPLES }?.axes(),
                mag = mag?.takeIf { it.n >= MIN_SAMPLES }?.axes(),
                accelRateHz = accelRate,
                gyroRateHz = gyroRate,
                magRateHz = magRate,
            )
        } finally {
            ht.quitSafely()
        }
    }

    private fun register(sensor: Sensor?, capacity: Int, handler: Handler): Recorder? {
        if (sensor == null) return null
        val rec = Recorder(capacity)
        val ok = sensorManager.registerListener(rec, sensor, SensorManager.SENSOR_DELAY_FASTEST, BATCH_LATENCY_US, handler)
        return if (ok) rec else null
    }

    /** Batches x/y/z samples for one sensor while it's registered on the shared handler. */
    private class Recorder(capacity: Int) : SensorEventListener {
        private val xs = FloatArray(capacity)
        private val ys = FloatArray(capacity)
        private val zs = FloatArray(capacity)
        var n = 0
            private set
        private var firstTs = 0L
        private var lastTs = 0L

        override fun onSensorChanged(e: SensorEvent) {
            if (n >= xs.size) return
            if (firstTs == 0L) firstTs = e.timestamp
            lastTs = e.timestamp
            xs[n] = e.values[0]; ys[n] = e.values[1]; zs[n] = e.values[2]
            n++
        }

        override fun onAccuracyChanged(s: Sensor?, accuracy: Int) {}

        fun axes(): Axes = Axes(xs.copyOf(n), ys.copyOf(n), zs.copyOf(n))

        /** Effective rate from timestamps; falls back to [targetHz] if the span can't be measured. */
        fun rateHz(targetHz: Double): Double {
            if (n < MIN_SAMPLES) return 0.0
            val spanSec = if (lastTs > firstTs) (lastTs - firstTs) / 1_000_000_000.0 else 0.0
            return if (spanSec > 0) (n - 1) / spanSec else targetHz
        }
    }

    companion object {
        private const val BATCH_LATENCY_US = 100_000
        private const val MIN_SAMPLES = 16
    }
}
