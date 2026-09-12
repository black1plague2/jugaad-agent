package com.jugaad.agent.ml.diagnosis

import com.jugaad.agent.core.config.AppConfig
import com.jugaad.agent.core.config.Condition
import com.jugaad.agent.core.config.FaultRule
import com.jugaad.agent.core.config.MachineType
import com.jugaad.agent.domain.model.IssueSuggestion

/**
 * Scores a machine type's catalogue faults against one diagnose run's [Evidence].
 *
 * `confidence = sum(weight of satisfied conditions) / sum(all weights)`; a fault is
 * suggested when `confidence >= cfg.diagnosis.minConfidence`. The top
 * `cfg.diagnosis.maxIssues` are returned, sorted by confidence, each carrying its
 * fault's full keyword list (the vocabulary a technician recognizes) and action.
 *
 * Pure and stateless — the caller (see [com.jugaad.agent.domain.usecase.DiagnoseUseCase])
 * is what keeps this from firing on a Healthy reading.
 */
object RulesEngine {

    fun evaluate(type: MachineType, evidence: Evidence, cfg: AppConfig): List<IssueSuggestion> =
        type.faults
            .mapNotNull { fault -> confidence(fault, evidence)?.let { fault to it } }
            .filter { (_, confidence) -> confidence >= cfg.diagnosis.minConfidence }
            .sortedByDescending { (_, confidence) -> confidence }
            .take(cfg.diagnosis.maxIssues)
            .map { (fault, confidence) ->
                IssueSuggestion(
                    faultId = fault.id,
                    label = fault.label,
                    confidence = confidence.toFloat(),
                    matchedKeywords = fault.keywords,
                    action = fault.action,
                    severity = fault.severity,
                )
            }

    /** Null when the fault has no evidence conditions at all — it can never be scored. */
    private fun confidence(fault: FaultRule, evidence: Evidence): Double? {
        val totalWeight = fault.evidence.sumOf { it.weight }
        if (totalWeight <= 0.0) return null
        val satisfiedWeight = fault.evidence.filter { it.isSatisfiedBy(evidence) }.sumOf { it.weight }
        return satisfiedWeight / totalWeight
    }

    private fun Condition.isSatisfiedBy(evidence: Evidence): Boolean {
        val v = evidence[metric] ?: return false
        return when (op) {
            ">" -> v > value
            ">=" -> v >= value
            "<" -> v < value
            "<=" -> v <= value
            "==" -> v == value
            "!=" -> v != value
            else -> false
        }
    }
}
