package com.jugaad.agent.domain.model

import kotlinx.serialization.Serializable

/**
 * One ranked candidate cause for the current reading, produced by
 * [com.jugaad.agent.ml.diagnosis.RulesEngine] from a machine type's catalogue faults.
 * `matchedKeywords` are the fault's full keyword list (not just the ones that fired) —
 * they are the vocabulary a technician recognizes ("gurgling", "no water", ...).
 */
@Serializable
data class IssueSuggestion(
    val faultId: String,
    val label: String,
    val confidence: Float,
    val matchedKeywords: List<String>,
    val action: String,
    val severity: String,
)
