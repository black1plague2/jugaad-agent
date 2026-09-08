package com.jugaad.agent.ui.result

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jugaad.agent.domain.model.MachineStatus
import com.jugaad.agent.ui.common.BackendIndicator
import com.jugaad.agent.ui.common.MetricRow
import com.jugaad.agent.ui.common.SectionCard
import com.jugaad.agent.ui.common.SpectrogramImage
import com.jugaad.agent.ui.common.StatusPill
import com.jugaad.agent.ui.common.colors
import com.jugaad.agent.ui.services
import com.jugaad.agent.ui.theme.Accent
import com.jugaad.agent.ui.theme.Ink
import com.jugaad.agent.ui.theme.TextHi
import com.jugaad.agent.ui.theme.TextMid
import com.jugaad.agent.ui.vmFactory

@Composable
fun ResultScreen(
    assetId: String,
    diagnosisId: String,
    onDone: () -> Unit,
    onHistory: () -> Unit,
) {
    val ctx = LocalContext.current
    val services = ctx.services()
    val vm: ResultViewModel = viewModel(
        factory = vmFactory { ResultViewModel(services, assetId, diagnosisId) },
    )
    val s by vm.state.collectAsStateWithLifecycle()

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { pad ->
        if (s.loading) {
            Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Accent)
            }
            return@Scaffold
        }
        val d = s.diagnosis
        if (d == null) {
            Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
                Text("Diagnosis not found", color = TextMid)
            }
            return@Scaffold
        }

        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(s.assetName, color = TextMid, style = MaterialTheme.typography.titleLarge)

            StatusPill(d.status, large = true)

            Text(
                "%.2f".format(d.anomalyScore),
                color = d.status.colors().fg,
                style = MaterialTheme.typography.displayLarge,
            )
            Text("anomaly score", color = TextMid)

            BackendIndicator(backend = d.backend, heuristic = s.heuristicClassifier)

            SpectrogramImage(bitmap = s.spectrogram, modifier = Modifier.fillMaxWidth())

            SectionCard {
                Text("Advice", color = TextHi, fontWeight = FontWeight.SemiBold)
                Text(
                    d.advice.ifBlank { "No advice generated." },
                    color = TextHi,
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    "source: " + when (d.adviceSource) {
                        com.jugaad.agent.domain.model.Diagnosis.AdviceSource.GEMMA -> "Gemma 3 1B (on-device)"
                        com.jugaad.agent.domain.model.Diagnosis.AdviceSource.TEMPLATE -> "rule template"
                        else -> "—"
                    },
                    color = TextMid,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            SectionCard {
                Text("Details", color = TextHi, fontWeight = FontWeight.SemiBold)
                MetricRow("Status", d.status.label)
                if (d.status != MachineStatus.HEALTHY) {
                    d.faultClass?.let {
                        MetricRow("CNN fault class", "${it.label}  (${"%.0f".format(d.faultConfidence * 100)}%)")
                    }
                }
                MetricRow("Cosine distance", "%.4f".format(d.cosineDistance))
                MetricRow("Healthy spread", "%.4f".format(d.spread))
                if (d.dominantHz > 0) MetricRow("Dominant frequency", "${d.dominantHz.toInt()} Hz")
                MetricRow("IMU vibration index", "%.3f".format(d.imuIndex))
                if (d.faultClass != null) {
                    MetricRow("Backend", d.backend.longLabel)
                    if (d.inferenceMs > 0) MetricRow("CNN latency", "${d.inferenceMs} ms")
                }
            }

            Button(
                onClick = { vm.shareReport(ctx) },
                modifier = Modifier.fillMaxWidth().height(54.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Ink),
            ) {
                Icon(Icons.Default.Share, contentDescription = null)
                Text("  Share report (PNG)", fontWeight = FontWeight.Bold)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onHistory, modifier = Modifier.weight(1f)) { Text("History") }
                OutlinedButton(onClick = onDone, modifier = Modifier.weight(1f)) { Text("Done") }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}
