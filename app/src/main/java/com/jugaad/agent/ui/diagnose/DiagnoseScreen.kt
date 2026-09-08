package com.jugaad.agent.ui.diagnose

import android.Manifest
import androidx.annotation.RequiresPermission
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jugaad.agent.ui.common.CaptureOverlay
import com.jugaad.agent.ui.common.SectionCard
import com.jugaad.agent.ui.services
import com.jugaad.agent.ui.theme.Accent
import com.jugaad.agent.ui.theme.Ink
import com.jugaad.agent.ui.theme.StatusCritical
import com.jugaad.agent.ui.theme.TextHi
import com.jugaad.agent.ui.theme.TextMid
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
                Text("Diagnose", style = MaterialTheme.typography.displayLarge, color = TextHi)
                Text(
                    "One 3-second reading. Rest the phone flat on the machine housing and keep still.",
                    color = TextMid,
                )

                SectionCard {
                    when (val p = phase) {
                        DiagnoseViewModel.Phase.Idle ->
                            Text("Ready.", color = TextMid)
                        DiagnoseViewModel.Phase.Capturing ->
                            Text("Listening…", color = TextHi)
                        DiagnoseViewModel.Phase.Analyzing ->
                            Row2("Analysing (log-mel → anomaly score → CNN → advice)…")
                        is DiagnoseViewModel.Phase.Done ->
                            Text("Done.", color = TextHi)
                        is DiagnoseViewModel.Phase.Error ->
                            Text(p.message, color = StatusCritical)
                    }
                }

                Box(Modifier.weight(1f))

                when (phase) {
                    DiagnoseViewModel.Phase.Idle -> Button(
                        onClick = vm::start,
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Ink),
                    ) { Text("Start reading", fontWeight = FontWeight.Bold) }

                    is DiagnoseViewModel.Phase.Error -> Button(
                        onClick = { vm.reset(); vm.start() },
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Ink),
                    ) { Text("Retry", fontWeight = FontWeight.Bold) }

                    else -> Unit
                }
                OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Back") }
            }

            if (progress.running) {
                CaptureOverlay(progress = progress, label = "Diagnostic reading")
            } else if (phase == DiagnoseViewModel.Phase.Analyzing) {
                Box(
                    Modifier.fillMaxSize().padding(pad),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = Accent)
                        Text(
                            "  Computing…",
                            color = TextMid,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Row2(text: String) = Text(text, color = TextHi)
