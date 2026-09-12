package com.jugaad.agent.ml

import com.jugaad.agent.ml.signal.ImuVibrationIndex
import com.jugaad.agent.ml.signal.LogMel
import com.jugaad.agent.ml.signal.LogMelSpectrogram
import com.jugaad.agent.ml.signal.MagneticIndex
import com.jugaad.agent.sensor.MotionCapture

/**
 * One call turns a raw capture into everything the decision layer needs.
 */
class FeatureExtractor(
    private val logMelSpectrogram: LogMelSpectrogram = LogMelSpectrogram(),
) {
    data class Analysis(
        /** 128 x 128 log-mel image (row-major, mel-major). */
        val logMel: LogMel,
        /** 256-d feature vector: per-band mean (128) || per-band std (128). */
        val feature: FloatArray,
        /** Accelerometer 5-60 Hz energy fraction, 0 when no accelerometer data. */
        val imuIndex: Double,
        val imuDominantHz: Double,
        /** Gyroscope |omega| 5-60 Hz energy fraction, 0 when no gyroscope. */
        val gyroIndex: Double,
        /** Magnetometer |B| 3-25 Hz energy fraction, 0 when no magnetometer. */
        val magIndex: Double,
        /** Magnetometer AC RMS in microtesla, 0 when no magnetometer. */
        val magRms: Double,
    )

    fun analyze(audio: FloatArray, motion: MotionCapture.Reading?): Analysis {
        val logMel = logMelSpectrogram.compute(audio)
        val feature = logMelSpectrogram.featureVector(logMel)

        val imuResult = motion?.accel?.let {
            ImuVibrationIndex.compute(ImuVibrationIndex.magnitude(it.x, it.y, it.z), motion.accelRateHz)
        }
        val gyroResult = motion?.gyro?.let {
            ImuVibrationIndex.compute(ImuVibrationIndex.magnitude(it.x, it.y, it.z), motion.gyroRateHz)
        }
        val magResult = motion?.mag?.let {
            MagneticIndex.compute(ImuVibrationIndex.magnitude(it.x, it.y, it.z), motion.magRateHz)
        }

        return Analysis(
            logMel = logMel,
            feature = feature,
            imuIndex = imuResult?.index ?: 0.0,
            imuDominantHz = imuResult?.dominantHz ?: 0.0,
            gyroIndex = gyroResult?.index ?: 0.0,
            magIndex = magResult?.index ?: 0.0,
            magRms = magResult?.rmsMicroTesla ?: 0.0,
        )
    }
}
