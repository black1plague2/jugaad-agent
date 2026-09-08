package com.jugaad.agent.core

import com.jugaad.agent.BuildConfig

/**
 * Single source of truth for capture + DSP parameters.
 *
 * These mirror the `buildConfigField` entries in app/build.gradle.kts so that the
 * Python reference implementation (ml/logmel_reference.py) and the on-device
 * front-end stay bit-for-bit comparable.
 */
object Constants {

    // --- Audio capture --------------------------------------------------------
    const val SAMPLE_RATE_HZ = BuildConfig.SAMPLE_RATE_HZ            // 44100
    const val CAPTURE_SECONDS = BuildConfig.CAPTURE_SECONDS          // 3
    const val CAPTURE_SAMPLES = SAMPLE_RATE_HZ * CAPTURE_SECONDS     // 132300

    // --- Log-mel front-end --------------------------------------------------------
    const val N_FFT = BuildConfig.N_FFT                              // 2048
    const val HOP = BuildConfig.HOP                                  // 1024
    const val N_MELS = BuildConfig.N_MELS                            // 128
    const val SPEC_FRAMES = BuildConfig.SPEC_FRAMES                  // 128  -> 128 x 128 image

    /** Mel filter-bank band edges. 20 Hz .. 11 kHz per the spec. */
    const val MEL_FMIN_HZ = 20.0
    const val MEL_FMAX_HZ = 11_000.0

    /** 256-d feature vector = [per-band mean (128) || per-band std (128)]. */
    const val FEATURE_DIM = N_MELS * 2                               // 256

    // --- IMU vibration index ------------------------------------------------------
    /** Target accelerometer rate; HIGH_SAMPLING_RATE_SENSORS is required above 200 Hz. */
    const val IMU_TARGET_RATE_HZ = 400
    const val IMU_BAND_LOW_HZ = 5.0
    const val IMU_BAND_HIGH_HZ = 60.0

    // --- Baseline ------------------------------------------------------------------
    const val BASELINE_CLIPS = 3

    // --- Anomaly thresholds (calibratable per asset) ----------------------------
    const val DEFAULT_T1 = 2.0   // <= T1  -> Healthy
    const val DEFAULT_T2 = 4.0   // <= T2  -> Warning ; > T2 -> Critical

    // --- CNN classifier ---------------------------------------------------------
    val FAULT_LABELS = listOf("Healthy", "Rotor Imbalance", "Airflow Obstruction")

    // --- Storage --------------------------------------------------------------
    const val ASSETS_DIR = "assets"          // filesDir/assets/<assetId>/
    const val MODELS_DIR = "models"          // filesDir/models/  (copied from apk assets)
    const val REPORTS_DIR = "reports"        // cacheDir/reports/
}
