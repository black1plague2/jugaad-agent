package com.jugaad.agent.ml.classifier

import com.jugaad.agent.domain.model.FaultClass
import com.jugaad.agent.domain.model.InferenceBackend

/**
 * SHOULD-HAVE path. Turns a 128x128 log-mel image into a fault class.
 *
 * Two implementations:
 *   - [ExecuTorchFaultClassifier]  real CNN, XNNPACK or QNN .pte  (needs the AAR + model)
 *   - [HeuristicFaultClassifier]   dependency-free stand-in so the UI + demo flow
 *                                  always have a label to show
 */
interface FaultClassifier {

    data class Prediction(
        val faultClass: FaultClass,
        val confidence: Float,
        val probabilities: FloatArray,
        val backend: InferenceBackend,
        val inferenceMs: Long,
    )

    val isReady: Boolean
    val backend: InferenceBackend

    /** @param logMel row-major, mel-major, length 128*128. */
    fun classify(logMel: FloatArray): Prediction?

    fun close() {}
}
