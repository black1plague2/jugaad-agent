@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.jugaad.agent.ui.result

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jugaad.agent.domain.model.FaultClass
import com.jugaad.agent.domain.model.MachineStatus
import com.jugaad.agent.ui.common.BackendIndicator
import com.jugaad.agent.ui.common.MetricRow
import com.jugaad.agent.ui.common.SectionCard
import com.jugaad.agent.ui.common.SpectrogramImage
import com.jugaad.agent.ui.common.StatusPill
import com.jugaad.agent.ui.common.fiori.FioriColors
import com.jugaad.agent.ui.common.fiori.FioriObjectCell
import com.jugaad.agent.ui.common.fiori.FioriSectionHeader
import com.jugaad.agent.ui.common.fiori.FioriStatusLabel
import com.jugaad.agent.ui.common.fiori.GhostButton
import com.jugaad.agent.ui.common.fiori.Semantic
import com.jugaad.agent.ui.common.fiori.semantic
import com.jugaad.agent.ui.services
import com.jugaad.agent.ui.vmFactory

@OptIn(ExperimentalMaterial3Api::class)
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
                CircularProgressIndicator(color = FioriColors.Brand)
            }
            return@Scaffold
        }
        val d = s.diagnosis
        if (d == null) {
            Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
                Text("Measurement document not found", color = FioriColors.TextSecondary)
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
            // --- Status header: big status chip, score, dominant evidence -----------------
            Text(s.assetName, color = FioriColors.TextSecondary, style = MaterialTheme.typography.titleLarge)

            StatusPill(d.status, large = true)

            Text(
                "Dominant evidence: ${dominantSourceLabel(d.dominantSource)}",
                color = FioriColors.TextSecondary,
                style = MaterialTheme.typography.bodyMedium,
            )
            if (d.dominantSource != "acoustic") {
                Text("Sensor score: %.3f".format(d.sensorScore), color = FioriColors.TextSecondary, style = MaterialTheme.typography.bodyMedium)
            }

            Text(
                "%.2f".format(d.anomalyScore),
                color = FioriColors.of(d.status.semantic()),
                style = MaterialTheme.typography.headlineMedium,
            )
            Text("anomaly score", color = FioriColors.TextSecondary)

            // --- Spectrogram heatmap: kept, prominent right under the header --------------
            SectionCard {
                SpectrogramImage(bitmap = s.spectrogram, modifier = Modifier.fillMaxWidth())
            }

            SectionCard {
                Text("Details", color = FioriColors.TextPrimary, fontWeight = FontWeight.SemiBold)
                MetricRow("Status", d.status.label)
                if (d.status != MachineStatus.HEALTHY) {
                    d.faultClass?.let {
                        MetricRow("CNN fault class", "${it.label}  (${"%.1f".format(d.faultConfidence * 100)}%)")
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

            // --- Likely issues: raised cards with keyword chips + action -------------------
            if (d.status != MachineStatus.HEALTHY && d.issues.isNotEmpty()) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    FioriSectionHeader("Likely issues")
                    d.issues.forEach { issue ->
                        FioriObjectCell(
                            title = issue.label,
                            subtitle = issue.action,
                            status = {
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    FioriStatusLabel(severityLabel(issue.severity), severitySemantic(issue.severity))
                                    if (issue.matchedKeywords.isNotEmpty()) {
                                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            issue.matchedKeywords.take(6).forEach { kw ->
                                                FioriStatusLabel(kw, Semantic.NEUTRAL)
                                            }
                                        }
                                    }
                                }
                            },
                            trailing = { Text("%.1f".format(issue.confidence * 100) + "%") },
                        )
                    }
                }
            }

            // --- Confirm label chips --------------------------------------------------------
            if (s.flRuntimeReady) {
                var selected by remember(d.id) { mutableStateOf(d.faultClass) }
                SectionCard {
                    Text("Confirm label", color = FioriColors.TextPrimary, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Help the on-device model learn from this reading.",
                        color = FioriColors.TextSecondary,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FaultClass.entries.forEach { fc ->
                            FilterChip(
                                selected = selected == fc,
                                onClick = {
                                    selected = fc
                                    vm.confirmLabel(fc)
                                },
                                label = { Text(fc.label) },
                            )
                        }
                    }
                    if (s.labelSaved) {
                        Text("Saved to local training set", color = FioriColors.Positive, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            // --- Notification proposal -------------------------------------------------------
            SectionCard {
                Text("Notification proposal", color = FioriColors.TextPrimary, fontWeight = FontWeight.SemiBold)
                Text(
                    d.advice.ifBlank { "No notification proposal generated." },
                    color = FioriColors.TextPrimary,
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    "source: " + when (d.adviceSource) {
                        com.jugaad.agent.domain.model.Diagnosis.AdviceSource.GEMMA -> "Gemma 3 1B (on-device)"
                        com.jugaad.agent.domain.model.Diagnosis.AdviceSource.TEMPLATE -> "rule template"
                        else -> "-"
                    },
                    color = FioriColors.TextSecondary,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            BackendIndicator(backend = d.backend, heuristic = s.heuristicClassifier)

            GhostButton(
                text = "Share report",
                onClick = { vm.shareReport(ctx) },
                modifier = Modifier.fillMaxWidth(),
                icon = Icons.Default.Share,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GhostButton(text = "Measurement history", onClick = onHistory, modifier = Modifier.weight(1f))
                GhostButton(text = "Done", onClick = onDone, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

private fun dominantSourceLabel(source: String): String = when (source) {
    "acoustic" -> "acoustic"
    "accel" -> "accelerometer"
    "gyro" -> "gyroscope"
    "mag", "magRms" -> "magnetometer"
    else -> source
}

private fun severityLabel(severity: String): String = severity.lowercase().replaceFirstChar { it.uppercase() }

private fun severitySemantic(severity: String): Semantic = when (severity) {
    "WARNING" -> Semantic.CRITICAL
    "CRITICAL" -> Semantic.NEGATIVE
    else -> Semantic.NEUTRAL
}
