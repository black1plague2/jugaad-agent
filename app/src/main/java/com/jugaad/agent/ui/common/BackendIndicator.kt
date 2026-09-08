package com.jugaad.agent.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jugaad.agent.domain.model.InferenceBackend
import com.jugaad.agent.ui.theme.Accent
import com.jugaad.agent.ui.theme.InkCard
import com.jugaad.agent.ui.theme.StatusHealthy
import com.jugaad.agent.ui.theme.TextMid

/**
 * Demo "backend indicator": shows whether the last CNN result came from the
 * Hexagon NPU (QNN), the CPU (XNNPACK), or the dependency-free heuristic.
 */
@Composable
fun BackendIndicator(
    backend: InferenceBackend,
    heuristic: Boolean,
    modifier: Modifier = Modifier,
) {
    val (dot, label) = when {
        heuristic -> TextMid to "HEURISTIC"
        backend == InferenceBackend.NPU -> StatusHealthy to "NPU · QNN"
        backend == InferenceBackend.CPU -> Accent to "CPU · XNNPACK"
        else -> TextMid to "ANOMALY ONLY"
    }
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(InkCard)
            .border(1.dp, dot.copy(alpha = 0.5f), RoundedCornerShape(50))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(
            Modifier.size(8.dp).clip(CircleShape).background(dot),
        )
        Spacer(Modifier.width(8.dp))
        Text(label, color = dot, fontWeight = FontWeight.Bold, fontSize = 12.sp)
    }
}
