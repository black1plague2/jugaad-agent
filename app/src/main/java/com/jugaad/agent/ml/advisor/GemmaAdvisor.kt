package com.jugaad.agent.ml.advisor

import android.content.Context
import com.jugaad.agent.core.Logx
import com.jugaad.agent.domain.model.Diagnosis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * On-device advice via **Gemma 3 1B (int4)** through the MediaPipe LLM Inference API
 * (`com.google.mediapipe.tasks.genai`).
 *
 * As with the ExecuTorch wrapper, MediaPipe is reached by reflection so the app
 * compiles without the `tasks-genai` AAR (`jugaad.gemma.enabled=false`). Enable the
 * flag, drop `gemma3-1b-it-int4.task` into `assets/models/`, and this lights up.
 *
 * If ANYTHING here fails (AAR missing, model missing, OOM, timeout) the caller
 * falls back to [TemplateAdvisor] and the demo continues.
 */
class GemmaAdvisor(
    private val appContext: Context,
    private val modelFile: File?,
) : MaintenanceAdvisor {

    override val source = Diagnosis.AdviceSource.GEMMA
    private var engine: Any? = null
    override val isReady get() = engine != null

    fun load(): Boolean {
        if (engine != null) return true
        val model = modelFile
        if (model == null || !model.exists()) {
            Logx.i("Gemma: model file absent — TemplateAdvisor stays active")
            return false
        }
        return try {
            val llmClass = Class.forName("com.google.mediapipe.tasks.genai.llminference.LlmInference")
            val optionsClass = Class.forName(
                "com.google.mediapipe.tasks.genai.llminference.LlmInference\$LlmInferenceOptions"
            )
            val builder = optionsClass.getMethod("builder").invoke(null)
            builder.callIfPresent("setModelPath", String::class.java, model.absolutePath)
            builder.callIfPresent("setMaxTokens", Int::class.javaPrimitiveType!!, 160)
            builder.callIfPresent("setMaxTopK", Int::class.javaPrimitiveType!!, 40)
            builder.callIfPresent("setTopK", Int::class.javaPrimitiveType!!, 40)
            builder.callIfPresent("setTemperature", Float::class.javaPrimitiveType!!, 0.6f)
            val options = builder.javaClass.getMethod("build").invoke(builder)

            engine = llmClass
                .getMethod("createFromOptions", Context::class.java, optionsClass)
                .invoke(null, appContext, options)
            Logx.i("Gemma: engine ready (${model.name})")
            true
        } catch (t: Throwable) {
            Logx.w("Gemma: load failed — using TemplateAdvisor", t)
            false
        }
    }

    override suspend fun advise(ctx: AdviceContext): String = withContext(Dispatchers.Default) {
        val e = engine ?: return@withContext ""
        val prompt = AdvicePrompt.build(ctx)
        withTimeoutOrNull(8_000) {
            runCatching {
                val method = e.javaClass.getMethod("generateResponse", String::class.java)
                (method.invoke(e, prompt) as? String).orEmpty().trim()
            }.onFailure { Logx.w("Gemma: generateResponse failed", it) }.getOrDefault("")
        }.orEmpty().ifBlank {
            Logx.w("Gemma: empty / timed-out response")
            ""
        }
    }

    override fun close() {
        runCatching { engine?.javaClass?.getMethod("close")?.invoke(engine) }
        engine = null
    }

    private fun Any.callIfPresent(name: String, argType: Class<*>, value: Any) {
        try {
            javaClass.getMethod(name, argType).invoke(this, value)
        } catch (_: NoSuchMethodException) {
            // Method name varies by tasks-genai version; skip silently.
        } catch (t: Throwable) {
            Logx.w("Gemma: builder.$name failed", t)
        }
    }
}
