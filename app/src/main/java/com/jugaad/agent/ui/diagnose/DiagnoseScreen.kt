package com.jugaad.agent.ui.diagnose

import android.Manifest
import androidx.annotation.RequiresPermission
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jugaad.agent.ui.common.CaptureOverlay
import com.jugaad.agent.ui.common.SectionCard
import com.jugaad.agent.ui.common.WaveformView
import com.jugaad.agent.ui.common.fiori.FioriColors
import com.jugaad.agent.ui.common.fiori.PrimaryButton
import com.jugaad.agent.ui.services
import com.jugaad.agent.ui.vmFactory

@RequiresPermission(Manifest.permission.RECORD_AUDIO)
@Composable
fun DiagnoseScreen(
    assetId: String,
    onResult: (assetId: String, diagnosisId: String) -> Unit,
    onBack: () -> Unit,
) {
    val services = LocalContext.current.services()
    val vm: DiagnoseViewModel = viewModel(factory = vmFactory { DiagnoseViewModel(services, assetId) })
    val phase by vm.phase.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()

    LaunchedEffect(progress.running, progress.fraction) {
        if (!progress.running && progress.fraction >= 1f) vm.markAnalyzing()
    }
    LaunchedEffect(phase) {
        (phase as? DiagnoseViewModel.Phase.Done)?.let { onResult(assetId, it.diagnosisId) }
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            Column(
                Modifier.fillMaxSize().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text("Take reading", style = MaterialTheme.typography.headlineMedium, color = FioriColors.TextPrimary)
                Text(
                    "One 3-second reading. Rest the phone flat on the machine housing and keep still.",
                    color = FioriColors.TextSecondary,
                )

                SectionCard {
                    WaveformView(peaks = progress.waveform, modifier = Modifier.fillMaxWidth())
                    when (val p = phase) {
                        DiagnoseViewModel.Phase.Idle ->
                            Text("Ready.", color = FioriColors.TextSecondary)
                        DiagnoseViewModel.Phase.Capturing ->
                            Text("Listening", color = FioriColors.TextPrimary)
                        DiagnoseViewModel.Phase.Analyzing ->
                            Text("Analysing: log-mel, anomaly score, CNN, notification proposal", color = FioriColors.TextPrimary)
                        is DiagnoseViewModel.Phase.Done ->
                            Text("Done.", color = FioriColors.TextPrimary)
                        is DiagnoseViewModel.Phase.Error ->
                            Text(p.message, color = FioriColors.Negative)
                    }
                }

                Box(Modifier.weight(1f))

                when (phase) {
                    DiagnoseViewModel.Phase.Idle -> PrimaryButton(
                        text = "Start reading",
                        onClick = vm::start,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    is DiagnoseViewModel.Phase.Error -> PrimaryButton(
                        text = "Retry",
                        onClick = { vm.reset(); vm.start() },
                        modifier = Modifier.fillMaxWidth(),
                    )

                    else -> Unit
                }
                TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                    Text("Back", color = FioriColors.TextSecondary)
                }
            }

            if (progress.running) {
                CaptureOverlay(progress = progress, label = "Reading")
            } else if (phase == DiagnoseViewModel.Phase.Analyzing) {
                Box(
                    Modifier.fillMaxSize().padding(pad),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = FioriColors.Brand)
                        Text(
                            "Computing",
                            color = FioriColors.TextSecondary,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                }
            }
        }
    }
}
