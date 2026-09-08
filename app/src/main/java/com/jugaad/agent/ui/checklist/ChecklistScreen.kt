package com.jugaad.agent.ui.checklist

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jugaad.agent.ui.common.SectionCard
import com.jugaad.agent.ui.services
import com.jugaad.agent.ui.theme.Accent
import com.jugaad.agent.ui.theme.Ink
import com.jugaad.agent.ui.theme.StatusCritical
import com.jugaad.agent.ui.theme.StatusHealthy
import com.jugaad.agent.ui.theme.TextHi
import com.jugaad.agent.ui.theme.TextMid
import kotlinx.coroutines.launch

private enum class Check { PASS, FAIL, UNKNOWN }

@Composable
fun ChecklistScreen(
    assetId: String,
    onProceed: () -> Unit,
    onBack: () -> Unit,
) {
    val ctx = LocalContext.current
    val services = ctx.services()
    val scope = rememberCoroutineScope()

    val micGranted = ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    val hasAccel = services.imuCapture.isAvailable()

    var resting by remember { mutableStateOf(Check.UNKNOWN) }
    var hasBaseline by remember { mutableStateOf(Check.UNKNOWN) }
    var running by remember { mutableStateOf(false) }

    fun runChecks() {
        running = true
        scope.launch {
            hasBaseline = if (services.assetRepository.getBaseline(assetId) != null) Check.PASS else Check.FAIL
            resting = if (services.imuCapture.isRestingStill()) Check.PASS else Check.FAIL
            running = false
        }
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Go / no-go", style = MaterialTheme.typography.displayLarge, color = TextHi)
            Text("Confirm the setup before taking a reading.", color = TextMid)

            SectionCard {
                CheckRow("Microphone permission", if (micGranted) Check.PASS else Check.FAIL)
                CheckRow("Accelerometer present", if (hasAccel) Check.PASS else Check.FAIL)
                CheckRow("Baseline captured for this asset", hasBaseline)
                CheckRow("Phone resting still on the housing", resting)
            }

            Text(
                "Tip: press the phone flat against a solid part of the machine frame — not a panel or guard.",
                color = TextMid,
                style = MaterialTheme.typography.bodyMedium,
            )

            Spacer(Modifier.weight(1f))

            OutlinedButton(
                onClick = { runChecks() },
                enabled = !running,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (running) "Checking…" else "Run checks") }

            val ready = micGranted && hasAccel && hasBaseline == Check.PASS && resting == Check.PASS
            Button(
                onClick = onProceed,
                enabled = ready,
                modifier = Modifier.fillMaxWidth().height(54.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Ink),
            ) { Text("Proceed to diagnose", fontWeight = FontWeight.Bold) }

            OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Back") }
        }
    }
}

@Composable
private fun CheckRow(label: String, state: Check) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        val (icon, tint) = when (state) {
            Check.PASS -> Icons.Default.CheckCircle to StatusHealthy
            Check.FAIL -> Icons.Default.Cancel to StatusCritical
            Check.UNKNOWN -> Icons.Default.RadioButtonUnchecked to TextMid
        }
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        Text("  $label", color = TextHi, modifier = Modifier.padding(start = 4.dp))
    }
}
