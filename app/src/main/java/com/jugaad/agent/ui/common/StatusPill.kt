package com.jugaad.agent.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jugaad.agent.domain.model.MachineStatus
import com.jugaad.agent.ui.theme.StatusCritical
import com.jugaad.agent.ui.theme.StatusCriticalDim
import com.jugaad.agent.ui.theme.StatusHealthy
import com.jugaad.agent.ui.theme.StatusHealthyDim
import com.jugaad.agent.ui.theme.StatusWarning
import com.jugaad.agent.ui.theme.StatusWarningDim
import com.jugaad.agent.ui.theme.TextHi

data class StatusColors(val fg: Color, val dim: Color)

fun MachineStatus.colors(): StatusColors = when (this) {
    MachineStatus.HEALTHY -> StatusColors(StatusHealthy, StatusHealthyDim)
    MachineStatus.WARNING -> StatusColors(StatusWarning, StatusWarningDim)
    MachineStatus.CRITICAL -> StatusColors(StatusCritical, StatusCriticalDim)
}

/**
 * The big traffic-light pill. [large] = result-screen hero size, readable at 3 m.
 */
@Composable
fun StatusPill(
    status: MachineStatus,
    modifier: Modifier = Modifier,
    large: Boolean = false,
) {
    val c = status.colors()
    val pad = if (large) PaddingValues(horizontal = 28.dp, vertical = 14.dp)
    else PaddingValues(horizontal = 14.dp, vertical = 7.dp)
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(c.dim)
            .border(1.5.dp, c.fg, RoundedCornerShape(50))
            .padding(pad),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(if (large) 16.dp else 10.dp)
                .clip(CircleShape)
                .background(c.fg),
        )
        Spacer(Modifier.width(if (large) 12.dp else 8.dp))
        Text(
            text = status.label.uppercase(),
            color = if (large) TextHi else c.fg,
            fontWeight = FontWeight.Bold,
            fontSize = if (large) 30.sp else 14.sp,
            textAlign = TextAlign.Center,
        )
    }
}
