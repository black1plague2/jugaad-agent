package com.jugaad.agent.ml

import com.jugaad.agent.ml.signal.ImuVibrationIndex
import com.jugaad.agent.ml.signal.LogMel
import com.jugaad.agent.ml.signal.LogMelSpectrogram
import com.jugaad.agent.sensor.ImuCapture

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
        /** IMU 5-60 Hz energy fraction, 0 when no accelerometer data. */
        val imuIndex: Double,
        val imuDominantHz: Double,
    )

    fun analyze(audio: FloatArray, imu: ImuCapture.Reading?): Analysis {
        val logMel = logMelSpectrogram.compute(audio)
        val feature = logMelSpectrogram.featureVector(logMel)

        val imuResult = imu?.let {
            val mag = ImuVibrationIndex.magnitude(it.x, it.y, it.z)
            ImuVibrationIndex.compute(mag, it.effectiveRateHz)
        }
        return Analysis(
            logMel = logMel,
            feature = feature,
            imuIndex = imuResult?.index ?: 0.0,
            imuDominantHz = imuResult?.dominantHz ?: 0.0,
        )
    }
}
