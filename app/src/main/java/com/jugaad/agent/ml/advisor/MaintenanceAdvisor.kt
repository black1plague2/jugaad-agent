package com.jugaad.agent.ml.advisor

import com.jugaad.agent.domain.model.Diagnosis
import com.jugaad.agent.domain.model.FaultClass
import com.jugaad.agent.domain.model.IssueSuggestion
import com.jugaad.agent.domain.model.MachineStatus

/** Context handed to the advisor to turn numbers into a sentence a technician can act on. */
data class AdviceContext(
    val status: MachineStatus,
    val anomalyScore: Double,
    val fault: FaultClass?,
    val dominantHz: Double,
    val imuIndex: Double,
    val assetName: String,
    /** Top-ranked catalogue suggestion from [com.jugaad.agent.ml.diagnosis.RulesEngine], if any. */
    val topIssue: IssueSuggestion? = null,
    /** Same list `topIssue` was drawn from, highest confidence first — for prompts that want more than one. */
    val issues: List<IssueSuggestion> = emptyList(),
)

interface MaintenanceAdvisor {
    val source: Diagnosis.AdviceSource
    val isReady: Boolean
    /** One or two short, plain-language sentences. Never throws — returns "" on failure. */
    suspend fun advise(ctx: AdviceContext): String
    fun close() {}
}

/** Shared prompt so the LLM path and any future providers stay consistent. */
object AdvicePrompt {
    fun build(ctx: AdviceContext): String {
        val faultText = ctx.fault?.label ?: "not classified"
        val freqText = if (ctx.dominantHz > 0) "${ctx.dominantHz.toInt()} Hz" else "no specific frequency"
        val base = """
            You are an industrial maintenance expert. Machine status: ${ctx.status.label}.
            Anomaly score: ${"%.1f".format(ctx.anomalyScore)}. Most likely fault: $faultText.
            Spectrogram shows increased energy at $freqText.
            Write 1-2 short, clear sentences of practical advice for a non-technical technician.
            Use simple language.
        """.trimIndent().replace("\n", " ")
        if (ctx.issues.isEmpty()) return base
        val bullets = ctx.issues.take(3).joinToString(" ") { i ->
            "- ${i.label} (${(i.confidence * 100).toInt()}%): ${i.action}"
        }
        return "$base Likely issues from the rule engine: $bullets"
    }
}
