package com.jugaad.agent.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jugaad.agent.ui.common.fiori.GhostButton
import com.jugaad.agent.ui.theme.Accent
import com.jugaad.agent.ui.theme.Ink
import com.jugaad.agent.ui.theme.TextHi
import com.jugaad.agent.ui.theme.TextMid

/**
 * Full-bleed get-ready countdown shown before capture starts, in the same visual language as
 * [CaptureOverlay]. No sensor or microphone call runs while this is on screen: it exists purely
 * to give the technician time to put the phone down before anything is recorded.
 */
@Composable
fun PreRollOverlay(
    secondsLeft: Int,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Ink.copy(alpha = 0.96f))
            .padding(28.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Text(
                "Get ready".uppercase(),
                color = Accent,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.5.sp,
                style = MaterialTheme.typography.labelLarge,
            )

            Text(
                "Place the phone flat on the machine housing and let go.",
                color = TextMid,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.fillMaxWidth(),
            )

            Text(
                "Recording starts in",
                color = TextMid,
                style = MaterialTheme.typography.bodyMedium,
            )

            Text(
                text = "$secondsLeft",
                color = TextHi,
                fontSize = 96.sp,
                fontWeight = FontWeight.Bold,
            )

            GhostButton(text = "Cancel", onClick = onCancel, modifier = Modifier.fillMaxWidth())
        }
    }
}
