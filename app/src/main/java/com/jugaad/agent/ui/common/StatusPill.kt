package com.jugaad.agent.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jugaad.agent.domain.model.MachineStatus
import com.jugaad.agent.ui.common.fiori.FioriColors
import com.jugaad.agent.ui.common.fiori.FioriRadius
import com.jugaad.agent.ui.common.fiori.FioriStatusLabel
import com.jugaad.agent.ui.common.fiori.semantic

/**
 * The status label, re-skinned onto Fiori tokens via [FioriStatusLabel]. [large] is
 * the result-screen hero size, readable at 3 m, built from the same styling at scale.
 */
@Composable
fun StatusPill(
    status: MachineStatus,
    modifier: Modifier = Modifier,
    large: Boolean = false,
) {
    if (!large) {
        FioriStatusLabel(text = status.label, semantic = status.semantic(), modifier = modifier)
        return
    }
    val color = FioriColors.of(status.semantic())
    Row(
        modifier = modifier
            .clip(FioriRadius)
            .background(color.copy(alpha = FioriColors.STATUS_TINT_ALPHA))
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(14.dp).clip(CircleShape).background(color),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = status.label,
            color = color,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.headlineMedium,
        )
    }
}
