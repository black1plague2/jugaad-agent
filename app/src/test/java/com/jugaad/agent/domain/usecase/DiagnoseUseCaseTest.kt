package com.jugaad.agent.domain.usecase

import com.jugaad.agent.domain.model.FaultClass
import com.jugaad.agent.domain.model.InferenceBackend
import com.jugaad.agent.ml.classifier.FaultClassifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * DiagnoseUseCase.run() needs a real CaptureCoordinator (AudioRecord/SensorManager), so the
 * full flow can't be exercised in a JVM unit test. This covers [DiagnoseUseCase.sanitizePrediction]
 * directly instead: the exact guard `run` calls before assigning faultClass/faultConfidence, so
 * a non-finite confidence (e.g. from a still-poisoned FL head) can never reach a persisted
 * Diagnosis and trip kotlinx.serialization's NaN/Infinity check on save.
 */
class DiagnoseUseCaseTest {

    private fun prediction(confidence: Float) = FaultClassifier.Prediction(
        faultClass = FaultClass.ROTOR_IMBALANCE,
        confidence = confidence,
        probabilities = floatArrayOf(0f, 1f, 0f),
        backend = InferenceBackend.NONE,
        inferenceMs = 0L,
    )

    @Test
    fun nanConfidenceIsDroppedAlongWithTheFaultClass() {
        assertNull(DiagnoseUseCase.sanitizePrediction(prediction(Float.NaN)))
    }

    @Test
    fun infiniteConfidenceIsDropped() {
        assertNull(DiagnoseUseCase.sanitizePrediction(prediction(Float.POSITIVE_INFINITY)))
        assertNull(DiagnoseUseCase.sanitizePrediction(prediction(Float.NEGATIVE_INFINITY)))
    }

    @Test
    fun finiteConfidencePassesThroughUnchanged() {
        val p = prediction(0.87f)
        assertEquals(p, DiagnoseUseCase.sanitizePrediction(p))
    }

    @Test
    fun nullPredictionStaysNull() {
        assertNull(DiagnoseUseCase.sanitizePrediction(null))
    }
}
