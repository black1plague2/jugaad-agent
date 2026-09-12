package com.jugaad.agent.fl

import org.tensorflow.lite.Interpreter
import java.io.File

/**
 * Thin wrapper around one variant's TFLite signature-based interpreter
 * (`infer` / `train` / `get_weights` / `set_weights`). [Interpreter] is not
 * thread-safe, so every call is synchronized on this instance. `noise` and
 * `balanced` share the `base` graph but each gets its own [FlModel] (and thus
 * its own [Interpreter]), so their weights evolve independently. [threads]
 * caps the interpreter's thread count — see [TrainBudget] (Decision 8, v3).
 */
class FlModel(modelFile: File, val spec: VariantSpec, threads: Int = 4) : AutoCloseable {

    private val interpreter = Interpreter(modelFile, Interpreter.Options().setNumThreads(threads))
    private val lock = Any()

    /** @return probs[3] */
    fun infer(x: FloatArray): FloatArray {
        require(x.size == FlConstants.INPUT_DIM) {
            "FlModel.infer: expected ${FlConstants.INPUT_DIM} inputs, got ${x.size}"
        }
        synchronized(lock) {
            val probs = Array(1) { FloatArray(FlConstants.N_CLASSES) }
            val logits = Array(1) { FloatArray(FlConstants.N_CLASSES) }
            interpreter.runSignature(
                mapOf("x" to arrayOf(x)),
                mapOf("probs" to probs, "logits" to logits),
                "infer",
            )
            return probs[0]
        }
    }

    /** @param xBatch/yBatch exactly [FlConstants.TRAIN_BATCH] rows. @return mean loss. */
    fun trainStep(xBatch: Array<FloatArray>, yBatch: Array<FloatArray>): Float {
        require(xBatch.size == FlConstants.TRAIN_BATCH) {
            "FlModel.trainStep: expected ${FlConstants.TRAIN_BATCH} x-rows, got ${xBatch.size}"
        }
        require(yBatch.size == FlConstants.TRAIN_BATCH) {
            "FlModel.trainStep: expected ${FlConstants.TRAIN_BATCH} y-rows, got ${yBatch.size}"
        }
        synchronized(lock) {
            val loss = FloatArray(1)
            interpreter.runSignature(
                mapOf("x" to xBatch, "y" to yBatch),
                mapOf("loss" to loss),
                "train",
            )
            return loss[0]
        }
    }

    fun getWeights(): FloatArray {
        synchronized(lock) {
            val w = FloatArray(spec.weightCount)
            interpreter.runSignature(
                mapOf("dummy" to floatArrayOf(0f)),
                mapOf("w" to w),
                "get_weights",
            )
            return w
        }
    }

    fun setWeights(w: FloatArray) {
        require(w.size == spec.weightCount) {
            "FlModel.setWeights: expected ${spec.weightCount} weights, got ${w.size}"
        }
        synchronized(lock) {
            val ok = FloatArray(1)
            interpreter.runSignature(
                mapOf("w" to w),
                mapOf("ok" to ok),
                "set_weights",
            )
        }
    }

    override fun close() {
        synchronized(lock) { interpreter.close() }
    }
}
