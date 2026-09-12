package com.jugaad.agent.fl

import com.jugaad.agent.domain.model.FaultClass
import com.jugaad.agent.domain.model.InferenceBackend
import com.jugaad.agent.ml.classifier.FaultClassifier

/**
 * [FaultClassifier] backed by the network's current champion variant. The
 * legacy log-mel-only overload has nothing to build [FeatureDelta] from (no
 * baseline, no IMU index), so it returns null — callers go through
 * [FaultClassifier.Input] instead (see [com.jugaad.agent.domain.usecase.DiagnoseUseCase]).
 */
class LiteRtFaultClassifier(private val runtime: FlRuntime) : FaultClassifier {

    override val isReady = true
    override val backend = InferenceBackend.LITERT

    override fun classify(logMel: FloatArray): FaultClassifier.Prediction? = null

    override fun classify(input: FaultClassifier.Input): FaultClassifier.Prediction {
        val t0 = System.nanoTime()
        val x = FeatureDelta.build(input.feature, input.imuIndex, input.gyroIndex, input.magIndex, input.magRms, input.baseline)
        val probs = runtime.trainer(runtime.championId.value).infer(x)
        val idx = probs.indices.maxByOrNull { probs[it] } ?: 0
        val ms = (System.nanoTime() - t0) / 1_000_000
        return FaultClassifier.Prediction(
            faultClass = FaultClass.fromIndex(idx),
            confidence = probs[idx],
            probabilities = probs,
            backend = InferenceBackend.LITERT,
            inferenceMs = ms,
        )
    }
}
