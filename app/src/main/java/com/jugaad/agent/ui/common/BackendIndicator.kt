package com.jugaad.agent.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.jugaad.agent.domain.model.InferenceBackend
import com.jugaad.agent.ui.common.fiori.FioriStatusLabel
import com.jugaad.agent.ui.common.fiori.Semantic

/**
 * Demo "backend indicator": shows whether the last CNN result came from the
 * Hexagon NPU (QNN), the CPU (XNNPACK), or the dependency-free heuristic.
 * Backend/engine chips render as Informative per the Fiori vocabulary.
 */
@Composable
fun BackendIndicator(
    backend: InferenceBackend,
    heuristic: Boolean,
    modifier: Modifier = Modifier,
) {
    val label = when {
        heuristic -> "Heuristic"
        backend == InferenceBackend.NPU -> "NPU · QNN"
        backend == InferenceBackend.CPU -> "CPU · XNNPACK"
        else -> "Anomaly only"
    }
    FioriStatusLabel(text = label, semantic = Semantic.INFORMATIVE, modifier = modifier)
}
