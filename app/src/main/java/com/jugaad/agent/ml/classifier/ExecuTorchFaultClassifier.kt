package com.jugaad.agent.ml.classifier

import com.jugaad.agent.core.Constants
import com.jugaad.agent.core.Logx
import com.jugaad.agent.domain.model.FaultClass
import com.jugaad.agent.domain.model.InferenceBackend
import com.jugaad.agent.ml.executorch.SocDetector
import java.io.File
import java.lang.reflect.Array as RArray
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Real CNN inference through **PyTorch ExecuTorch**.
 *
 * The ExecuTorch Java API (`org.pytorch.executorch.{Module,EValue,Tensor}`) is
 * reached by reflection so this file compiles even when the
 * `org.pytorch:executorch-android` AAR is NOT on the classpath
 * (`jugaad.executorch.enabled=false`). When the AAR *is* present and a `.pte`
 * exists in `filesDir/models/`, it runs for real.
 *
 * Backend selection:
 *   - `fault_cnn_qnn.pte`      loaded first on Snapdragon HTP parts (SM8850 etc.).
 *                              ExecuTorch prints `[Qnn...]` lines to logcat — that
 *                              is the NPU proof for the demo.
 *   - `fault_cnn_xnnpack.pte`  CPU fallback, loaded everywhere else / on QNN failure.
 *
 * Input contract (must match ml/train_cnn.py):
 *   shape [1, 1, 128, 128], per-image standardised (subtract mean, divide by std).
 */
class ExecuTorchFaultClassifier(
    private val xnnpackPte: File?,
    private val qnnPte: File?,
) : FaultClassifier {

    private var module: Any? = null
    private var evalueClass: Class<*>? = null
    private var tensorClass: Class<*>? = null
    private var _backend = InferenceBackend.NONE

    override val isReady get() = module != null
    override val backend get() = _backend

    fun load(): Boolean {
        if (module != null) return true
        return try {
            val moduleClass = Class.forName("org.pytorch.executorch.Module")
            evalueClass = Class.forName("org.pytorch.executorch.EValue")
            tensorClass = Class.forName("org.pytorch.executorch.Tensor")

            val htp = SocDetector.info.htpCapable
            val order = buildList {
                if (htp && qnnPte?.exists() == true) add(InferenceBackend.NPU to qnnPte)
                if (xnnpackPte?.exists() == true) add(InferenceBackend.CPU to xnnpackPte)
                if (qnnPte?.exists() == true && !htp) add(InferenceBackend.NPU to qnnPte)
            }
            if (order.isEmpty()) {
                Logx.i("ExecuTorch: no .pte files present, staying on heuristic path")
                return false
            }

            val loadFn = moduleClass.getMethod("load", String::class.java)
            for ((be, file) in order) {
                try {
                    Logx.i("ExecuTorch: loading ${file.name} for $be path")
                    module = loadFn.invoke(null, file.absolutePath)
                    _backend = be
                    Logx.i("ExecuTorch: loaded ${file.name}  backend=$be")
                    return true
                } catch (t: Throwable) {
                    Logx.w("ExecuTorch: ${file.name} failed for $be, trying next", t)
                }
            }
            false
        } catch (t: Throwable) {
            Logx.w("ExecuTorch runtime not available (AAR missing?) — heuristic path stays active", t)
            false
        }
    }

    override fun classify(logMel: FloatArray): FaultClassifier.Prediction? {
        val mod = module ?: return null
        val eClass = evalueClass ?: return null
        val tClass = tensorClass ?: return null
        return try {
            val t0 = System.nanoTime()
            val input = standardise(logMel)

            // Tensor.fromBlob(float[], long[])
            val fromBlob = tClass.getMethod("fromBlob", FloatArray::class.java, LongArray::class.java)
            val tensor = fromBlob.invoke(null, input, longArrayOf(1, 1, 128, 128))

            // EValue.from(Tensor)
            val evFrom = eClass.getMethod("from", tClass)
            val inputEValue = evFrom.invoke(null, tensor)

            // module.forward(EValue[])  (varargs -> single EValue[] param)
            val inputArray = RArray.newInstance(eClass, 1)
            RArray.set(inputArray, 0, inputEValue)
            val forward = mod.javaClass.getMethod("forward", inputArray.javaClass)
            val outObj = forward.invoke(mod, inputArray)

            val out0 = RArray.get(outObj, 0)
            val toTensor = eClass.getMethod("toTensor")
            val outTensor = toTensor.invoke(out0)
            val getData = tClass.getMethod("getDataAsFloatArray")
            @Suppress("UNCHECKED_CAST")
            val logits = getData.invoke(outTensor) as FloatArray

            val probs = softmax(logits)
            val idx = probs.indices.maxBy { probs[it] }
            val ms = (System.nanoTime() - t0) / 1_000_000
            Logx.i("ExecuTorch: infer ${ms}ms backend=$_backend -> ${FaultClass.fromIndex(idx).label} ${"%.2f".format(probs[idx])}")

            FaultClassifier.Prediction(
                faultClass = FaultClass.fromIndex(idx),
                confidence = probs[idx],
                probabilities = probs,
                backend = _backend,
                inferenceMs = ms,
            )
        } catch (t: Throwable) {
            Logx.e("ExecuTorch inference failed", t)
            null
        }
    }

    override fun close() {
        runCatching { module?.javaClass?.getMethod("destroy")?.invoke(module) }
        module = null
    }

    private fun standardise(x: FloatArray): FloatArray {
        var mean = 0.0
        for (v in x) mean += v
        mean /= x.size
        var varAcc = 0.0
        for (v in x) varAcc += (v - mean) * (v - mean)
        val std = sqrt(varAcc / x.size).coerceAtLeast(1e-6)
        return FloatArray(x.size) { ((x[it] - mean) / std).toFloat() }
    }

    private fun softmax(x: FloatArray): FloatArray {
        val m = x.max()
        val e = FloatArray(x.size) { exp((x[it] - m).toDouble()).toFloat() }
        val s = e.sum()
        return FloatArray(x.size) { e[it] / s }
    }
}
