package com.jugaad.agent.fl

/**
 * Fixed shapes/hyperparameters shared by every variant's exported `fl_head_*.tflite`
 * and the Python training script (ml/fl/). Per-variant shape/weight-count/epoch/recipe
 * fields live in [VariantSpec]; changing any of these without re-exporting the models
 * breaks the signature contract.
 */
object FlConstants {
    const val INPUT_DIM = 260
    const val SENSOR_DIMS = 4
    const val N_CLASSES = 3
    const val TRAIN_BATCH = 8
    const val LR = 0.05f
    const val IMU_SCALE = 10f
}
